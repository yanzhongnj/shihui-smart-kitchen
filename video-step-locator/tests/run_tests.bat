@echo off
chcp 65001 >nul
title 智能菜谱视频步骤定位系统 - 测试运行器
color 0D

echo ============================================
echo  智能菜谱视频步骤定位系统
echo  测试用例运行器
echo ============================================
echo.

:: 获取项目根目录
set PROJECT_DIR=%~dp0..
cd /d "%PROJECT_DIR%"

:: 检查虚拟环境
if not exist ".venv\Scripts\python.exe" (
    echo [错误] 虚拟环境不存在，请先运行 setup_env.bat
    pause
    exit /b 1
)

:: 激活虚拟环境
call .venv\Scripts\activate.bat

:: 设置 HuggingFace 镜像
set HF_ENDPOINT=https://hf-mirror.com

echo ============================================
echo  测试模式选择
echo ============================================
echo  1. 运行所有测试（含详细输出）
echo  2. 运行所有测试（简洁模式）
echo  3. 运行单元测试（不含集成测试）
echo  4. 运行特定模块测试
echo  5. 生成测试覆盖率报告
echo.
set /p TEST_MODE="请选择测试模式 (1/2/3/4/5, 默认1): "
if "%TEST_MODE%"=="" set TEST_MODE=1

echo.

if "%TEST_MODE%"=="1" (
    echo [运行] 所有测试（详细模式）
    echo.
    python -m pytest tests\ -v --tb=short
) else if "%TEST_MODE%"=="2" (
    echo [运行] 所有测试（简洁模式）
    echo.
    python -m pytest tests\ -q --tb=short
) else if "%TEST_MODE%"=="3" (
    echo [运行] 单元测试（跳过集成测试）
    echo.
    python -m pytest tests\ -v --tb=short -m "not integration"
) else if "%TEST_MODE%"=="4" (
    echo 可用测试模块：
    echo  1. test_extract_audio      - 音频提取
    echo  2. test_transcribe         - 语音识别
    echo  3. test_extract_steps      - 步骤抽取
    echo  4. test_match_timestamps   - 时间戳匹配
    echo  5. test_pipeline           - 管线集成
    echo.
    set /p MODULE="请选择模块编号 (1-5): "
    echo.
    if "%MODULE%"=="1" (
        python -m pytest tests\test_extract_audio.py -v --tb=short
    ) else if "%MODULE%"=="2" (
        python -m pytest tests\test_transcribe.py -v --tb=short
    ) else if "%MODULE%"=="3" (
        python -m pytest tests\test_extract_steps.py -v --tb=short
    ) else if "%MODULE%"=="4" (
        python -m pytest tests\test_match_timestamps.py -v --tb=short
    ) else if "%MODULE%"=="5" (
        python -m pytest tests\test_pipeline.py -v --tb=short
    ) else (
        echo [错误] 无效的选择
    )
) else if "%TEST_MODE%"=="5" (
    echo [运行] 测试覆盖率报告
    echo.
    python -m pytest tests\ -v --tb=short --cov=scripts --cov-report=term --cov-report=html:coverage_report
    echo.
    echo 覆盖率报告已生成: coverage_report\index.html
) else (
    echo [错误] 无效的选择
)

echo.
echo ============================================
echo  测试运行完毕！
echo ============================================

pause
