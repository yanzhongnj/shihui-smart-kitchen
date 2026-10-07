"""
步骤抽取模块
使用 LLM 从转录文本中提取结构化的菜谱操作步骤。
支持 OpenAI / DeepSeek 等兼容 API。
"""

import json
import logging
import re
from typing import Optional

logger = logging.getLogger(__name__)


def build_prompt(segments: list) -> str:
    """
    构建 LLM 提示词。

    注意：转录文本不含时间戳前缀，以便 LLM 返回的 char_start/char_end
    与 build_char_index() 中的字符偏移精确对齐。

    Args:
        segments: WhisperX segments 列表，每个元素含 start, end, text

    Returns:
        格式化后的提示词字符串
    """
    # 纯文本（与 build_char_index 对齐）
    raw_lines = [seg.get("text", "") for seg in segments]
    raw_text = "\n".join(raw_lines)

    # 时间参考表（帮助 LLM 理解时间顺序，不计入偏移）
    offset = 0
    ref_lines = []
    for i, seg in enumerate(segments):
        text = seg.get("text", "")
        start = seg.get("start", 0)
        end = seg.get("end", 0)
        preview = text[:25].replace("\n", " ")
        ref_lines.append(
            f"  片段{i+1} [{start:.2f}s-{end:.2f}s] "
            f"(字符{offset}-{offset+len(text)}): \"{preview}...\""
        )
        offset += len(text) + 1  # +1 对应换行符

    time_reference = "\n".join(ref_lines)

    prompt = f"""你是一个烹饪教学助手。以下是视频中的语音转录文本，按时间顺序列出。

转录文本（字符偏移从0开始，请基于此文本计算 char_start/char_end）：
{raw_text}

时间参考（每个片段的起止时间和字符偏移范围）：
{time_reference}

请提取出所有的做菜操作步骤，忽略问候、重复和闲聊。

要求：
1. 每个步骤用一句简洁的话概括（summary）。
2. 必须从**上面的转录文本**中摘录最能代表该步骤的原文句子（original_text），一字不改。
3. 同时输出 original_text 在**转录文本**中的起始字符偏移位置（char_start）和结束字符偏移位置（char_end）。
4. 严格按操作顺序输出。

约束条件（请严格遵守）：
5. 每个步骤的 original_text 原文长度不得少于 8 个字符（约 2-3 秒以上的讲解内容）。
6. 相邻步骤的 original_text 之间不要有大量重叠（同一句话不要被拆成多个步骤）。
7. 一个完整的操作（如"腌制肉丝""调制酱汁"）应合并为一个步骤，不要拆分成多个子步骤。
8. 整体步骤数量控制在 4~10 个，不要过度细分。
9. 以 JSON 格式返回，格式为：
   {{"steps":[{{"index":1,"summary":"...","original_text":"...","char_start":10,"char_end":30}}, ...]}}

转录文本：
{raw_text}
"""
    return prompt


def extract_steps(
    segments: list,
    api_key: str,
    base_url: str = "https://api.openai.com/v1",
    model_name: str = "gpt-4o",
    temperature: float = 0.1,
    seed: int = 42,
) -> list:
    """
    使用 LLM 从转录中提取结构化步骤。

    Args:
        segments: WhisperX segments 列表
        api_key: LLM API 密钥
        base_url: API 基础地址
        model_name: 模型名称
        temperature: 采样温度（低值使输出更稳定）
        seed: 随机种子，保证多次调用结果一致

    Returns:
        步骤列表，每个元素含 index, summary, original_text, char_start, char_end

    Raises:
        ValueError: LLM 输出解析失败
    """
    from openai import OpenAI

    client = OpenAI(api_key=api_key, base_url=base_url)
    prompt = build_prompt(segments)

    # 判断是否支持 JSON mode
    support_json_mode = "gpt" in model_name.lower()

    kwargs = {
        "model": model_name,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": temperature,
        "seed": seed,
    }
    if support_json_mode:
        kwargs["response_format"] = {"type": "json_object"}

    logger.info(f"调用 LLM: {model_name} (JSON mode: {support_json_mode})")
    response = client.chat.completions.create(**kwargs)
    raw = response.choices[0].message.content
    logger.debug(f"LLM 原始输出: {raw[:200]}...")

    # 统一提取 JSON
    steps_data = None
    if support_json_mode:
        steps_data = json.loads(raw)
    else:
        match = re.search(r'\{.*\}', raw, re.DOTALL)
        if match:
            steps_data = json.loads(match.group())
        else:
            raise ValueError("LLM 输出中未找到有效 JSON")

    steps = steps_data.get("steps", [])
    logger.info(f"LLM 提取到 {len(steps)} 个步骤")

    return steps


def validate_steps(steps: list, min_count: int = 4, max_count: int = 10) -> list:
    """
    校验并清洗 LLM 返回的步骤列表。

    校验规则：
    - 必要字段完整性检查
    - original_text 最小长度（≥ 8 字符）
    - 步骤数量上下限
    - 基于 original_text 去重
    - char_start/char_end 范围合理性
    - 相邻步骤文本重叠检测

    Args:
        steps: 步骤列表
        min_count: 最小合理步骤数
        max_count: 最大合理步骤数

    Returns:
        清洗后的步骤列表
    """
    MIN_TEXT_LENGTH = 8        # original_text 最小字符数
    MIN_CHAR_SPAN = 5          # char_start 到 char_end 最小跨度
    MAX_OVERLAP_RATIO = 0.6    # 相邻步骤 original_text 最大重叠比例

    valid_steps = []

    for step in steps:
        # 检查必要字段
        if not isinstance(step, dict):
            continue
        if not step.get("original_text"):
            continue
        if not step.get("summary"):
            continue

        original_text = step["original_text"].strip()

        # 检查 original_text 最小长度（避免过短片段）
        if len(original_text) < MIN_TEXT_LENGTH:
            logger.debug(f"过滤过短步骤: '{original_text[:20]}...' ({len(original_text)}字符 < {MIN_TEXT_LENGTH})")
            continue

        # 检查 char_start/char_end 范围合理性
        char_start = step.get("char_start")
        char_end = step.get("char_end")
        if char_start is not None and char_end is not None:
            char_span = char_end - char_start
            if char_span < MIN_CHAR_SPAN:
                logger.debug(
                    f"步骤 '{step['summary'][:20]}...' 字符偏移跨度 {char_span} "
                    f"不足 {MIN_CHAR_SPAN}，可能时间过短"
                )

        valid_steps.append({
            "index": len(valid_steps) + 1,
            "summary": step["summary"].strip(),
            "original_text": original_text,
            "char_start": char_start,
            "char_end": char_end,
        })

    # 数量限制
    if len(valid_steps) < min_count:
        logger.warning(f"步骤数量偏少 ({len(valid_steps)} < {min_count})，可能提取不完整")
    elif len(valid_steps) > max_count:
        logger.warning(f"步骤数量偏多 ({len(valid_steps)} > {max_count})，截断到 {max_count}")
        valid_steps = valid_steps[:max_count]

    # 去重：基于 original_text 去重
    seen_texts = set()
    deduped = []
    for step in valid_steps:
        text_key = step["original_text"].replace(" ", "").replace("\u3000", "")
        if text_key not in seen_texts:
            seen_texts.add(text_key)
            step["index"] = len(deduped) + 1
            deduped.append(step)
        else:
            logger.debug(f"去除重复步骤: {step['summary']}")

    # 相邻步骤文本重叠检测：如果两个相邻步骤的 original_text 高度相似，
    # 保留较长的那一个
    merged = []
    for step in deduped:
        if merged:
            prev_text = merged[-1]["original_text"]
            curr_text = step["original_text"]
            # 计算重叠比例
            shorter = min(len(prev_text), len(curr_text))
            if shorter > 0:
                # 简单前缀/包含检测
                if curr_text in prev_text or prev_text in curr_text:
                    overlap_ratio = min(len(curr_text), len(prev_text)) / max(len(curr_text), len(prev_text))
                else:
                    # 最长公共子串近似
                    overlap_ratio = _overlap_ratio(prev_text, curr_text)

                if overlap_ratio > MAX_OVERLAP_RATIO:
                    logger.debug(
                        f"相邻步骤文本重叠 {overlap_ratio:.0%}："
                        f"'{merged[-1]['summary'][:15]}' <-> '{step['summary'][:15]}'，"
                        f"保留较长项"
                    )
                    # 保留 original_text 较长的步骤
                    if len(curr_text) > len(prev_text):
                        merged[-1] = step
                    continue

        merged.append(step)

    # 重新编号
    for i, step in enumerate(merged):
        step["index"] = i + 1

    if len(merged) != len(deduped):
        logger.info(f"合并重叠步骤后: {len(deduped)} -> {len(merged)}")

    return merged


def _overlap_ratio(text1: str, text2: str) -> float:
    """计算两个文本的重叠比例（基于最长公共子串的近似）。"""
    # 使用较短的文本在较长的文本中滑动匹配
    short, long = (text1, text2) if len(text1) <= len(text2) else (text2, text1)
    short_len = len(short)
    if short_len == 0:
        return 0.0

    # 检查短文本是否是长文本的子串
    if short in long:
        return len(short) / len(long)

    # 检查较大前缀重叠
    max_overlap = 0
    for i in range(1, min(len(short), len(long)) + 1):
        if short[:i] in long:
            max_overlap = i

    return max_overlap / len(long)
