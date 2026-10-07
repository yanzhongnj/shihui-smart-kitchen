@echo off
chcp 65001 >nul
title 下载 Whisper large-v2 模型
color 0E

echo ============================================
echo  Whisper large-v2 模型
echo ============================================
echo.
echo  当前模型缓存状态：
echo    large-v2: 1.5B 参数 ✅ 已下载（当前默认）
echo    tiny:     39M 参数 ✅ 可用
echo    base:     74M 参数 ⚠️ 文件不完整
echo.
echo  如需重新下载 large-v2 或切换下载源，可使用此脚本
echo.

set PROJECT_DIR=%~dp0
cd /d "%PROJECT_DIR%"

:: 检查虚拟环境
if not exist ".venv\Scripts\python.exe" (
    echo [错误] 虚拟环境不存在，请先运行 setup_env.bat
    pause
    exit /b 1
)

call .venv\Scripts\activate.bat

:: 方式选择
echo 选择下载镜像源：
echo  1. hf-mirror.com（推荐中国大陆用户）
echo  2. huggingface.co（直连，需代理/VPN）
echo  3. 手动下载（自行准备 model.bin 文件）
echo.
set /p MIRROR_CHOICE="请选择 (1/2/3, 默认1): "
if "%MIRROR_CHOICE%"=="" set MIRROR_CHOICE=1

if "%MIRROR_CHOICE%"=="1" (
    set HF_ENDPOINT=https://hf-mirror.com
    echo.
    echo 使用镜像: hf-mirror.com
) else if "%MIRROR_CHOICE%"=="2" (
    set HF_ENDPOINT=https://huggingface.co
    echo.
    echo 使用直连: huggingface.co
) else (
    echo.
    echo 请手动下载 model.bin 文件放到以下目录：
    echo   %%USERPROFILE%%\\.cache\\huggingface\\hub\\models--Systran--faster-whisper-large-v2\\blobs\\
    echo.
    pause
    exit /b 0
)

echo.
echo 开始下载（这可能需要 10-60 分钟，取决于网络速度）...
echo.
echo 下载完成后，使用 pipeline 时指定 --whisper-model large-v2 即可使用
echo.

set HF_ENDPOINT=%HF_ENDPOINT%

python -c "
from huggingface_hub import snapshot_download
import sys

model_id = 'Systran/faster-whisper-large-v2'
print(f'正在下载 {model_id}...')
sys.stdout.flush()
path = snapshot_download(
    repo_id=model_id,
    local_files_only=False,
    ignore_patterns=['*.h5', '*.ot', '*.msgpack', '*.pdf', '*.md']
)
print(f'下载完成！')
print(f'路径: {path}')
"

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [错误] 下载失败，可能是网络不通
    echo 请尝试使用代理或 VPN 后重试
) else (
    echo.
    echo [成功] large-v2 模型下载完成！
    echo 现在可以运行：run_pipeline.bat 或 run_all.bat
    echo 或者在命令行指定：--whisper-model large-v2
)

pause
