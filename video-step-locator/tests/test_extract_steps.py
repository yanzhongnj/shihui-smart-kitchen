"""
步骤抽取模块测试
测试 extract_steps.py 中的 LLM 步骤抽取功能。
"""

import sys
import os
from pathlib import Path

# 将项目根目录加入 Python 路径
PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

import pytest
import json
from unittest.mock import patch, MagicMock

from scripts.extract_steps import build_prompt, extract_steps, validate_steps


class TestBuildPrompt:
    """提示词构建测试"""

    def test_build_prompt_basic(self):
        """测试基本提示词构建"""
        segments = [
            {"start": 0.0, "end": 1.0, "text": "首先我们切菜"},
            {"start": 1.0, "end": 2.0, "text": "然后我们炒菜"},
        ]
        prompt = build_prompt(segments)

        # 验证包含时间参考（新格式：时间参考表中）
        assert "0.00s-1.00s" in prompt
        assert "1.00s-2.00s" in prompt

        # 验证包含文本
        assert "首先我们切菜" in prompt
        assert "然后我们炒菜" in prompt

        # 验证包含格式要求
        assert "summary" in prompt
        assert "original_text" in prompt
        assert "char_start" in prompt
        assert "char_end" in prompt
        assert "JSON" in prompt

    def test_build_prompt_empty_segments(self):
        """测试空 segments"""
        prompt = build_prompt([])
        assert prompt is not None
        assert len(prompt) > 0

    def test_build_prompt_single_segment(self):
        """测试单个 segment"""
        segments = [{"start": 5.5, "end": 10.2, "text": "测试文本"}]
        prompt = build_prompt(segments)
        assert "5.50s-10.20s" in prompt
        assert "测试文本" in prompt

    def test_build_prompt_long_text(self):
        """测试长文本提示词"""
        segments = [
            {"start": i * 10.0, "end": (i + 1) * 10.0, "text": f"这是第{i+1}步操作。"}
            for i in range(20)
        ]
        prompt = build_prompt(segments)
        for i in range(20):
            assert f"这是第{i+1}步操作。" in prompt


class TestExtractSteps:
    """LLM 步骤抽取测试（使用 mock）"""

    @patch('openai.OpenAI')
    def test_extract_steps_json_mode(self, mock_openai):
        """测试 JSON mode 步骤抽取"""
        # 模拟 LLM 响应
        mock_response = MagicMock()
        mock_response.choices[0].message.content = json.dumps({
            "steps": [
                {"index": 1, "summary": "切菜", "original_text": "首先我们切菜", "char_start": 0, "char_end": 6},
                {"index": 2, "summary": "炒菜", "original_text": "然后我们炒菜", "char_start": 7, "char_end": 13},
            ]
        })
        mock_client = MagicMock()
        mock_client.chat.completions.create.return_value = mock_response
        mock_openai.return_value = mock_client

        segments = [
            {"start": 0.0, "end": 1.0, "text": "首先我们切菜"},
            {"start": 1.0, "end": 2.0, "text": "然后我们炒菜"},
        ]

        steps = extract_steps(segments, api_key="sk-test", model_name="gpt-4o")
        assert len(steps) == 2
        assert steps[0]["summary"] == "切菜"
        assert steps[1]["summary"] == "炒菜"

    @patch('openai.OpenAI')
    def test_extract_steps_regex_fallback(self, mock_openai):
        """测试非 JSON mode 下的正则回退解析"""
        # 模拟 LLM 返回包含 JSON 的文本
        mock_response = MagicMock()
        mock_response.choices[0].message.content = """
        以下是从转录中提取的步骤：
        ```json
        {
          "steps": [
            {"index": 1, "summary": "准备食材", "original_text": "准备食材", "char_start": 0, "char_end": 4}
          ]
        }
        ```
        """
        mock_client = MagicMock()
        mock_client.chat.completions.create.return_value = mock_response
        mock_openai.return_value = mock_client

        segments = [{"start": 0.0, "end": 1.0, "text": "准备食材"}]

        # 使用非 gpt 模型名触发正则回退
        steps = extract_steps(segments, api_key="sk-test", model_name="deepseek-chat")
        assert len(steps) == 1
        assert steps[0]["summary"] == "准备食材"

    @patch('openai.OpenAI')
    def test_extract_steps_no_json(self, mock_openai):
        """测试 LLM 输出不含 JSON 时抛出异常"""
        mock_response = MagicMock()
        mock_response.choices[0].message.content = "抱歉，我无法解析这个转录。"
        mock_client = MagicMock()
        mock_client.chat.completions.create.return_value = mock_response
        mock_openai.return_value = mock_client

        segments = [{"start": 0.0, "end": 1.0, "text": "测试"}]

        with pytest.raises(Exception):
            extract_steps(segments, api_key="sk-test", model_name="deepseek-chat")

    @patch('openai.OpenAI')
    def test_extract_steps_empty_response(self, mock_openai):
        """测试 LLM 返回空步骤列表"""
        mock_response = MagicMock()
        mock_response.choices[0].message.content = json.dumps({"steps": []})
        mock_client = MagicMock()
        mock_client.chat.completions.create.return_value = mock_response
        mock_openai.return_value = mock_client

        segments = [{"start": 0.0, "end": 1.0, "text": "测试"}]

        steps = extract_steps(segments, api_key="sk-test", model_name="gpt-4o")
        assert len(steps) == 0


class TestValidateSteps:
    """步骤校验测试"""

    def test_validate_steps_basic(self):
        """测试基本校验"""
        steps = [
            {"index": 1, "summary": "先把菜洗干净切成小块", "original_text": "先把菜洗干净切成小块", "char_start": 0, "char_end": 11},
            {"index": 2, "summary": "然后把切好的菜下锅翻炒", "original_text": "然后把切好的菜下锅翻炒", "char_start": 12, "char_end": 25},
        ]
        validated = validate_steps(steps)
        assert len(validated) == 2
        assert validated[0]["index"] == 1
        assert validated[1]["index"] == 2

    def test_validate_steps_remove_empty(self):
        """测试移除空字段和过短步骤"""
        steps = [
            {"index": 1, "summary": "先把菜洗干净切成小块", "original_text": "先把菜洗干净切成小块"},
            {"index": 2, "summary": "", "original_text": ""},  # 空文本
            {"index": 3, "summary": "短", "original_text": "短"},  # 过短（< 8 字）
            {"index": 4},  # 缺少字段
            "not_a_dict",  # 非 dict
        ]
        validated = validate_steps(steps)
        assert len(validated) == 1
        assert validated[0]["summary"] == "先把菜洗干净切成小块"

    def test_validate_steps_dedup(self):
        """测试去重"""
        steps = [
            {"index": 1, "summary": "先把菜洗干净切成小块", "original_text": "先把菜洗干净切成小块"},
            {"index": 2, "summary": "重复的洗菜步骤", "original_text": "先把菜洗干净切成小块"},  # 重复
            {"index": 3, "summary": "然后把切好的菜下锅翻炒", "original_text": "然后把切好的菜下锅翻炒"},
        ]
        validated = validate_steps(steps)
        assert len(validated) == 2  # 去重后应为 2
        assert validated[0]["summary"] == "先把菜洗干净切成小块"
        assert validated[1]["summary"] == "然后把切好的菜下锅翻炒"

    def test_validate_steps_min_count_warning(self):
        """测试步骤过少警告（不报错）"""
        steps = [
            {"index": 1, "summary": "先把菜洗干净切成小块", "original_text": "先把菜洗干净切成小块"},
        ]
        # 不抛出异常，只是记录警告
        validated = validate_steps(steps, min_count=4)
        assert len(validated) == 1

    def test_validate_steps_max_count_truncate(self):
        """测试步骤过多截断"""
        steps = [
            {"index": i, "summary": f"这是第{i}个操作步骤请查收", "original_text": f"这是第{i}个操作步骤请查收"}
            for i in range(1, 20)
        ]
        validated = validate_steps(steps, max_count=10)
        assert len(validated) == 10  # 截断到 10 个

    def test_validate_steps_reindex_after_dedup(self):
        """测试去重后重新编号"""
        steps = [
            {"index": 1, "summary": "先把菜洗干净切成小块", "original_text": "先把菜洗干净切成小块"},
            {"index": 2, "summary": "重复的洗菜步骤", "original_text": "先把菜洗干净切成小块"},  # 重复
            {"index": 3, "summary": "然后把切好的菜下锅翻炒", "original_text": "然后把切好的菜下锅翻炒"},
        ]
        validated = validate_steps(steps)
        # 去重后剩下 2 个，索引应为 1, 2
        assert validated[0]["index"] == 1
        assert validated[1]["index"] == 2
