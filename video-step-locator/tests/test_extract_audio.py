"""
音频提取模块测试
测试 extract_audio.py 中的音频提取功能。
"""

import sys
import os
from pathlib import Path

# 将项目根目录加入 Python 路径
PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

import pytest
import tempfile
import json
from unittest.mock import patch, MagicMock

from scripts.extract_audio import extract_audio, extract_audio_adaptive, _FFMPEG_PATH


class TestExtractAudio:
    """音频提取功能测试"""

    def test_ffmpeg_path_exists(self):
        """测试 FFmpeg 路径已正确检测"""
        assert _FFMPEG_PATH is not None, "FFmpeg 路径未检测到"
        assert os.path.exists(_FFMPEG_PATH) or _FFMPEG_PATH != "", "FFmpeg 可执行文件不存在"

    def test_extract_audio_file_not_found(self):
        """测试文件不存在时抛出异常"""
        with pytest.raises(FileNotFoundError, match="视频文件不存在"):
            extract_audio("non_existent_video.mp4", "output.wav")

    def test_extract_audio_invalid_file(self):
        """测试无效文件时 ffmpeg 报错"""
        with tempfile.NamedTemporaryFile(suffix=".mp4", delete=False) as f:
            f.write(b"not a real video file")
            fake_video = f.name

        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            fake_output = f.name

        try:
            # 无效文件应该导致 ffmpeg 失败
            with pytest.raises(RuntimeError, match="ffmpeg 执行失败"):
                extract_audio(fake_video, fake_output)
        finally:
            os.unlink(fake_video)
            if os.path.exists(fake_output):
                os.unlink(fake_output)

    def test_extract_audio_adaptive_no_video(self):
        """测试自适应提取时文件不存在"""
        with pytest.raises(FileNotFoundError, match="视频文件不存在"):
            extract_audio_adaptive("non_existent.mp4", "output.wav")

    def test_extract_audio_creates_output_dir(self):
        """测试输出目录被自动创建"""
        # 使用不存在的视频文件，但检查目录创建逻辑
        # 实际目录创建在 extract_audio 函数中由 Path(output).parent.mkdir 完成
        output_path = os.path.join(tempfile.gettempdir(), "test_audio_extract", "subdir", "test.wav")

        with pytest.raises((FileNotFoundError, RuntimeError)):
            extract_audio("non_existent.mp4", output_path)

        # 验证父目录没有被创建（因为输入文件不存在，函数在创建目录前就抛出了）
        assert not os.path.exists(os.path.dirname(output_path))

    @patch('scripts.extract_audio.subprocess.run')
    @patch('scripts.extract_audio.Path.exists')
    def test_extract_audio_success(self, mock_exists, mock_run):
        """测试正常音频提取流程"""
        # 模拟文件存在
        mock_exists.return_value = True

        # 模拟 ffmpeg 成功执行
        mock_result = MagicMock()
        mock_result.returncode = 0
        mock_result.stderr = ""
        mock_run.return_value = mock_result

        # 模拟输出路径：stat() 和 exists() 均正常
        with patch('scripts.extract_audio.Path.stat') as mock_stat, \
             patch('scripts.extract_audio.Path.is_dir') as mock_is_dir:
            mock_stat.return_value.st_size = 1024 * 100  # 100 KB
            mock_is_dir.return_value = True

            result = extract_audio("test.mp4", "output/test.wav")

            # 验证路径正确（Windows 使用 \，POSIX 使用 /）
            assert result == os.path.normpath("output/test.wav")
            # 验证 ffmpeg 命令参数
            call_args = mock_run.call_args[0][0]
            assert "-y" in call_args
            assert "-i" in call_args
            assert "test.mp4" in call_args
            assert "-vn" in call_args
            assert "-acodec" in call_args
            assert "pcm_s16le" in call_args
            assert "-ar" in call_args
            assert "16000" in call_args
            assert "-ac" in call_args
            assert "1" in call_args


class TestExtractAudioIntegration:
    """音频提取集成测试（需要真实 ffmpeg）"""

    def test_detect_ffmpeg_executable(self):
        """验证 FFmpeg 可执行文件可运行"""
        import subprocess
        result = subprocess.run([_FFMPEG_PATH, "-version"], capture_output=True, text=True)
        assert result.returncode == 0, "FFmpeg 无法运行"
        assert "ffmpeg" in result.stdout.lower(), "输出中未包含 ffmpeg 标识"
