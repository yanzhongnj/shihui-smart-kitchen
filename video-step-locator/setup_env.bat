@echo off
chcp 65001 >nul
title 智能菜谱视频步骤定位系统 - 环境安装
color 0A

echo ============================================
echo  智能菜谱视频步骤定位系统 - 环境安装
echo ============================================
echo.

:: 检查 Python
where python >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [错误] 未找到 Python，请先安装 Python 3.10~3.12
    echo 下载地址: https://www.python.org/downloads/release/python-31210/
    pause
    exit /b 1
)

python --version
echo.

:: 获取当前目录
set PROJECT_DIR=%~dp0
cd /d "%PROJECT_DIR%"

:: 检查虚拟环境
if not exist ".venv\Scripts\python.exe" (
    echo [1/6] 创建虚拟环境...
    python -m venv .venv
    if %ERRORLEVEL% NEQ 0 (
        echo [错误] 虚拟环境创建失败
        pause
        exit /b 1
    )
    echo      虚拟环境已创建: .venv\
) else (
    echo [1/6] 虚拟环境已存在，跳过
)

:: 激活虚拟环境
echo [2/6] 激活虚拟环境...
call .venv\Scripts\activate.bat

:: 升级 pip
echo [3/6] 升级 pip 和基础工具...
python -m pip install --upgrade pip setuptools wheel -q
if %ERRORLEVEL% NEQ 0 (
    echo [警告] pip 升级失败，继续安装...
)

:: 设置 HuggingFace 镜像（中国大陆）
set HF_ENDPOINT=https://hf-mirror.com
echo [信息] 已设置 HuggingFace 镜像: %HF_ENDPOINT%
echo.

:: 安装核心依赖
echo [4/6] 安装核心依赖（whisperx, sentence-transformers, openai 等）...
pip install whisperx sentence-transformers openai ffmpeg-python imageio-ffmpeg -q
if %ERRORLEVEL% NEQ 0 (
    echo [警告] 部分依赖安装失败，尝试逐个安装...
    pip install whisperx -q
    pip install sentence-transformers -q
    pip install openai -q
    pip install ffmpeg-python imageio-ffmpeg -q
)
echo.

:: 安装 PyTorch（CPU 版）
echo [5/6] 安装 PyTorch（CPU 版）...
pip install torch==2.6.0+cpu torchvision==0.21.0+cpu torchaudio==2.6.0+cpu --index-url https://download.pytorch.org/whl/cpu -q
if %ERRORLEVEL% NEQ 0 (
    echo [警告] PyTorch 2.6.0 安装失败，尝试安装兼容版本...
    pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cpu -q
)
echo.

:: 安装测试依赖
echo [6/6] 安装测试依赖...
pip install pytest pytest-cov -q
echo.

:: 验证安装
echo ============================================
echo  验证安装...
echo ============================================
python -c "import whisperx; print('[OK] whisperx:', whisperx.__version__)" 2>nul || echo [??] whisperx: 版本未知
python -c "import openai; print('[OK] openai:', openai.__version__)" 2>nul || echo [??] openai: 版本未知
python -c "import torch; print('[OK] torch:', torch.__version__); print('[OK] CUDA可用:', torch.cuda.is_available())"
python -c "import torchvision; print('[OK] torchvision:', torchvision.__version__)" 2>nul || echo [??] torchvision: 版本未知
python -c "import sentence_transformers; print('[OK] sentence-transformers:', sentence_transformers.__version__)" 2>nul || echo [??] sentence-transformers: 版本未知
python -c "import imageio_ffmpeg; print('[OK] imageio-ffmpeg')"
echo.

:: 提示配置 LLM API
echo ============================================
echo  环境安装完成！
echo ============================================
echo.
echo 下一步：配置 LLM API 密钥
echo.
echo  【推荐】创建 .env 文件（自动加载）:
echo    copy .env.example .env
echo    然后编辑 .env 填入你的 API 密钥
echo.
echo  【或】使用环境变量:
echo    set LLM_API_KEY=sk-your-api-key
echo    set LLM_BASE_URL=https://api.deepseek.com/v1
echo    set LLM_MODEL=deepseek-chat
echo.
echo 然后运行：
echo   run_pipeline.bat     - 处理视频
echo   run_frontend.bat     - 启动前端
echo   run_all.bat          - 一键处理+前端
echo.

pause
