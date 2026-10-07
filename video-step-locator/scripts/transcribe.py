"""
语音识别与对齐模块
使用 WhisperX 将音频转为带单词级时间戳的转录文本。
"""

import json
import logging
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)


def transcribe(
    audio_path: str,
    model_name: str = "large-v2",
    device: str = "cuda",
    compute_type: str = "float16",
    output_dir: Optional[str] = None,
    batch_size: int = 16,
) -> dict:
    """
    使用 WhisperX 进行语音识别和时间对齐。

    Args:
        audio_path: 音频文件路径（.wav）
        model_name: Whisper 模型名称（large-v2, large-v3, base 等）
        device: 计算设备（cuda / cpu）
        compute_type: 计算精度（float16 / float32 / int8）
        output_dir: 转录结果输出目录（None 表示不保存）
        batch_size: 批处理大小

    Returns:
        包含 segments 的完整转录结果字典

    Raises:
        FileNotFoundError: 音频文件不存在
        ImportError: whisperx 未安装
    """
    audio = Path(audio_path)
    if not audio.exists():
        raise FileNotFoundError(f"音频文件不存在: {audio_path}")

    try:
        import whisperx
    except ImportError:
        raise ImportError("请安装 whisperx: pip install whisperx")

    # CPU 不支持 float16，自动降级为 float32
    if device == "cpu" and compute_type == "float16":
        compute_type = "float32"
        logger.info("CPU 不支持 float16，已自动降级为 float32")

    logger.info(f"加载 WhisperX 模型: {model_name} (device={device}, compute={compute_type})")

    # 1. 加载 Whisper 模型并转录
    model = whisperx.load_model(model_name, device=device, compute_type=compute_type)
    logger.info(f"开始转录: {audio.name}")
    result = model.transcribe(str(audio), batch_size=batch_size)
    logger.info(f"转录完成, 检测到语言: {result.get('language', 'unknown')}")

    # 2. 加载对齐模型进行单词级时间对齐（降级友好）
    logger.info("加载对齐模型...")
    align_device = device
    if device == "cuda" and not whisperx.utils.check_cuda():
        align_device = "cpu"
        logger.info("CUDA 不可用，对齐模型回退到 CPU")
    try:
        align_model, metadata = whisperx.load_align_model(
            language_code=result["language"], device=align_device
        )
        logger.info("开始单词级时间对齐...")
        result = whisperx.align(
            result["segments"],
            align_model,
            metadata,
            str(audio),
            device=align_device,
        )
        logger.info(f"对齐完成, 共 {len(result['segments'])} 个 segment")
    except Exception as e:
        logger.warning(f"对齐模型加载失败，跳过对齐步骤: {e}")
        logger.warning("将使用 WhisperX 原始 segment 时间戳（精度稍低但可用）")

    # 3. 保存转录结果
    if output_dir:
        out_dir = Path(output_dir)
        out_dir.mkdir(parents=True, exist_ok=True)

        # 保存完整结果
        seg_path = out_dir / f"{audio.stem}_segments.json"
        # 序列化时将 numpy 类型转为原生 Python 类型
        result_serializable = _make_serializable(result)
        with open(seg_path, "w", encoding="utf-8") as f:
            json.dump(result_serializable, f, ensure_ascii=False, indent=2)
        logger.info(f"转录结果已保存: {seg_path}")

        # 保存纯文本转录
        txt_path = out_dir / f"{audio.stem}_transcript.txt"
        with open(txt_path, "w", encoding="utf-8") as f:
            for seg in result["segments"]:
                start = seg.get("start", 0)
                end = seg.get("end", 0)
                text = seg.get("text", "")
                f.write(f"[{start:.2f}s - {end:.2f}s] {text}\n")
        logger.info(f"纯文本转录已保存: {txt_path}")

    return result


def _make_serializable(obj):
    """递归将对象中的 numpy 类型转为原生 Python 类型。"""
    import numpy as np
    if isinstance(obj, dict):
        return {k: _make_serializable(v) for k, v in obj.items()}
    elif isinstance(obj, list):
        return [_make_serializable(v) for v in obj]
    elif isinstance(obj, np.integer):
        return int(obj)
    elif isinstance(obj, np.floating):
        return float(obj)
    elif isinstance(obj, np.ndarray):
        return obj.tolist()
    return obj


def load_segments(segments_path: str) -> dict:
    """从文件加载 WhisperX 转录结果。"""
    with open(segments_path, "r", encoding="utf-8") as f:
        return json.load(f)
