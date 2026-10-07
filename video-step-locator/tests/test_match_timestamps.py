"""
时间戳映射模块测试
测试 match_timestamps.py 中的三级匹配流水线。
"""

import sys
import os
from pathlib import Path

# 将项目根目录加入 Python 路径
PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

import pytest
import json
import tempfile
from unittest.mock import patch, MagicMock

from scripts.match_timestamps import (
    build_char_index,
    match_by_char_offset,
    fuzzy_text_match,
    semantic_match,
    TimestampMatcher,
    adjust_step_timestamps,
    apply_offset,
    _normalize_text,
)


# ============================================================
# Fixtures
# ============================================================

@pytest.fixture
def sample_segments():
    """提供示例 segments 数据"""
    return [
        {"text": "首先我们切菜", "start": 0.0, "end": 5.0},
        {"text": "然后把菜炒熟", "start": 5.0, "end": 10.0},
        {"text": "最后装盘出锅", "start": 10.0, "end": 15.0},
    ]


@pytest.fixture
def sample_char_index(sample_segments):
    """提供构建好的字符索引"""
    return build_char_index(sample_segments)


# ============================================================
# 工具函数测试
# ============================================================

class TestNormalizeText:
    """文本标准化测试"""

    def test_normalize_remove_spaces(self):
        """测试去除空格"""
        assert _normalize_text("hello world") == "helloworld"
        assert _normalize_text("  hello  ") == "hello"

    def test_normalize_fullwidth_spaces(self):
        """测试去除全角空格"""
        assert _normalize_text("你好\u3000世界") == "你好世界"

    def test_normalize_punctuation(self):
        """测试标点符号统一"""
        assert "，" in "你好，世界"  # 中文逗号
        result = _normalize_text("你好，世界。")
        assert "，" not in result
        assert "。" not in result

    def test_normalize_quotes(self):
        """测试引号统一"""
        result = _normalize_text('\u201c你好\u201d')
        assert '\u201c' not in result
        assert '\u201d' not in result

    def test_normalize_empty(self):
        """测试空字符串"""
        assert _normalize_text("") == ""

    def test_normalize_no_change(self):
        """测试无需改变的文本"""
        text = "helloworld123"
        assert _normalize_text(text) == text


# ============================================================
# 一级匹配：字符偏移
# ============================================================

class TestBuildCharIndex:
    """字符索引构建测试"""

    def test_build_char_index_basic(self, sample_segments):
        """测试基本字符索引构建"""
        full_text, char_index = build_char_index(sample_segments)
        assert len(full_text) > 0
        assert len(char_index) == len(full_text)
        assert len(char_index) == 18  # 6 + 6 + 6 = 18 个字

    def test_build_char_index_first_char(self, sample_segments):
        """测试第一个字符的时间"""
        full_text, char_index = build_char_index(sample_segments)
        assert char_index[0][0] == 0  # 第一个字符索引为 0
        assert char_index[0][1] == 0  # 属于第 0 个 segment
        assert char_index[0][2] == 0.0  # 起始时间

    def test_build_char_index_last_char(self, sample_segments):
        """测试最后一个字符的时间"""
        full_text, char_index = build_char_index(sample_segments)
        last = char_index[-1]
        assert last[0] == len(full_text) - 1
        assert last[3] == 15.0  # segment 的 end 时间


class TestMatchByCharOffset:
    """字符偏移匹配测试"""

    def test_match_by_char_offset_exact(self, sample_char_index):
        """测试精确字符偏移匹配"""
        full_text, char_index = sample_char_index
        result = match_by_char_offset(3, 6, char_index)
        assert result is not None
        assert result["confidence"] == "high"
        assert result["start"] >= 0
        assert result["end"] > result["start"]

    def test_match_by_char_offset_none(self, sample_char_index):
        """测试空偏移返回 None"""
        full_text, char_index = sample_char_index
        result = match_by_char_offset(None, None, char_index)
        assert result is None

    def test_match_by_char_offset_out_of_range(self, sample_char_index):
        """测试超出范围的偏移"""
        full_text, char_index = sample_char_index
        result = match_by_char_offset(999, 1000, char_index)
        assert result is None

    def test_match_by_char_offset_partial(self, sample_char_index):
        """测试部分匹配（start 有效，end 无效时返回 None）"""
        full_text, char_index = sample_char_index
        result = match_by_char_offset(0, 999, char_index)
        assert result is None  # end 超出范围，无法匹配


# ============================================================
# 二级匹配：模糊匹配
# ============================================================

class TestFuzzyTextMatch:
    """模糊文本匹配测试"""

    def test_fuzzy_match_exact(self, sample_segments):
        """测试精确匹配"""
        result = fuzzy_text_match("首先我们切菜", sample_segments)
        assert result is not None
        assert result["confidence"] == "medium"
        assert result["start"] == 0.0
        assert result["end"] == 5.0

    def test_fuzzy_match_partial(self, sample_segments):
        """测试部分匹配"""
        result = fuzzy_text_match("我们切菜", sample_segments, min_ratio=0.5)
        assert result is not None

    def test_fuzzy_match_no_match(self, sample_segments):
        """测试无匹配"""
        result = fuzzy_text_match("完全不相关的内容", sample_segments)
        assert result is None

    def test_fuzzy_match_cross_segment(self, sample_segments):
        """测试跨段匹配"""
        # "切菜然后把菜炒熟" 跨越了 segment 0 和 1
        result = fuzzy_text_match("切菜然后把菜炒熟", sample_segments, min_ratio=0.5)
        assert result is not None

    def test_fuzzy_match_empty_text(self, sample_segments):
        """测试空文本"""
        result = fuzzy_text_match("", sample_segments)
        assert result is None


# ============================================================
# 三级匹配：语义匹配
# ============================================================

class TestSemanticMatch:
    """语义匹配测试"""

    def test_semantic_match_exact(self, sample_segments):
        """测试精确语义匹配"""
        # 只要有 sentence-transformers 就测试真实语义匹配
        try:
            result = semantic_match("切菜", sample_segments, threshold=0.1)
            assert result is not None
            assert result["confidence"] == "low"
        except ImportError:
            pytest.skip("sentence-transformers 未安装")

    def test_semantic_match_high_threshold(self, sample_segments):
        """测试高阈值导致无匹配"""
        try:
            result = semantic_match("无关内容", sample_segments, threshold=0.95)
            # 这个阈值很高，可能匹配不到
            if result:
                assert result["score"] is not None
        except ImportError:
            pytest.skip("sentence-transformers 未安装")


# ============================================================
# 综合匹配器测试
# ============================================================

class TestTimestampMatcher:
    """时间戳匹配器测试"""

    def test_matcher_init(self, sample_segments):
        """测试匹配器初始化"""
        matcher = TimestampMatcher(sample_segments)
        assert matcher.segments == sample_segments
        assert matcher.full_text == "首先我们切菜然后把菜炒熟最后装盘出锅"
        assert len(matcher.char_index) == 18

    def test_matcher_match_by_char_offset(self, sample_segments):
        """测试匹配器 - 字符偏移路径"""
        matcher = TimestampMatcher(sample_segments)
        step = {
            "summary": "切菜",
            "original_text": "首先我们切菜",
            "char_start": 0,
            "char_end": 6,
        }
        result = matcher.match_step(step)
        assert result["start"] is not None
        assert result["end"] is not None
        assert result["confidence"] in ("high", "medium", "low")

    def test_matcher_match_by_fuzzy(self, sample_segments):
        """测试匹配器 - 模糊匹配路径"""
        matcher = TimestampMatcher(sample_segments)
        step = {
            "summary": "炒菜步骤",
            "original_text": "然后把菜炒熟",
            # 没有 char_start/char_end，会走二级匹配
        }
        result = matcher.match_step(step)
        assert "start" in result
        assert "end" in result

    def test_matcher_match_no_text(self, sample_segments):
        """测试匹配器 - 无文本（跳过语义匹配以避免模型加载）"""
        matcher = TimestampMatcher(sample_segments)
        # 直接测试前两级匹配路径：无文本时 char_start/end 为空且 fuzzy 无匹配
        step = {
            "summary": "未知步骤",
            "original_text": "",
        }
        # 不调用 match_step（会触发语义模型加载），直接测试底层逻辑
        offset_result = match_by_char_offset(None, None, matcher.char_index)
        assert offset_result is None

        fuzzy_result = fuzzy_text_match("", sample_segments)
        assert fuzzy_result is None

    def test_matcher_multiple_steps(self, sample_segments):
        """测试匹配器 - 多个步骤"""
        matcher = TimestampMatcher(sample_segments)
        steps = [
            {"summary": "切菜", "original_text": "首先我们切菜", "char_start": 0, "char_end": 6},
            {"summary": "炒菜", "original_text": "然后把菜炒熟", "char_start": 6, "char_end": 12},
            {"summary": "装盘", "original_text": "最后装盘出锅", "char_start": 12, "char_end": 18},
        ]
        for step in steps:
            result = matcher.match_step(step)
            assert result["start"] is not None
            assert result["end"] is not None


# ============================================================
# 后处理测试
# ============================================================

class TestAdjustStepTimestamps:
    """步骤时间修正测试"""

    def test_adjust_basic(self):
        """测试基本时间修正"""
        steps = [
            {"index": 1, "start": 0.0, "end": 5.0, "confidence": "high"},
            {"index": 2, "start": 6.0, "end": 10.0, "confidence": "high"},
        ]
        result = adjust_step_timestamps(steps)
        assert result[0]["start"] == 0.0
        assert result[1]["start"] == 6.0

    def test_adjust_overlap_small_gap(self):
        """测试小重叠修正"""
        steps = [
            {"index": 1, "start": 0.0, "end": 5.0, "confidence": "high"},
            {"index": 2, "start": 4.5, "end": 10.0, "confidence": "high"},  # 与上一步重叠 0.5s
        ]
        result = adjust_step_timestamps(steps)
        assert result[1]["start"] >= result[0]["end"] + 0.5  # 修正后不重叠

    def test_adjust_overlap_large_gap(self):
        """测试大重叠标记低置信度"""
        steps = [
            {"index": 1, "start": 0.0, "end": 10.0, "confidence": "high"},
            {"index": 2, "start": 2.0, "end": 12.0, "confidence": "high"},  # 重叠 8s
        ]
        result = adjust_step_timestamps(steps)
        assert result[1]["confidence"] == "low"  # 标记为低置信度

    def test_adjust_end_less_than_start(self):
        """测试 end < start 的修正（第二个步骤起会被修正）"""
        steps = [
            {"index": 1, "start": 0.0, "end": 5.0, "confidence": "high"},
            {"index": 2, "start": 10.0, "end": 3.0, "confidence": "high"},  # end < start
        ]
        result = adjust_step_timestamps(steps)
        assert result[1]["end"] > result[1]["start"]  # 修正后 end > start
        assert result[1]["end"] == 12.0  # start(10.0) + MIN_STEP_DURATION(2.0)

    def test_adjust_empty(self):
        """测试空列表"""
        result = adjust_step_timestamps([])
        assert result == []


class TestApplyOffset:
    """前置偏移测试"""

    def test_apply_offset_default(self):
        """测试默认偏移"""
        steps = [
            {"index": 1, "start": 5.0, "end": 10.0},
            {"index": 2, "start": 15.0, "end": 20.0},
        ]
        result = apply_offset(steps, offset=0.2)
        assert result[0]["start"] == 4.8  # 5.0 - 0.2
        assert result[1]["start"] == 14.8  # 15.0 - 0.2
        # end 不受影响
        assert result[0]["end"] == 10.0

    def test_apply_offset_no_negative(self):
        """测试不会产生负时间"""
        steps = [
            {"index": 1, "start": 0.1, "end": 5.0},
        ]
        result = apply_offset(steps, offset=0.5, min_time=0.0)
        assert result[0]["start"] >= 0.0  # 不会小于 0

    def test_apply_offset_zero_offset(self):
        """测试零偏移"""
        steps = [
            {"index": 1, "start": 5.0, "end": 10.0},
        ]
        result = apply_offset(steps, offset=0.0)
        assert result[0]["start"] == 5.0

    def test_apply_offset_empty(self):
        """测试空列表"""
        result = apply_offset([])
        assert result == []


# ============================================================
# 集成测试：使用真实数据
# ============================================================

class TestTimestampMatcherIntegration:
    """集成测试 - 使用流水线输出的真实 segments 数据"""

    @pytest.fixture
    def segments_data(self):
        """加载真实或模拟的 segments 数据"""
        # 模拟真实的 WhisperX segments 数据
        return [
            {"text": "我们几块钱店经常会吃鱼香肉丝盖饭", "start": 0.318, "end": 5.230},
            {"text": "今天咱们就做鱼香肉丝", "start": 5.530, "end": 8.018},
            {"text": "先把小料和配菜准备好", "start": 8.423, "end": 12.428},
            {"text": "配菜也就是胡萝卜和尖椒", "start": 13.137, "end": 16.314},
            {"text": "接着我们再来准备一块猪肉", "start": 16.837, "end": 20.213},
            {"text": "把它切成比筷子稍细一点的丝", "start": 20.627, "end": 24.613},
            {"text": "肉丝中加入盐和胡椒粉", "start": 25.052, "end": 29.605},
            {"text": "最后装盘出锅即可", "start": 30.752, "end": 35.943},
        ]

    def test_integration_full_pipeline(self, segments_data):
        """模拟完整匹配流程"""
        matcher = TimestampMatcher(segments_data)

        # 模拟 LLM 提取的步骤
        steps = [
            {"summary": "准备配菜", "original_text": "先把小料和配菜准备好"},
            {"summary": "切肉丝", "original_text": "把它切成比筷子稍细一点的丝"},
            {"summary": "腌制肉丝", "original_text": "肉丝中加入盐和胡椒粉"},
            {"summary": "装盘", "original_text": "最后装盘出锅即可"},
        ]

        matched_count = 0
        for step in steps:
            result = matcher.match_step(step)
            if result.get("start") is not None and result.get("end") is not None:
                matched_count += 1
                # 验证时间戳在合理范围内
                assert result["start"] >= 0
                assert result["end"] > result["start"]
                assert result["end"] <= 40.0

        assert matched_count > 0, "至少应匹配到一个步骤"
