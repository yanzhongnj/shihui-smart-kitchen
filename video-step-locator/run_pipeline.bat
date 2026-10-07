@echo off
chcp 65001 >nul
title 智能菜谱视频步骤定位系统 - 后端处理流水线
color 0A

echo ============================================
echo  智能菜谱视频步骤定位系统
echo  后端处理流水线
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
    echo [错误] 虚拟环境不存在，请先运行 setup_env.bat
    pause
    exit /b 1
)

:: 激活虚拟环境
call .venv\Scripts\activate.bat

:: 设置 HuggingFace 镜像（中国大陆用户）
set HF_ENDPOINT=https://hf-mirror.com

:: ---- 参数配置（用户可修改） ----
:: LLM API 配置（如已设置环境变量则使用，否则使用默认值）
if "%LLM_API_KEY%"=="" (
    echo [警告] 未设置 LLM_API_KEY 环境变量
    echo        请在运行前先设置：
    echo        set LLM_API_KEY=sk-your-api-key
    echo.
    set /p LLM_API_KEY="请输入 LLM API 密钥 (或直接回车退出): "
    if "%LLM_API_KEY%"=="" exit /b 1
)
if "%LLM_BASE_URL%"=="" set LLM_BASE_URL=https://api.deepseek.com/v1
if "%LLM_MODEL%"=="" set LLM_MODEL=deepseek-v4-flash

:: 输入视频文件
set VIDEO_FILE=input\cooking.mp4
if not exist "%VIDEO_FILE%" (
    echo [信息] 默认视频不存在，请选择视频文件
    echo.
    set /p VIDEO_FILE="请输入视频文件路径 (直接回车退出): "
    if "%VIDEO_FILE%"=="" exit /b 1
)

:: 处理参数
:: 可选模型: tiny (快速) / base / large-v2 (高精度, 默认)
set WHISPER_MODEL=large-v2
set DEVICE=cpu
set OUTPUT_DIR=output
set OFFSET=0.2

:: 打印配置信息
echo ============================================
echo  配置信息
echo ============================================
echo  视频文件:     %VIDEO_FILE%
echo  输出目录:     %OUTPUT_DIR%
echo  Whisper模型:  %WHISPER_MODEL%
echo  计算设备:     %DEVICE%
echo  LLM模型:      %LLM_MODEL%
echo  LLM地址:      %LLM_BASE_URL%
echo  前置偏移:     %OFFSET% 秒
echo  HF镜像:       %HF_ENDPOINT%
echo ============================================
echo.
echo 开始处理，请稍候...
echo.

:: 运行管线
python scripts\pipeline.py ^
    --input "%VIDEO_FILE%" ^
    --output "%OUTPUT_DIR%" ^
    --whisper-model "%WHISPER_MODEL%" ^
    --device "%DEVICE%" ^
    --llm-api-key "%LLM_API_KEY%" ^
    --llm-base-url "%LLM_BASE_URL%" ^
    --llm-model "%LLM_MODEL%" ^
    --offset %OFFSET%

:: 检查结果
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [错误] 处理过程中出现错误，请检查日志
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo ============================================
echo  处理完成！
echo ============================================
echo.
echo 输出文件:
dir /b "%OUTPUT_DIR%\steps\*_steps.json" 2>nul
echo.
echo 可运行 run_frontend.bat 启动前端查看结果
echo.

pause
