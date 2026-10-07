@echo off
chcp 65001 >nul
title 视频步骤定位系统 - 环境初始化
echo ========================================
echo  视频步骤定位系统 - 环境初始化
echo ========================================
echo.

REM ---- 路径计算 ----
set "SCRIPTS_DIR=%~dp0"
set "ROOT_DIR=%SCRIPTS_DIR%.."
set "PIPELINE_DIR=%ROOT_DIR%\pipeline"
echo 部署根目录: %ROOT_DIR%
echo Pipeline 目录: %PIPELINE_DIR%
echo.

REM ---- 1. Python 虚拟环境 ----
echo [1/4] 配置 Python 虚拟环境...
if not exist "%PIPELINE_DIR%\.venv" (
    python -m venv "%PIPELINE_DIR%\.venv"
    if %errorlevel% neq 0 (
        echo [错误] Python 虚拟环境创建失败，请确认 Python 3.10+ 已安装
        pause
        exit /b 1
    )
    echo   虚拟环境已创建
) else (
    echo   虚拟环境已存在，跳过
)

REM ---- 2. Python 依赖 ----
echo [2/4] 安装 Python 依赖...
call "%PIPELINE_DIR%\.venv\Scripts\activate.bat"
pip install -r "%PIPELINE_DIR%\requirements.txt" --quiet
if %errorlevel% neq 0 (
    echo [警告] 部分依赖安装可能失败，请检查输出
)
call deactivate
echo   依赖安装完成

REM ---- 3. MySQL 数据库 ----
echo [3/4] 检查 MySQL 数据库...
echo   请确保 MySQL 已启动，数据库 video_step_locator 已创建
echo   默认连接: localhost:3306, 用户名 root, 密码 root
echo   如需修改请在 ..\backend\application-prod.yml 中配置

REM ---- 4. 配置目录 ----
echo [4/4] 创建存储目录...
if not exist "%VIDEO_STORAGE_PATH%" (
    set VIDEO_STORAGE_PATH=D:\nas\video-steps
)
if not exist "D:\nas\video-steps" (
    mkdir D:\nas\video-steps
    mkdir D:\nas\video-steps\videos
    mkdir D:\nas\video-steps\audio
    mkdir D:\nas\video-steps\segments
    mkdir D:\nas\video-steps\steps
    mkdir D:\nas\video-steps\logs
    echo   存储目录已创建: D:\nas\video-steps
) else (
    echo   存储目录已存在
)

echo.
echo ========================================
echo  环境初始化完成！
echo  下一步：运行 02-build.bat 构建系统
echo ========================================
pause
