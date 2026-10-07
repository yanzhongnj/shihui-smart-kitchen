"""
FunASR 语音识别客户端
通过 Docker 容器内的 C++ WebSocket 客户端进行语音识别。
"""

import json
import logging
import subprocess
import tempfile
from pathlib import Path

logger = logging.getLogger(__name__)

FUNASR_HOST = "localhost"
FUNASR_PORT = 10096
CONTAINER_NAME = "funasr"
CONTAINER_PORT = 10095


def transcribe_funasr(
    audio_path: str,
    timeout: int = 120,
) -> dict:
    """
    使用 FunASR Docker 服务进行语音识别。

    将音频文件复制到容器内，通过容器内的 C++ 客户端调用 WebSocket 服务。
    """
    audio = Path(audio_path)
    if not audio.exists():
        raise FileNotFoundError(f"音频文件不存在: {audio_path}")

    logger.info(f"FunASR 开始识别: {audio.name}")

    # 复制音频到容器
    container_wav = f"/workspace/{audio.name}"
    copy_cmd = ["docker", "cp", str(audio.resolve()), f"{CONTAINER_NAME}:{container_wav}"]
    subprocess.run(copy_cmd, capture_output=True, timeout=30)

    # 在容器内执行 C++ 客户端
    client_path = "/workspace/FunASR/runtime/websocket/build/bin/funasr-wss-client-2pass"
    cmd = [
        "docker", "exec", CONTAINER_NAME,
        client_path,
        "--server-ip", "127.0.0.1",
        "--port", str(CONTAINER_PORT),
        "--mode", "offline",
        "--is-ssl", "0",
        "--wav-path", container_wav,
    ]

    logger.info(f"调用 FunASR C++ 客户端...")
    result = subprocess.run(cmd, capture_output=True, text=False, timeout=timeout)

    if result.returncode != 0:
        err = result.stderr.decode("utf-8", errors="replace")[:200] if result.stderr else ""
        raise RuntimeError(f"FunASR 识别失败: {err}")

    # FunASR C++ 客户端将 JSON 结果输出到 stderr！stdout 是日志
    stderr = result.stderr.decode("utf-8", errors="replace")

    # 解析 stderr 中的 JSON 行（on_message 行包含 stamp_sents）
    segments = _parse_output(stderr)
    logger.info(f"FunASR 识别完成，共 {len(segments)} 个片段")
    return {"segments": segments, "language": "zh"}


def _parse_output(output: str) -> list:
    """
    解析 C++ 客户端的 stderr 输出，提取 JSON 中的 stamp_sents。
    stderr 中每行格式: I... on_message = {...JSON...}
    """
    segments = []
    for line in output.strip().split("\n"):
        line = line.strip()
        # 查找 "on_message = {" 之后的 JSON
        idx = line.find("on_message = ")
        if idx < 0:
            continue
        json_str = line[idx + len("on_message = "):]
        try:
            data = json.loads(json_str)
        except json.JSONDecodeError:
            continue

        stamp_sents = data.get("stamp_sents", [])
        if not stamp_sents:
            continue

        for sent in stamp_sents:
            text_seg = sent.get("text_seg", "").strip()
            if not text_seg:
                continue

            start_ms = sent.get("start")
            end_ms = sent.get("end")
            ts_list = sent.get("ts_list", [])

            if ts_list:
                starts = [t[0] for t in ts_list if len(t) >= 1]
                ends = [t[1] for t in ts_list if len(t) >= 2]
                if starts: start_ms = starts[0]
                if ends: end_ms = ends[-1]

            if start_ms is not None and end_ms is not None:
                punc = sent.get("punc", "")
                segments.append({
                    "text": text_seg + punc,
                    "start": round(start_ms / 1000.0, 3),
                    "end": round(end_ms / 1000.0, 3),
                })

    return segments


def test_connection() -> bool:
    """测试 FunASR Docker 服务和容器是否可用。"""
    try:
        result = subprocess.run(
            ["docker", "exec", CONTAINER_NAME, "ps", "aux"],
            capture_output=True, text=True, timeout=10
        )
        return "funasr-wss-server-2pass" in result.stdout
    except Exception:
        return False

