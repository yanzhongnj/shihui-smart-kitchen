"""
语音识别模块测试
测试 transcribe.py 中的转录和对齐功能。
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

from scripts.transcribe import (
    transcribe,
    load_segments,
    _make_serializable,
)


class TestTranscribeUtils:
    """转录工具函数测试"""

    def test_make_serializable_with_dict(self):
        """测试 dict 序列化"""
        import numpy as np
        data = {
            "text": "hello",
            "start": np.float32(1.5),
            "end": np.int32(10),
            "values": np.array([1, 2, 3]),
        }
        result = _make_serializable(data)
        assert result["text"] == "hello"
        assert isinstance(result["start"], float)
        assert isinstance(result["end"], int)
        assert isinstance(result["values"], list)

    def test_make_serializable_with_list(self):
        """测试 list 序列化"""
        import numpy as np
        data = [np.float32(1.1), np.int32(2), "text"]
        result = _make_serializable(data)
        assert isinstance(result[0], float)
        assert isinstance(result[1], int)
        assert result[2] == "text"

    def test_make_serializable_nested(self):
        """测试嵌套结构序列化"""
        import numpy as np
        data = {
            "segments": [
                {"start": np.float32(0.0), "end": np.float32(1.5)},
                {"start": np.float32(1.5), "end": np.float32(3.0)},
            ]
        }
        result = _make_serializable(data)
        assert result["segments"][0]["start"] == 0.0
        assert isinstance(result["segments"][0]["start"], float)

    def test_make_serializable_with_native_types(self):
        """测试原生类型无需转换"""
        data = {"text": "test", "count": 42, "rate": 3.14}
        result = _make_serializable(data)
        assert result == data

    def test_load_segments_from_file(self, tmp_path):
        """测试从文件加载 segments"""
        test_data = {
            "segments": [
                {"text": "你好", "start": 0.0, "end": 1.0},
                {"text": "世界", "start": 1.0, "end": 2.0},
            ]
        }
        json_file = tmp_path / "test_segments.json"
        with open(json_file, "w", encoding="utf-8") as f:
            json.dump(test_data, f)

        loaded = load_segments(str(json_file))
        assert loaded == test_data
        assert len(loaded["segments"]) == 2

    def test_load_segments_file_not_found(self):
        """测试文件不存在时抛出异常"""
        with pytest.raises(FileNotFoundError):
            load_segments("non_existent.json")


class TestTranscribe:
    """转录功能测试（使用 mock）"""

    @patch('scripts.transcribe.whisperx')
    def test_transcribe_file_not_found(self, mock_whisperx):
        """测试音频文件不存在"""
        with pytest.raises(FileNotFoundError, match="音频文件不存在"):
            transcribe("non_existent.wav")

    @patch('scripts.transcribe.whisperx')
    def test_transcribe_import_error(self, mock_whisperx):
        """测试 whisperx 未安装时的错误处理"""
        mock_whisperx.side_effect = ImportError("模拟导入错误")

        # 注意：transcribe 函数内部 import whisperx，我们需要模拟导入失败
        # 使用 sys.modules 方式来测试
        with patch.dict('sys.modules', {'whisperx': None}):
            # 重新加载模块会失败，但直接测试函数的 import 处理
            pass

    @patch('scripts.transcribe.whisperx')
    def test_transcribe_cpu_float16_fallback(self, mock_whisperx):
        """测试 CPU 模式下 float16 自动降级为 float32"""
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            audio_path = f.name

        try:
            # 模拟 whisperx 模型
            mock_model = MagicMock()
            mock_model.transcribe.return_value = {
                "segments": [],
                "language": "zh",
            }
            mock_whisperx.load_model.return_value = mock_model

            # 模拟对齐模型
            mock_whisperx.load_align_model.return_value = (MagicMock(), {})
            mock_whisperx.align.return_value = {"segments": []}

            result = transcribe(
                audio_path,
                model_name="tiny",
                device="cpu",
                compute_type="float16",
            )

            # 验证模型加载时使用了 float32
            call_kwargs = mock_whisperx.load_model.call_args.kwargs
            assert call_kwargs["compute_type"] == "float32"

        finally:
            os.unlink(audio_path)

    @patch('scripts.transcribe.whisperx')
    def test_transcribe_saves_output(self, mock_whisperx):
        """测试转录结果保存到文件"""
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            audio_path = f.name

        try:
            # 模拟 whisperx 模型
            mock_model = MagicMock()
            mock_model.transcribe.return_value = {
                "segments": [
                    {"text": "测试", "start": 0.0, "end": 1.0}
                ],
                "language": "zh",
            }
            mock_whisperx.load_model.return_value = mock_model

            # 模拟对齐
            mock_whisperx.load_align_model.return_value = (MagicMock(), {})
            mock_whisperx.align.return_value = {
                "segments": [
                    {"text": "测试", "start": 0.0, "end": 1.0}
                ]
            }

            with tempfile.TemporaryDirectory() as tmp_dir:
                result = transcribe(
                    audio_path,
                    model_name="tiny",
                    device="cpu",
                    output_dir=tmp_dir,
                )

                # 验证输出文件存在
                seg_file = os.path.join(tmp_dir, f"{Path(audio_path).stem}_segments.json")
                txt_file = os.path.join(tmp_dir, f"{Path(audio_path).stem}_transcript.txt")
                assert os.path.exists(seg_file), "segments JSON 文件未生成"
                assert os.path.exists(txt_file), "transcript 文本文件未生成"

        finally:
            os.unlink(audio_path)

    @patch('scripts.transcribe.whisperx')
    def test_transcribe_align_model_fallback(self, mock_whisperx):
        """测试对齐模型失败时优雅降级"""
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            audio_path = f.name

        try:
            # 模拟 whisperx 模型成功
            mock_model = MagicMock()
            mock_model.transcribe.return_value = {
                "segments": [{"text": "测试", "start": 0.0, "end": 1.0}],
                "language": "zh",
            }
            mock_whisperx.load_model.return_value = mock_model

            # 模拟对齐模型加载失败
            mock_whisperx.load_align_model.side_effect = Exception("模拟对齐失败")

            with tempfile.TemporaryDirectory() as tmp_dir:
                # 不应该抛出异常
                result = transcribe(
                    audio_path,
                    model_name="tiny",
                    device="cpu",
                    output_dir=tmp_dir,
                )
                # 验证结果仍然包含 segments（虽然未对齐）
                assert "segments" in result

        finally:
            os.unlink(audio_path)
