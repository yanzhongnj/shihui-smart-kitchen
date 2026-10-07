@echo off
chcp 65001 >nul
title 视频步骤定位系统 - 一键部署
echo ========================================
echo  视频步骤定位系统 - 一键部署
echo ========================================
echo.

REM ---- 切换到脚本所在目录 ----
set "SCRIPTS_DIR=%~dp0"
cd /d "%SCRIPTS_DIR%"

echo 步骤 1/3: 初始化环境...
call "scripts\01-setup-env.bat"
if %errorlevel% neq 0 (
    echo [错误] 环境初始化失败
    pause
    exit /b 1
)

echo.
echo 步骤 2/3: 构建系统...
call "scripts\02-build.bat"
if %errorlevel% neq 0 (
    echo [错误] 构建失败
    pause
    exit /b 1
)

echo.
echo 步骤 3/3: 启动服务...
call "scripts\03-start.bat"

echo.
echo ========================================
echo  一键部署完成！
echo ========================================
pause
