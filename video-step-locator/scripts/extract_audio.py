"""
音频提取模块
从视频文件中提取可用于语音识别的音频流。
"""

import subprocess
import logging
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)


def _get_ffmpeg_path() -> str:
    """获取 ffmpeg 可执行文件路径，优先使用系统 PATH 中的，其次使用 imageio-ffmpeg 自带的。"""
    import shutil
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg:
        return ffmpeg
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except ImportError:
        raise RuntimeError(
            "未找到 ffmpeg，请安装: pip install imageio-ffmpeg，"
            "或确保 ffmpeg 在系统 PATH 中"
        )


_FFMPEG_PATH = _get_ffmpeg_path()


def extract_audio(
    video_path: str,
    output_path: str,
    sample_rate: int = 16000,
    channels: int = 1,
    codec: str = "pcm_s16le",
) -> str:
    """
    从视频文件中提取音频。

    Args:
        video_path: 输入视频文件路径
        output_path: 输出音频文件路径（.wav）
        sample_rate: 采样率，默认 16000 Hz（WhisperX 推荐）
        channels: 声道数，默认 1（单声道）
        codec: 音频编码，默认 PCM 16-bit 小端

    Returns:
        输出音频文件的路径

    Raises:
        FileNotFoundError: 视频文件不存在
        RuntimeError: ffmpeg 执行失败
    """
    video = Path(video_path)
    if not video.exists():
        raise FileNotFoundError(f"视频文件不存在: {video_path}")

    output = Path(output_path)
    output.parent.mkdir(parents=True, exist_ok=True)

    cmd = [
        _FFMPEG_PATH,
        "-y",                          # 覆盖输出文件
        "-i", str(video),              # 输入文件
        "-vn",                         # 禁用视频流
        "-acodec", codec,              # 音频编码
        "-ar", str(sample_rate),       # 采样率
        "-ac", str(channels),          # 声道数
        str(output),
    ]

    logger.info(f"提取音频: {video.name} -> {output.name}")
    logger.debug(f"ffmpeg 命令: {' '.join(cmd)}")

    result = subprocess.run(cmd, capture_output=True, text=True)

    if result.returncode != 0:
        error_msg = result.stderr.strip() or "未知错误"
        raise RuntimeError(f"ffmpeg 执行失败: {error_msg}")

    if not output.exists():
        raise RuntimeError(f"输出文件未生成: {output_path}")

    logger.info(f"音频提取完成: {output_path} ({output.stat().st_size / 1024:.1f} KB)")
    return str(output)


def extract_audio_adaptive(
    video_path: str,
    output_path: str,
) -> str:
    """
    自适应音频提取：尝试直接复制已有音频流以节省重编码时间。
    若视频已包含 WAV/PCM 格式音频则直接流复制，否则进行转码。

    Args:
        video_path: 输入视频文件路径
        output_path: 输出音频文件路径

    Returns:
        输出音频文件的路径
    """
    video = Path(video_path)
    if not video.exists():
        raise FileNotFoundError(f"视频文件不存在: {video_path}")

    # 探查视频的音频流信息
    probe_cmd = [
        _FFMPEG_PATH.replace("ffmpeg", "ffprobe"),
        "-v", "quiet",
        "-select_streams", "a:0",
        "-show_entries", "stream=codec_name,sample_rate,channels",
        "-of", "csv=p=0",
        str(video),
    ]
    result = subprocess.run(probe_cmd, capture_output=True, text=True)
    if result.returncode == 0 and result.stdout.strip():
        parts = result.stdout.strip().split(",")
        codec_name = parts[0] if len(parts) > 0 else ""
        sample_rate = parts[1] if len(parts) > 1 else ""
        channels = parts[2] if len(parts) > 2 else ""

        # 如果已经是 PCM 16kHz 单声道，直接流复制
        if codec_name == "pcm_s16le" and sample_rate == "16000" and channels == "1":
            copy_cmd = [
                _FFMPEG_PATH, "-y",
                "-i", str(video),
                "-vn", "-acodec", "copy",
                str(output_path),
            ]
            logger.info(f"音频流已是目标格式，直接复制: {output_path}")
            subprocess.run(copy_cmd, capture_output=True, text=True)
            if Path(output_path).exists():
                return str(output_path)

    # 默认走转码路径
    return extract_audio(video_path, output_path)
