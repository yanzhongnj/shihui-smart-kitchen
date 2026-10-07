"""
完整处理流水线
协调音频提取 → 语音识别 → 步骤抽取 → 时间映射 的全流程。
"""

import argparse
import json
import logging
import sys
from pathlib import Path

# 将项目根目录加入 Python 路径
PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from scripts.extract_audio import extract_audio
from scripts.transcribe import transcribe
from scripts.extract_steps import extract_steps, validate_steps
from scripts.match_timestamps import (
    TimestampMatcher,
    adjust_step_timestamps,
    apply_offset,
)

# 尝试导入 FunASR 客户端（可选）
try:
    from scripts.funasr_client import transcribe_funasr, test_connection as test_funasr
    HAS_FUNASR = True
except (ImportError, ModuleNotFoundError):
    HAS_FUNASR = False

logger = logging.getLogger(__name__)


def setup_logging(log_dir: str = "logs"):
    """配置日志输出到文件和控制台。"""
    log_path = Path(log_dir)
    log_path.mkdir(parents=True, exist_ok=True)

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
        handlers=[
            logging.FileHandler(log_path / "pipeline.log", encoding="utf-8"),
            logging.StreamHandler(sys.stdout),
        ],
    )


def run_pipeline(
    video_path: str,
    output_dir: str,
    whisper_model: str = "large-v2",
    device: str = "cuda",
    llm_api_key: str = "",
    llm_base_url: str = "https://api.openai.com/v1",
    llm_model: str = "gpt-4o",
    offset: float = 0.2,
    skip_audio: bool = False,
    skip_transcribe: bool = False,
    backend: str = "whisper",  # "whisper" 或 "funasr"
) -> dict:
    """
    运行完整处理流水线。

    Args:
        video_path: 输入视频路径
        output_dir: 输出目录
        whisper_model: WhisperX 模型名称
        device: 计算设备 (cuda/cpu)
        llm_api_key: LLM API 密钥
        llm_base_url: LLM API 地址
        llm_model: LLM 模型名称
        offset: 前置偏移秒数
        skip_audio: 跳过音频提取（使用已有音频）
        skip_transcribe: 跳过转录（使用已有转录结果）

    Returns:
        最终步骤数据 dict
    """
    video = Path(video_path)
    output = Path(output_dir)
    video_id = video.stem

    logger.info(f"{'='*60}")
    logger.info(f"开始处理视频: {video.name}")
    logger.info(f"{'='*60}")

    # ---- 阶段1: 音频提取 ----
    audio_path = output / "audio" / f"{video_id}.wav"
    if not skip_audio:
        logger.info("[阶段1/4] 提取音频...")
        extract_audio(str(video), str(audio_path))
    else:
        if audio_path.exists():
            logger.info(f"[阶段1/4] 跳过音频提取，使用已有文件: {audio_path}")
        else:
            logger.error(f"跳过音频提取但文件不存在: {audio_path}")
            return {}

    # ---- 阶段2: 语音识别 ----
    segments_path = output / "segments" / f"{video_id}_segments.json"
    transcript_path = output / "transcripts" / f"{video_id}_transcript.txt"

    if not skip_transcribe:
        if backend == "funasr":
            if not HAS_FUNASR:
                logger.error("FunASR 客户端未安装，请执行: pip install funasr websocket-client")
                return {}
            logger.info("[阶段2/4] FunASR 语音识别...")
            result = transcribe_funasr(str(audio_path))
        else:
            logger.info("[阶段2/4] 语音识别与对齐...")
            result = transcribe(
                str(audio_path),
                model_name=whisper_model,
                device=device,
                output_dir=str(output / "segments"),
            )
        segments = result["segments"]
    else:
        if segments_path.exists():
            logger.info(f"[阶段2/4] 跳过转录，加载已有结果: {segments_path}")
            with open(segments_path, "r", encoding="utf-8") as f:
                result = json.load(f)
            segments = result["segments"]
        else:
            logger.error(f"跳过转录但文件不存在: {segments_path}")
            return {}

    # 检查无旁白视频
    full_text = "".join(seg.get("text", "") for seg in segments)
    if len(full_text.strip()) < 10:
        logger.warning("转录文本过短（< 10 字符），可能为无旁白视频，跳过后续处理")
        return {
            "video": video.name,
            "duration": 0,
            "steps": [],
            "error": "该视频暂未检测到语音讲解",
        }

    # ---- 阶段3: LLM 步骤抽取 ----
    logger.info("[阶段3/4] LLM 步骤抽取...")
    if not llm_api_key:
        logger.error("未提供 LLM API 密钥，无法抽取步骤")
        return {}

    steps = extract_steps(
        segments,
        api_key=llm_api_key,
        base_url=llm_base_url,
        model_name=llm_model,
    )
    steps = validate_steps(steps)
    logger.info(f"  提取到 {len(steps)} 个有效步骤")

    if not steps:
        logger.warning("未提取到任何步骤")
        return {
            "video": video.name,
            "duration": 0,
            "steps": [],
        }

    # ---- 阶段4: 时间戳映射 ----
    logger.info("[阶段4/4] 时间戳映射...")
    matcher = TimestampMatcher(segments)

    for i, step in enumerate(steps):
        logger.info(f"  匹配步骤 {i+1}/{len(steps)}: {step['summary']}")
        step = matcher.match_step(step)
        steps[i] = step

    # 后处理：时间修正 + 前置偏移
    video_duration = segments[-1]["end"] if segments else 0
    steps = adjust_step_timestamps(steps)
    steps = apply_offset(steps, offset=offset)

    # 裁剪：确保所有步骤时间不超出视频时长
    for step in steps:
        if step.get("end", 0) > video_duration:
            logger.warning(
                f"步骤 {step['index']} 的 end({step['end']:.2f}s) "
                f"超出视频时长({video_duration:.2f}s)，已裁剪"
            )
            step["end"] = video_duration
        if step.get("start", 0) > video_duration:
            logger.warning(
                f"步骤 {step['index']} 的 start({step['start']:.2f}s) "
                f"超出视频时长({video_duration:.2f}s)，已裁剪"
            )
            step["start"] = max(video_duration - 2.0, 0)
    output_data = {
        "video": video.name,
        "duration": round(video_duration, 2),
        "steps": [
            {
                "index": s["index"],
                "summary": s["summary"],
                "start": round(s["start"], 2),
                "end": round(s["end"], 2),
                "text": s["original_text"],
                "confidence": s.get("confidence", "low"),
            }
            for s in steps
        ],
    }

    # 保存结果
    steps_dir = output / "steps"
    steps_dir.mkdir(parents=True, exist_ok=True)
    output_path = steps_dir / f"{video_id}_steps.json"
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(output_data, f, ensure_ascii=False, indent=2)

    logger.info(f"处理完成！结果已保存: {output_path}")
    logger.info(f"共 {len(output_data['steps'])} 个步骤")

    return output_data


def main():
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="智能菜谱视频步骤定位系统")
    parser.add_argument("--input", "-i", required=True, help="输入视频文件路径")
    parser.add_argument("--output", "-o", default="./output", help="输出目录")
    parser.add_argument("--whisper-model", default="large-v2", help="WhisperX 模型名称 (tiny/base/small/medium/large-v2/large-v3)")
    parser.add_argument("--device", default="cuda", help="计算设备 (cuda/cpu)")
    parser.add_argument("--llm-api-key", default="", help="LLM API 密钥")
    parser.add_argument("--llm-base-url", default="https://api.openai.com/v1", help="LLM API 地址")
    parser.add_argument("--llm-model", default="deepseek-v4-flash", help="LLM 模型名称")
    parser.add_argument("--offset", type=float, default=0.2, help="前置偏移秒数")
    parser.add_argument("--skip-audio", action="store_true", help="跳过音频提取")
    parser.add_argument("--skip-transcribe", action="store_true", help="跳过语音转录")
    parser.add_argument("--backend", default="whisper", choices=["whisper", "funasr"],
                        help="语音识别后端 (whisper 或 funasr，需要 FunASR Docker 服务)")
    parser.add_argument("--log-dir", default="./logs", help="日志目录")

    args = parser.parse_args()

    setup_logging(args.log_dir)
    run_pipeline(
        video_path=args.input,
        output_dir=args.output,
        whisper_model=args.whisper_model,
        device=args.device,
        llm_api_key=args.llm_api_key,
        llm_base_url=args.llm_base_url,
        llm_model=args.llm_model,
        offset=args.offset,
        skip_audio=args.skip_audio,
        skip_transcribe=args.skip_transcribe,
        backend=args.backend,
    )


if __name__ == "__main__":
    main()
