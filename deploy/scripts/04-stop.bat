@echo off
chcp 65001 >nul
title 视频步骤定位系统 - 停止服务
echo ========================================
echo  视频步骤定位系统 - 停止服务
echo ========================================
echo.

echo 正在停止后端服务...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8080 " ^| findstr "LISTENING"') do (
    taskkill /f /pid %%a >nul 2>&1
    echo   已停止进程 PID: %%a
)

echo.
echo ✅ 服务已停止
echo.
pause
