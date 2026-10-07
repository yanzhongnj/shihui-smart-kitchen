"""
管线集成测试
测试 pipeline.py 的完整处理流程和各步骤协调。
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
from unittest.mock import patch, MagicMock, ANY

from scripts.pipeline import run_pipeline, setup_logging


class TestPipelineSetup:
    """管线基础功能测试"""

    def test_setup_logging_creates_dir(self, tmp_path):
        """测试日志目录创建"""
        log_dir = str(tmp_path / "custom_logs")
        setup_logging(log_dir)
        assert os.path.exists(log_dir), "日志目录未创建"

    def test_setup_logging_default_dir(self, tmp_path):
        """测试默认日志目录"""
        original_dir = os.getcwd()
        os.chdir(tmp_path)
        try:
            setup_logging()  # 使用默认值 "./logs"
            assert os.path.exists("logs"), "默认日志目录未创建"
        finally:
            os.chdir(original_dir)


class TestRunPipeline:
    """管线运行测试（使用 mock）"""

    @patch('scripts.pipeline.extract_audio')
    @patch('scripts.pipeline.transcribe')
    @patch('scripts.pipeline.extract_steps')
    @patch('scripts.pipeline.validate_steps')
    @patch('scripts.pipeline.TimestampMatcher')
    @patch('scripts.pipeline.adjust_step_timestamps')
    @patch('scripts.pipeline.apply_offset')
    def test_run_pipeline_success(
        self,
        mock_apply_offset,
        mock_adjust,
        mock_matcher_cls,
        mock_validate,
        mock_extract_steps,
        mock_transcribe,
        mock_extract_audio,
        tmp_path,
    ):
        """测试完整管线成功执行"""
        # 模拟各模块返回值
        mock_extract_audio.return_value = str(tmp_path / "audio.wav")

        mock_transcribe.return_value = {
            "segments": [
                {"text": "首先我们切菜", "start": 0.0, "end": 5.0},
                {"text": "然后我们炒菜", "start": 5.0, "end": 10.0},
            ]
        }

        mock_extract_steps.return_value = [
            {"index": 1, "summary": "切菜", "original_text": "首先我们切菜"},
            {"index": 2, "summary": "炒菜", "original_text": "然后我们炒菜"},
        ]
        mock_validate.return_value = [
            {"index": 1, "summary": "切菜", "original_text": "首先我们切菜"},
            {"index": 2, "summary": "炒菜", "original_text": "然后我们炒菜"},
        ]

        # 模拟 TimestampMatcher
        mock_matcher = MagicMock()
        mock_matcher.match_step.side_effect = lambda s: {
            **s,
            "start": 0.0 if s["index"] == 1 else 5.0,
            "end": 5.0 if s["index"] == 1 else 10.0,
            "confidence": "high",
        }
        mock_matcher_cls.return_value = mock_matcher

        # 模拟后处理
        mock_adjust.side_effect = lambda s: s
        mock_apply_offset.side_effect = lambda s, **kw: s

        # 创建临时视频文件
        video_path = tmp_path / "test_video.mp4"
        video_path.write_text("fake video content")

        # 创建需要的目录
        output_dir = str(tmp_path / "output")

        # 运行管线
        result = run_pipeline(
            video_path=str(video_path),
            output_dir=output_dir,
            llm_api_key="sk-test",
            llm_base_url="https://api.deepseek.com/v1",
            llm_model="deepseek-chat",
        )

        # 验证结果
        assert result["video"] == "test_video.mp4"
        assert len(result["steps"]) == 2
        assert result["steps"][0]["summary"] == "切菜"
        assert result["steps"][1]["summary"] == "炒菜"

        # 验证各模块被调用
        mock_extract_audio.assert_called_once()
        mock_transcribe.assert_called_once()
        mock_extract_steps.assert_called_once()
        mock_validate.assert_called_once()

    @patch('scripts.pipeline.extract_audio')
    @patch('scripts.pipeline.transcribe')
    def test_run_pipeline_no_llm_key(self, mock_transcribe, mock_extract_audio, tmp_path):
        """测试未提供 LLM API 密钥"""
        mock_extract_audio.return_value = str(tmp_path / "audio.wav")
        mock_transcribe.return_value = {
            "segments": [{"text": "测试", "start": 0.0, "end": 1.0}]
        }

        video_path = tmp_path / "test.mp4"
        video_path.write_text("fake")

        result = run_pipeline(
            video_path=str(video_path),
            output_dir=str(tmp_path / "output"),
            llm_api_key="",  # 空密钥
        )
        assert result == {}  # 应返回空结果

    @patch('scripts.pipeline.extract_audio')
    @patch('scripts.pipeline.transcribe')
    def test_run_pipeline_short_transcript(self, mock_transcribe, mock_extract_audio, tmp_path):
        """测试转录文本过短"""
        mock_extract_audio.return_value = str(tmp_path / "audio.wav")
        mock_transcribe.return_value = {
            "segments": [{"text": "嗨", "start": 0.0, "end": 1.0}]  # 仅 1 个字符
        }

        video_path = tmp_path / "test.mp4"
        video_path.write_text("fake")

        result = run_pipeline(
            video_path=str(video_path),
            output_dir=str(tmp_path / "output"),
            llm_api_key="sk-test",
        )
        assert "error" in result  # 应返回错误信息

    @patch('scripts.pipeline.extract_audio')
    @patch('scripts.pipeline.transcribe')
    @patch('scripts.pipeline.extract_steps')
    @patch('scripts.pipeline.validate_steps')
    def test_run_pipeline_no_steps(
        self,
        mock_validate,
        mock_extract_steps,
        mock_transcribe,
        mock_extract_audio,
        tmp_path,
    ):
        """测试未提取到步骤"""
        mock_extract_audio.return_value = str(tmp_path / "audio.wav")
        mock_transcribe.return_value = {
            "segments": [{"text": "测试文本内容", "start": 0.0, "end": 5.0}]
        }
        mock_extract_steps.return_value = []
        mock_validate.return_value = []

        video_path = tmp_path / "test.mp4"
        video_path.write_text("fake")

        result = run_pipeline(
            video_path=str(video_path),
            output_dir=str(tmp_path / "output"),
            llm_api_key="sk-test",
        )
        assert result.get("steps") == []

    def test_run_pipeline_skip_audio_no_file(self, tmp_path):
        """测试跳过音频提取但文件不存在"""
        video_path = tmp_path / "test.mp4"
        video_path.write_text("fake")

        result = run_pipeline(
            video_path=str(video_path),
            output_dir=str(tmp_path / "output"),
            skip_audio=True,
        )
        assert result == {}  # 应返回空

    @patch('scripts.pipeline.extract_steps')
    @patch('scripts.pipeline.validate_steps')
    @patch('scripts.pipeline.TimestampMatcher')
    @patch('scripts.pipeline.adjust_step_timestamps')
    @patch('scripts.pipeline.apply_offset')
    def test_run_pipeline_output_format(
        self,
        mock_apply_offset,
        mock_adjust,
        mock_matcher_cls,
        mock_validate,
        mock_extract_steps,
        tmp_path,
    ):
        """测试输出格式正确性"""
        # 模拟已有音频和转录
        audio_path = tmp_path / "audio" / "test.wav"
        audio_path.parent.mkdir(parents=True)
        audio_path.write_text("fake audio")

        segments_path = tmp_path / "segments"
        segments_path.mkdir(parents=True)
        seg_file = segments_path / "test_segments.json"
        with open(seg_file, "w", encoding="utf-8") as f:
            json.dump({
                "segments": [{"text": "测试", "start": 0.0, "end": 5.0}]
            }, f)

        mock_extract_steps.return_value = [
            {"index": 1, "summary": "测试步骤", "original_text": "测试", "char_start": 0, "char_end": 2}
        ]
        mock_validate.return_value = [
            {"index": 1, "summary": "测试步骤", "original_text": "测试", "char_start": 0, "char_end": 2}
        ]

        mock_matcher = MagicMock()
        mock_matcher.match_step.return_value = {
            "index": 1, "summary": "测试步骤", "original_text": "测试",
            "start": 0.0, "end": 5.0, "confidence": "high",
        }
        mock_matcher_cls.return_value = mock_matcher
        mock_adjust.side_effect = lambda s: s
        mock_apply_offset.side_effect = lambda s, **kw: s

        video_path = tmp_path / "test.mp4"
        video_path.write_text("fake")

        result = run_pipeline(
            video_path=str(video_path),
            output_dir=str(tmp_path / "output2"),
            skip_audio=True,
            skip_transcribe=True,
            llm_api_key="sk-test",
        )

        # 验证输出格式
        assert "video" in result
        assert "duration" in result
        assert "steps" in result
        if result["steps"]:
            step = result["steps"][0]
            assert "index" in step
            assert "summary" in step
            assert "start" in step
            assert "end" in step
            assert "text" in step
            assert "confidence" in step

    def test_run_pipeline_video_not_found(self, tmp_path):
        """测试视频文件不存在"""
        result = run_pipeline(
            video_path=str(tmp_path / "nonexistent.mp4"),
            output_dir=str(tmp_path / "output"),
        )
        # 音频提取时 file not found 会传播异常
        # 但 run_pipeline 本身不捕获这个异常——它向上抛出
        with pytest.raises(FileNotFoundError):
            run_pipeline(
                video_path=str(tmp_path / "nonexistent.mp4"),
                output_dir=str(tmp_path / "output"),
            )


class TestPipelineEndToEnd:
    """端到端集成测试"""

    def test_with_real_segments(self):
        """使用已有真实输出数据测试匹配流程"""
        # 尝试加载已处理的 segments 数据
        segments_file = Path(__file__).resolve().parent.parent / "output" / "segments" / "cooking_segments.json"
        steps_file = Path(__file__).resolve().parent.parent / "output" / "steps" / "cooking_steps.json"

        if not segments_file.exists():
            pytest.skip("真实 segments 数据不存在，跳过集成测试")

        # 加载 segments
        with open(segments_file, "r", encoding="utf-8") as f:
            data = json.load(f)
        segments = data.get("segments", [])

        assert len(segments) > 0, "segments 数据不应为空"

        # 测试字符索引构建
        from scripts.match_timestamps import build_char_index
        full_text, char_index = build_char_index(segments)
        assert len(full_text) > 0
        assert len(char_index) == len(full_text)

        # 如果也有步骤数据，测试匹配
        if steps_file.exists():
            with open(steps_file, "r", encoding="utf-8") as f:
                steps_data = json.load(f)

            assert "steps" in steps_data
            assert "duration" in steps_data
            assert steps_data["duration"] > 0

            # 验证步骤数据完整性
            for step in steps_data["steps"]:
                assert "index" in step
                assert "summary" in step
                assert "start" in step
                assert "end" in step
                assert "confidence" in step
                assert step["end"] > step["start"], f"步骤 {step['index']} 的 end 应大于 start"
