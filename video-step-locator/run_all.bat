@echo off
chcp 65001 >nul
title 智能菜谱视频步骤定位系统 - 一键运行
color 0A

echo ============================================
echo  智能菜谱视频步骤定位系统
echo  一键运行（处理视频 + 启动前端）
echo ============================================
echo.

:: 获取项目根目录
set PROJECT_DIR=%~dp0
cd /d "%PROJECT_DIR%"

:: 从 .env 文件加载 API 密钥（如果存在）
if exist ".env" (
    for /f "usebackq tokens=1,* delims==" %%a in (".env") do (
        if not "%%a"=="" if not "%%a"=="#" (
            if not defined %%a set %%a=%%b
        )
    )
    echo [信息] 已从 .env 文件加载配置
)

:: 检查虚拟环境
if not exist ".venv\Scripts\python.exe" (
    echo [步骤 0/4] 首次运行，正在安装环境...
    call setup_env.bat
    if %ERRORLEVEL% NEQ 0 (
        echo [错误] 环境安装失败
        pause
        exit /b 1
    )
)

:: 检查 LLM API 密钥
if "%LLM_API_KEY%"=="" (
    echo [步骤 1/4] 配置 LLM API...
    echo.
    echo 请选择 LLM 提供商：
    echo  1. DeepSeek（推荐，便宜且适合中文）
    echo  2. OpenAI（GPT 系列）
    echo.
    set /p LLM_CHOICE="请选择 (1/2, 默认1): "
    if "%LLM_CHOICE%"=="2" (
        set LLM_BASE_URL=https://api.openai.com/v1
        set LLM_MODEL=gpt-4o
    ) else (
        set LLM_BASE_URL=https://api.deepseek.com/v1
        set LLM_MODEL=deepseek-chat
    )
    echo.
    set /p LLM_API_KEY="请输入 API 密钥: "
    if "%LLM_API_KEY%"=="" (
        echo [错误] 必须提供 API 密钥
        pause
        exit /b 1
    )
)

:: 设置 HuggingFace 镜像
set HF_ENDPOINT=https://hf-mirror.com

:: 检查输入视频
echo [步骤 2/4] 检查输入视频...
if not exist "input\*.mp4" (
    echo [警告] input\ 目录下没有 mp4 视频文件
    echo 请将视频文件放入 input\ 目录
    echo.
    dir input\ /b 2>nul
    echo.
    set /p VIDEO_INPUT="或直接输入视频文件路径 (回车退出): "
    if "%VIDEO_INPUT%"=="" exit /b 1
) else (
    for %%f in (input\*.mp4) do set VIDEO_INPUT=%%f
    echo  找到视频: %VIDEO_INPUT%
)
echo.

:: 运行管线（显示详细输出）
echo [步骤 3/4] 运行视频处理管线...
echo.
echo  视频:     %VIDEO_INPUT%
echo  设备:     cpu
echo  模型:     large-v2
echo  LLM:      %LLM_MODEL%
echo.
echo 正在处理，请耐心等待...
echo.

call .venv\Scripts\activate.bat
python scripts\pipeline.py ^
    --input "%VIDEO_INPUT%" ^
    --output output ^
    --whisper-model large-v2 ^
    --device cpu ^
    --llm-api-key "%LLM_API_KEY%" ^
    --llm-base-url "%LLM_BASE_URL%" ^
    --llm-model "%LLM_MODEL%"

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [错误] 处理失败，请检查日志
    pause
    exit /b 1
)

:: 复制步骤数据到前端目录
echo.
echo [步骤 4/4] 准备前端数据...
if exist "output\steps\*_steps.json" (
    xcopy /Y /Q "output\steps\*_steps.json" "frontend\" >nul 2>&1
    echo  步骤数据已复制到 frontend\ 目录
)

:: 启动前端
echo.
echo ============================================
echo  处理完成！正在启动前端...
echo ============================================
echo.
echo 浏览器将打开: http://localhost:8080
echo 按 Ctrl+C 可停止服务器
echo.

cd frontend
python -m http.server 8080
cd ..

pause
