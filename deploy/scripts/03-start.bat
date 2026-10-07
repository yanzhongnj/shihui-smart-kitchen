@echo off
chcp 65001 >nul
title 视频步骤定位系统 - 启动服务
echo ========================================
echo  视频步骤定位系统 - 启动服务
echo ========================================
echo.

REM ---- 路径计算 ----
set "SCRIPTS_DIR=%~dp0"
set "ROOT_DIR=%SCRIPTS_DIR%.."
set "BACKEND_DIR=%ROOT_DIR%\backend"
set "PIPELINE_DIR=%ROOT_DIR%\pipeline"
set "LOG_DIR=%ROOT_DIR%\logs"

if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

REM ---- 检查 JAR 是否存在 ----
if not exist "%BACKEND_DIR%\app.jar" (
    echo [错误] 未找到后端 JAR 文件！
    echo   请先运行 02-build.bat 构建系统
    pause
    exit /b 1
)

REM ---- 加载环境变量 ----
echo [1/3] 配置环境变量...

REM --- LLM API (必需) ---
if not defined LLM_API_KEY (
    echo [提示] 请输入 DeepSeek API Key（回车跳过）:
    set /p LLM_API_KEY=
)
if not defined LLM_API_KEY (
    echo [警告] LLM_API_KEY 未设置，流水线处理将失败
) else (
    echo [OK] LLM_API_KEY 已配置
)

REM --- 其他配置 ---
if not defined PYTHON_PATH set "PYTHON_PATH=%PIPELINE_DIR%\.venv\Scripts\python.exe"
if not defined VIDEO_STORAGE_PATH set "VIDEO_STORAGE_PATH=D:\nas\video-steps"
if not defined MYSQL_PASSWORD set "MYSQL_PASSWORD=root"

echo   PYTHON_PATH = %PYTHON_PATH%
echo   VIDEO_STORAGE_PATH = %VIDEO_STORAGE_PATH%
echo.

REM ---- 检查端口占用 ----
echo [2/3] 检查端口 8080...
netstat -ano | findstr ":8080 " | findstr "LISTENING" >nul
if %errorlevel% equ 0 (
    echo [警告] 端口 8080 已被占用！
    echo   正在尝试停止旧进程...
    for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8080 " ^| findstr "LISTENING"') do (
        taskkill /f /pid %%a >nul 2>&1
    )
    timeout /t 2 >nul
    echo   旧进程已停止
)
echo   端口 8080 可用

REM ---- 启动后端 ----
echo [3/3] 启动后端服务...

start "VideoStep" /B java -jar "%BACKEND_DIR%\app.jar" ^
    --spring.profiles.active=prod ^
    --video.processing.llm-api-key="%LLM_API_KEY%" ^
    --video.processing.python-path="%PYTHON_PATH%" ^
    --video.processing.pipeline-dir="%PIPELINE_DIR%" ^
    --video.storage.base-path="%VIDEO_STORAGE_PATH%" ^
    --spring.datasource.password="%MYSQL_PASSWORD%" ^
    > "%LOG_DIR%\backend.log" 2>&1

echo   后端启动中，请等待 10 秒...
timeout /t 10 >nul

findstr "Started" "%LOG_DIR%\backend.log" >nul
if %errorlevel% equ 0 (
    echo.
    echo ========================================
    echo  ✅ 启动成功！
    echo  访问地址: http://localhost:8080
    echo ========================================
    start http://localhost:8080
) else (
    echo [错误] 启动可能失败，请查看日志
    type "%LOG_DIR%\backend.log" | findstr "ERROR"
)
echo. & pause
