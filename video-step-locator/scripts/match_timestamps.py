"""
时间戳映射模块
将 LLM 抽取的步骤映射到具体的视频时间区间。

三级匹配流水线：
1. 字符偏移定位 + 跨段合并（主路径）
2. 编辑距离模糊匹配 + 滑动窗口（容差路径）
3. Sentence-BERT 语义匹配（回退路径）
"""

import json
import logging
import shutil
from difflib import SequenceMatcher
from pathlib import Path
from typing import Optional

import numpy as np

logger = logging.getLogger(__name__)

# 全局 Sentence-BERT 模型缓存
_sbert_model = None
_SBERT_MODEL_NAME = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"


def _get_sbert_model():
    """
    懒加载 Sentence-BERT 模型（全局单例）。
    
    从本地 HuggingFace Hub 缓存加载，避免远程访问 huggingface.co。
    如果本地缓存不存在，则仅记录警告并回退到 None（语义匹配降级）。
    """
    global _sbert_model
    if _sbert_model is not None:
        return _sbert_model

    # 强制离线模式，禁止任何远程请求
    import os
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    os.environ["HF_HUB_OFFLINE"] = "1"

    # 从本地 HF Hub 缓存加载
    cache_path = _find_local_model_path(_SBERT_MODEL_NAME)
    if cache_path:
        try:
            from sentence_transformers import SentenceTransformer
            _sbert_model = SentenceTransformer(str(cache_path))
            logger.info(f"Sentence-BERT 模型已加载 (本地缓存: {cache_path})")
        except Exception as e:
            logger.warning(f"Sentence-BERT 本地加载失败: {e}")
    else:
        logger.warning(
            f"Sentence-BERT 模型未在本地缓存中找到，"
            f"语义匹配回退将不可用"
        )

    return _sbert_model


def _find_local_model_path(model_name: str) -> Path:
    """
    在本地 HuggingFace Hub 缓存中查找模型路径。
    
    搜索顺序：
    1. ~/.cache/torch/sentence_transformers/{model_name}/
    2. ~/.cache/huggingface/hub/models--{org}--{name}/snapshots/*/
    """
    import shutil

    home = Path.home()
    
    # 1. 检查 sentence-transformers 专用缓存
    st_cache = home / ".cache" / "torch" / "sentence_transformers"
    model_dir_name = model_name.replace("-", "_").replace("/", "_")
    cached_paths = list(st_cache.glob(f"{model_dir_name}*"))
    if cached_paths:
        return cached_paths[0]

    # 2. 检查 HuggingFace Hub 缓存
    hf_model_name = model_name.replace("/", "--")
    hf_cache = home / ".cache" / "huggingface" / "hub" / f"models--{hf_model_name}"
    snapshots_dir = hf_cache / "snapshots"
    if snapshots_dir.exists():
        snapshots = list(snapshots_dir.iterdir())
        if snapshots:
            # 复制到 sentence-transformers 缓存供下次直接使用
            src = snapshots[0]
            dst = st_cache / model_dir_name
            if not dst.exists():
                dst.mkdir(parents=True, exist_ok=True)
                for item in src.iterdir():
                    if item.is_dir():
                        shutil.copytree(item, dst / item.name, dirs_exist_ok=True)
                    else:
                        shutil.copy2(item, dst)
            return dst

    return None

# ============================================================
# 一级：字符偏移索引
# ============================================================

def build_char_index(segments: list) -> tuple:
    """
    为每个字符构建到 segment + 时间的反向索引。

    Args:
        segments: WhisperX segments 列表

    Returns:
        (full_text, char_index)
        - full_text: 拼接后的完整转录文本
        - char_index: 列表，每个元素为 (char_idx, seg_index, char_time, seg_end)
    """
    char_index = []
    full_text = ""

    for seg_idx, seg in enumerate(segments):
        seg_text = seg.get("text", "")
        seg_start = seg.get("start", 0)
        seg_end = seg.get("end", 0)
        seg_dur = seg_end - seg_start
        text_len = max(len(seg_text), 1)

        for ch_idx in range(len(seg_text)):
            global_idx = len(full_text) + ch_idx
            # 在 segment 内线性插值估算字符时间
            char_time = seg_start + seg_dur * (ch_idx / text_len)
            char_index.append((global_idx, seg_idx, char_time, seg_end))

        full_text += seg_text

    return full_text, char_index


def match_by_char_offset(
    char_start: Optional[int],
    char_end: Optional[int],
    char_index: list,
) -> Optional[dict]:
    """
    一级匹配：通过字符偏移直接定位。

    Args:
        char_start: 起始字符偏移
        char_end: 结束字符偏移
        char_index: build_char_index() 的输出

    Returns:
        匹配结果 dict 或 None
    """
    if char_start is None or char_end is None:
        return None

    start_entry = next((e for e in char_index if e[0] >= char_start), None)
    end_entry = next((e for e in char_index if e[0] >= char_end), None)

    if start_entry and end_entry:
        result = {
            "start": start_entry[2],
            "end": end_entry[3],
            "confidence": "high",
        }
        logger.debug(f"字符偏移匹配成功: char[{char_start}:{char_end}] -> time[{result['start']:.2f}s - {result['end']:.2f}s]")
        return result

    return None


# ============================================================
# 二级：编辑距离模糊匹配（含滑动窗口跨段合并）
# ============================================================

def fuzzy_text_match(step_text: str, segments: list, min_ratio: float = 0.8) -> Optional[dict]:
    """
    二级匹配：编辑距离模糊匹配，支持跨段合并。

    Args:
        step_text: 步骤原文
        segments: WhisperX segments 列表
        min_ratio: 最小匹配比率 (0~1)

    Returns:
        匹配结果 dict 或 None
    """
    step_clean = _normalize_text(step_text)

    # 策略 A：单 segment 逐条匹配
    for seg in segments:
        seg_clean = _normalize_text(seg.get("text", ""))
        ratio = SequenceMatcher(None, step_clean, seg_clean).ratio()
        if ratio >= min_ratio:
            logger.debug(f"模糊匹配成功 (单段, ratio={ratio:.3f})")
            return {
                "start": seg["start"],
                "end": seg["end"],
                "confidence": "medium",
            }

    # 策略 B：滑动窗口合并相邻 segment（处理跨段文本）
    n = len(segments)
    for start_idx in range(n):
        merged_text = ""
        merged_start = segments[start_idx]["start"]
        merged_end = segments[start_idx]["end"]

        for end_idx in range(start_idx, n):
            merged_text += segments[end_idx].get("text", "")
            merged_end = segments[end_idx]["end"]
            merged_clean = _normalize_text(merged_text)

            # 限制合并长度：不超过 step_text 的 1.5 倍，避免过度合并
            if len(merged_clean) > len(step_clean) * 1.5:
                break

            ratio = SequenceMatcher(None, step_clean, merged_clean).ratio()
            if ratio >= min_ratio:
                logger.debug(f"模糊匹配成功 (跨段合并 {start_idx}-{end_idx}, ratio={ratio:.3f})")
                return {
                    "start": merged_start,
                    "end": merged_end,
                    "confidence": "medium",
                }

    return None


def _normalize_text(text: str) -> str:
    """标准化文本：去空格、统一标点。"""
    return (
        text.replace(" ", "")
        .replace("\u3000", "")   # 全角空格
        .replace("，", ",")
        .replace("。", ".")
        .replace("、", ",")
        .replace("？", "?")
        .replace("！", "!")
        .replace("：", ":")
        .replace("；", ";")
        .replace("\u201c", "\"")  # 左双引号
        .replace("\u201d", "\"")  # 右双引号
        .replace("\u2018", "'")   # 左单引号
        .replace("\u2019", "'")   # 右单引号
    )


# ============================================================
# 三级：Sentence-BERT 语义匹配（回退路径）
# ============================================================


def semantic_match(
    step_text: str,
    segments: list,
    threshold: float = 0.7,
    seg_embeddings: Optional[np.ndarray] = None,
) -> Optional[dict]:
    """
    三级匹配：语义相似度匹配。

    Args:
        step_text: 步骤原文
        segments: WhisperX segments 列表
        threshold: 相似度阈值 (0~1)
        seg_embeddings: 预计算的 segment embeddings（批量优化）

    Returns:
        匹配结果 dict 或 None
    """
    model = _get_sbert_model()
    step_emb = model.encode(step_text, convert_to_tensor=True)

    if seg_embeddings is None:
        seg_embeddings = model.encode(
            [seg.get("text", "") for seg in segments],
            convert_to_tensor=True,
        )

    from sentence_transformers import util
    scores = util.cos_sim(step_emb, seg_embeddings)[0]
    best_idx = scores.argmax().item()
    best_score = scores[best_idx].item()

    if best_score >= threshold:
        logger.debug(f"语义匹配成功 (idx={best_idx}, score={best_score:.3f})")
        return {
            "start": segments[best_idx]["start"],
            "end": segments[best_idx]["end"],
            "confidence": "low",
            "score": best_score,
        }

    logger.warning(f"语义匹配失败, 最高分={best_score:.3f} (阈值={threshold})")
    return None


# ============================================================
# 综合匹配调度
# ============================================================

class TimestampMatcher:
    """时间戳匹配器，封装三级流水线和缓存。"""

    def __init__(self, segments: list):
        self.segments = segments
        self.full_text, self.char_index = build_char_index(segments)
        self._seg_embeddings: Optional[np.ndarray] = None

    def match_step(self, step: dict) -> dict:
        """
        对一个步骤执行三级匹配。

        Args:
            step: 步骤 dict，含 original_text, char_start, char_end

        Returns:
            扩充后的步骤 dict，加入 start, end, confidence
        """
        step_text = step.get("original_text", "")
        result = None

        # 一级：字符偏移定位
        result = match_by_char_offset(
            step.get("char_start"),
            step.get("char_end"),
            self.char_index,
        )

        # 二级：编辑距离模糊匹配
        if result is None:
            result = fuzzy_text_match(step_text, self.segments)

        # 三级：语义匹配
        if result is None:
            result = semantic_match(
                step_text,
                self.segments,
                seg_embeddings=self._get_embeddings(),
            )

        if result:
            step["start"] = result["start"]
            step["end"] = result["end"]
            step["confidence"] = result.get("confidence", "low")
        else:
            logger.warning(f"步骤 '{step.get('summary', '')}' 匹配失败，将使用默认时间")
            step["start"] = 0
            step["end"] = 0
            step["confidence"] = "low"

        return step

    def _get_embeddings(self) -> np.ndarray:
        """获取/缓存 segment embeddings。"""
        if self._seg_embeddings is None:
            model = _get_sbert_model()
            self._seg_embeddings = model.encode(
                [seg.get("text", "") for seg in self.segments],
                convert_to_tensor=True,
            )
            logger.debug(f"预计算 {len(self.segments)} 个 segment 的 embedding")
        return self._seg_embeddings


# ============================================================
# 后处理
# ============================================================

MIN_STEP_GAP = 0.5            # 步骤间最小间隔（秒）
MIN_STEP_DURATION = 2.0       # 步骤最小持续时间（秒），防止时长过短
LARGE_OVERLAP_THRESHOLD = 10.0  # 大重叠阈值：超过此值视为前一步 end 有误


def adjust_step_timestamps(steps: list) -> list:
    """
    确保步骤时间单调递增，检测异常偏差并标记低置信度。

    处理策略：
    - 小重叠（< 3s）：正常微调，前移 curr_start
    - 中重叠（3~10s）：标记低置信度，前移 curr_start
    - 大重叠（> 10s）：保留 curr_start，标记前一步 end 有误（避免级联错误）
    - 步骤时长 < MIN_STEP_DURATION：自动扩展

    Args:
        steps: 步骤列表（含时间戳）

    Returns:
        修正后的步骤列表
    """
    for i in range(1, len(steps)):
        prev_end = steps[i - 1].get("end", 0)
        curr_start = steps[i].get("start", 0)

        if curr_start < prev_end:
            gap = prev_end - curr_start
            if gap > LARGE_OVERLAP_THRESHOLD:
                # 大重叠：前一步的 end 很可能有误，保留当前 start
                logger.warning(
                    f"步骤 {steps[i]['index']} 的 start({curr_start:.1f}s) "
                    f"与前一步 end({prev_end:.1f}s) 重叠 {gap:.1f}s > "
                    f"{LARGE_OVERLAP_THRESHOLD}s，推断前一步 end 有误，保留当前 start"
                )
                steps[i - 1]["confidence"] = "low"
                # 修正前一步的 end 为当前 start - MIN_STEP_GAP
                steps[i - 1]["end"] = max(
                    steps[i - 1].get("start", 0),
                    curr_start - MIN_STEP_GAP
                )
            elif gap > 3.0:
                logger.warning(
                    f"步骤 {steps[i]['index']} 的 start({curr_start:.1f}s) "
                    f"远小于前一步 end({prev_end:.1f}s)，差值 {gap:.1f}s，标记低置信度"
                )
                steps[i]["confidence"] = "low"
                # 修正：不低于前一步 end + MIN_STEP_GAP
                steps[i]["start"] = max(curr_start, prev_end + MIN_STEP_GAP)
            else:
                # 小重叠：正常微调
                steps[i]["start"] = max(curr_start, prev_end + MIN_STEP_GAP)

        # 确保 end > start
        if steps[i].get("end", 0) <= steps[i]["start"]:
            steps[i]["end"] = steps[i]["start"] + MIN_STEP_DURATION

    # 确保每个步骤有最小持续时间，并处理相邻步骤时间冲突
    for i in range(len(steps)):
        duration = steps[i].get("end", 0) - steps[i].get("start", 0)
        if duration < MIN_STEP_DURATION:
            # 向后扩展 end
            desired_end = steps[i]["start"] + MIN_STEP_DURATION
            # 检查与下一步的冲突
            if i + 1 < len(steps):
                next_start = steps[i + 1].get("start", float("inf"))
                if next_start <= steps[i]["start"]:
                    # 下一步与当前步骤 start 相同或更早：将下一步往后推
                    steps[i + 1]["start"] = desired_end
                    new_end = desired_end
                else:
                    new_end = min(desired_end, next_start)
            else:
                new_end = desired_end

            if new_end > steps[i]["end"]:
                logger.debug(
                    f"步骤 {steps[i]['index']} 时长 {duration:.2f}s 不足 "
                    f"{MIN_STEP_DURATION}s，扩展到 {new_end:.2f}s"
                )
                steps[i]["end"] = new_end

    return steps


def apply_offset(steps: list, offset: float = 0.2, min_time: float = 0.0) -> list:
    """
    对每个步骤的 start 施加前置偏移，保护边界。

    Args:
        steps: 步骤列表
        offset: 前置偏移秒数（默认 0.2s）
        min_time: 最小时间边界

    Returns:
        处理后的步骤列表
    """
    for step in steps:
        step["start"] = max(step["start"] - offset, min_time)
    return steps
