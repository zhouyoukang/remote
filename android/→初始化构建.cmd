@echo off
chcp 65001 >nul
echo ═══════════════════════════════════════
echo   亲情远程 · 构建初始化
echo ═══════════════════════════════════════
echo.

cd /d "%~dp0"

REM 检查JAVA_HOME
if not defined JAVA_HOME (
    echo [!] JAVA_HOME 未设置
    echo     请安装 JDK 17+ 并设置 JAVA_HOME
    echo     推荐: Android Studio 内置 JBR
    pause
    exit /b 1
)

echo [1/3] 下载 Gradle Wrapper...
if not exist "gradle\wrapper" mkdir "gradle\wrapper"

REM 下载 gradle-wrapper.jar
powershell -Command "& {[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.5.0/gradle/wrapper/gradle-wrapper.jar' -OutFile 'gradle\wrapper\gradle-wrapper.jar'}" 2>nul

if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo [!] 下载 gradle-wrapper.jar 失败
    echo     请手动运行: gradle wrapper --gradle-version 8.5
    pause
    exit /b 1
)

echo [2/3] Gradle Wrapper 就绪

echo [3/3] 构建 Debug APK...
call gradlew.bat assembleDebug

if %ERRORLEVEL% equ 0 (
    echo.
    echo ═══════════════════════════════════════
    echo   构建成功!
    echo   APK: app\build\outputs\apk\debug\app-debug.apk
    echo ═══════════════════════════════════════
) else (
    echo.
    echo [!] 构建失败, 请检查错误信息
)

pause
