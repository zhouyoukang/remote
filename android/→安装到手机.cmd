@echo off
chcp 65001 >nul
echo ═══════════════════════════════════════
echo   亲情远程 · 安装APK到手机
echo ═══════════════════════════════════════
echo.

cd /d "%~dp0"

set APK=app\build\outputs\apk\debug\app-debug.apk

if not exist "%APK%" (
    echo [!] APK文件不存在: %APK%
    echo     请先运行: →初始化构建.cmd
    pause
    exit /b 1
)

echo [1/3] 检查ADB连接...
adb devices -l 2>nul | findstr "device" >nul
if %ERRORLEVEL% neq 0 (
    echo [!] 未检测到手机
    echo     请确保:
    echo     1. 手机已通过USB连接
    echo     2. 已开启USB调试
    echo     3. 手机上已授权此电脑
    pause
    exit /b 1
)

echo [2/3] 安装APK...
adb install -r "%APK%"

if %ERRORLEVEL% equ 0 (
    echo.
    echo [3/3] 启动APP...
    adb shell am start -n com.dao.remote/.MainActivity
    echo.
    echo ═══════════════════════════════════════
    echo   安装成功! APP已启动
    echo ═══════════════════════════════════════
) else (
    echo [!] 安装失败
)

pause
