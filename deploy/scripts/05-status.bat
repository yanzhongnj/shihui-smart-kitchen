@echo off
chcp 65001 >nul
title 视频步骤定位系统 - 服务状态
echo ========================================
echo  视频步骤定位系统 - 服务状态
echo ========================================
echo.

netstat -ano | findstr ":8080 " | findstr "LISTENING" >nul
if %errorlevel% equ 0 (
    echo ✅ 后端服务运行中
    echo.
    echo  --- 连接信息 ---
    netstat -ano | findstr ":8080 " | findstr "LISTENING"
    echo.
    echo  访问地址: http://localhost:8080
) else (
    echo ❌ 后端服务未运行
)
echo.
echo  --- 日志文件 ---
if exist "%~dp0..\logs\backend.log" (
    echo  日志: %~dp0..\logs\backend.log
    echo  最新日志:
    powershell -Command "Get-Content '%~dp0..\logs\backend.log' -Tail 5"
) else (
    echo  无日志文件
)
echo.
pause
