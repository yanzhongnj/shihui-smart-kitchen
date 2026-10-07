@echo off
chcp 65001 >nul
title 视频步骤定位系统 - 构建打包
echo ========================================
echo  视频步骤定位系统 - 构建打包
echo ========================================
echo.

REM ---- 路径计算 ----
set "SCRIPTS_DIR=%~dp0"
set "ROOT_DIR=%SCRIPTS_DIR%.."
set "DEPLOY_BACKEND=%ROOT_DIR%\backend"
set "PROJECT_ROOT=%ROOT_DIR%\.."
set "BACKEND_SRC=%PROJECT_ROOT%\video-step-locator-backend"
set "WEB_DIR=%PROJECT_ROOT%\video-step-locator-web"

echo 部署根目录: %ROOT_DIR%
echo 后端源码: %BACKEND_SRC%
echo 前端源码: %WEB_DIR%
echo.

REM ---- 检查源码目录 ----
if not exist "%BACKEND_SRC%\pom.xml" (
    echo [错误] 未找到后端源码目录: %BACKEND_SRC%
    echo   请确认项目结构正确
    pause
    exit /b 1
)
if not exist "%WEB_DIR%\package.json" (
    echo [错误] 未找到前端源码目录: %WEB_DIR%
    echo   请确认项目结构正确
    pause
    exit /b 1
)

REM ---- 停止旧进程 ----
echo [0/3] 停止旧进程...
netstat -ano | findstr ":8080 " | findstr "LISTENING" >nul
if %errorlevel% equ 0 (
    echo   发现运行中的后端进程，正在停止...
    for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8080 " ^| findstr "LISTENING"') do (
        taskkill /f /pid %%a >nul 2>&1
    )
    timeout /t 2 >nul
    echo   旧进程已停止
) else (
    echo   无运行中的后端进程
)

REM ---- 1. 构建前端 ----
echo [1/3] 构建前端...
cd /d "%WEB_DIR%"
call npm install --silent
call npx vite build
if %errorlevel% neq 0 (
    echo [错误] 前端构建失败
    pause
    exit /b 1
)
echo   前端构建成功

REM ---- 2. 复制前端到后端静态资源 ----
echo [2/3] 复制前端文件到后端...
if not exist "%BACKEND_SRC%\src\main\resources\static" mkdir "%BACKEND_SRC%\src\main\resources\static"
xcopy /E /Y /Q "%WEB_DIR%\dist\*" "%BACKEND_SRC%\src\main\resources\static\"
echo   前端文件已复制到后端

REM ---- 3. 构建后端 ----
echo [3/3] 构建后端 JAR...
cd /d "%BACKEND_SRC%"
call mvn clean package -DskipTests -q
if %errorlevel% neq 0 (
    echo [错误] 后端构建失败
    pause
    exit /b 1
)
echo   后端构建成功

REM ---- 复制 JAR 到部署目录 ----
copy /Y "%BACKEND_SRC%\target\video-step-locator-backend-1.0.0.jar" "%DEPLOY_BACKEND%\app.jar" >nul
echo   JAR 已复制到 %DEPLOY_BACKEND%\app.jar

REM ---- 复制配置文件 ----
copy /Y "%BACKEND_SRC%\src\main\resources\application.yml" "%DEPLOY_BACKEND%\application.yml" >nul
echo   配置文件已复制

echo.
echo ========================================
echo  构建完成！
echo  运行 03-start.bat 启动系统
echo ========================================
pause
