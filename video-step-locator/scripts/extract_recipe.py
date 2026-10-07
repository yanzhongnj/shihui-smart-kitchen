"""
菜谱结构化提取模块
从语音转录文本中，用 LLM 整理出结构化菜谱：
标题/摘要/主料/辅料/口味/过敏原/辛辣度/菜系/耗时/热量 + 操作步骤。
复用 OpenAI / DeepSeek 等兼容 API。
"""

import json
import logging
import re

logger = logging.getLogger(__name__)


def build_recipe_prompt(segments: list) -> str:
    raw_text = "\n".join(seg.get("text", "") for seg in segments)
    prompt = f"""你是一个菜谱整理助手。以下是烹饪类音频的语音转录文本（按时间顺序）：

转录文本：
{raw_text}

请整理成结构化菜谱，严格按以下 JSON 格式输出（不要输出其他内容）：
{{
  "title": "菜名",
  "summary": "一句话摘要",
  "main_ingredients": ["主料1", "主料2"],
  "auxiliary_ingredients": ["辅料/调料1", "糖", "盐"],
  "servings": 基准份量(整数人份，如2),
  "ingredients": [
    {{"name": "食材名", "amount": 用量数值(能精确则给克/毫升等数值，无法精确则 null), "unit": "g/ml/个/勺/片/瓣/人份(无法精确则 null)", "tolerance": 误差±数值，如0.5(无法给出则 null), "is_main": 是否主料(布尔), "note": "备注或替换建议，可空"}}
  ],
  "taste_tags": ["口味标签，如 清淡/酸甜/麻辣/咸鲜/香甜"],
  "allergens": ["过敏原，如 花生/海鲜/蛋/奶/麸质/大豆/坚果；没有则填 []"],
  "spice_level": 0到10的整数(0=不辣,10=特辣),
  "cuisine": "菜系，如 川菜/粤菜/鲁菜/家常菜",
  "estimated_time": 预计烹饪总分钟数(整数),
  "calories": 预估每份热量千卡数(整数),
  "steps": [
    {{"index": 1, "summary": "步骤概括", "original_text": "从转录原文中摘录的句子", "start_time": null, "end_time": null, "timer_min": 含固定时长的分钟数(腌制/焯水/焖煮/烤制等，无则 null), "temperature": 精确温度℃(如烤箱180，无则 null), "mode": "烤箱/蒸箱模式：上下火/热风/蒸/空气炸等(无则 null)"}}
  ],
  "device_params": [
    {{"step_index": 步骤序号(0=全程), "device_type": "oven/steamer/cooktop/airfryer", "mode": "模式，如 上下火/蒸/空气炸", "temperature": 温度℃, "duration_min": 时长(分钟), "note": "备注，如 预热至200℃"}}
  ]
}}

要求：
1. steps 4~10 个，严格按操作顺序。
2. original_text 必须从上面的转录文本中摘录原文，一字不改。
3. 只输出 JSON。
4. ingredients 与 main_ingredients/auxiliary_ingredients 一一对应；讲解给出精确克数/毫升（如"鸡胸肉300克"）必须如实填入 amount/unit/tolerance；只说"适量/少许/一把"的食材 amount/unit/tolerance 填 null，如实保留，严禁编造数值。
5. 步骤涉及固定时长（腌制/焯水/焖煮/烤制）必须填 timer_min；涉及温度（烤/蒸/空气炸等）填 temperature 与 mode；无则填 null。
6. 涉及明确设备（烤箱/蒸箱/空气炸锅/灶具 温度/时长/模式）时填 device_params，否则填 []；不要臆造设备。
"""
    return prompt


def extract_recipe(
    segments: list,
    api_key: str,
    base_url: str = "https://api.openai.com/v1",
    model_name: str = "gpt-4o",
    temperature: float = 0.2,
) -> dict:
    """从转录 segments 提取结构化菜谱 JSON。"""
    from openai import OpenAI

    client = OpenAI(api_key=api_key, base_url=base_url)
    prompt = build_recipe_prompt(segments)

    support_json_mode = "gpt" in model_name.lower()
    kwargs = {
        "model": model_name,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": temperature,
    }
    if support_json_mode:
        kwargs["response_format"] = {"type": "json_object"}

    logger.info(f"调用 LLM 提取菜谱: {model_name}")
    response = client.chat.completions.create(**kwargs)
    raw = response.choices[0].message.content

    if support_json_mode:
        data = json.loads(raw)
    else:
        match = re.search(r"\{.*\}", raw, re.DOTALL)
        if not match:
            raise ValueError("LLM 输出中未找到有效 JSON")
        data = json.loads(match.group())

    return {
        "title": data.get("title"),
        "summary": data.get("summary"),
        "main_ingredients": data.get("main_ingredients", []),
        "auxiliary_ingredients": data.get("auxiliary_ingredients", []),
        "servings": data.get("servings"),
        "ingredients": data.get("ingredients", []),
        "taste_tags": data.get("taste_tags", []),
        "allergens": data.get("allergens", []),
        "spice_level": data.get("spice_level"),
        "cuisine": data.get("cuisine"),
        "estimated_time": data.get("estimated_time"),
        "calories": data.get("calories"),
        "steps": data.get("steps", []),
        "device_params": data.get("device_params", []),
    }
