@echo off
chcp 65001 >nul
title 智能菜谱视频步骤定位系统 - 前端界面
color 0B

echo ============================================
echo  智能菜谱视频步骤定位系统
echo  前端界面启动
echo ============================================
echo.

:: 获取项目根目录
set PROJECT_DIR=%~dp0
cd /d "%PROJECT_DIR%"

:: 检查前端文件
if not exist "frontend\index.html" (
    echo [错误] 前端文件不存在: frontend\index.html
    pause
    exit /b 1
)

:: 复制步骤数据到前端目录（如果有最新结果）
if exist "output\steps\*_steps.json" (
    echo [信息] 检测到步骤数据，正在复制到前端目录...
    xcopy /Y /Q "output\steps\*_steps.json" "frontend\" >nul 2>&1
)

echo ============================================
echo  启动方式选择
echo ============================================
echo  1. Python HTTP 服务器（推荐，无需额外安装）
echo  2. Node.js http-server（如果已安装）
echo  3. 直接打开 HTML 文件（不支持本地文件加载）
echo.
set /p CHOICE="请选择启动方式 (1/2/3, 默认1): "
if "%CHOICE%"=="" set CHOICE=1

echo.
echo 启动前端服务器...
echo.

if "%CHOICE%"=="1" (
    :: 方式一：Python HTTP 服务器
    echo 在浏览器中打开: http://localhost:8080
    echo 按 Ctrl+C 停止服务器
    echo.
    cd frontend
    python -m http.server 8080
    cd ..
) else if "%CHOICE%"=="2" (
    :: 方式二：Node.js http-server
    where http-server >nul 2>nul
    if %ERRORLEVEL% NEQ 0 (
        echo [错误] 未找到 http-server，请先安装: npm install -g http-server
        pause
        exit /b 1
    )
    echo 在浏览器中打开: http://localhost:8080
    echo 按 Ctrl+C 停止服务器
    echo.
    cd frontend
    http-server -p 8080 --cors
    cd ..
) else (
    :: 方式三：提示用户
    echo.
    echo [注意] 直接打开 HTML 文件可能无法加载本地视频文件
    echo 请将前端文件部署到 Web 服务器后访问
    echo.
    echo 前端文件路径: %PROJECT_DIR%frontend\
    echo.
    start "" "%PROJECT_DIR%frontend\index.html"
)

pause
