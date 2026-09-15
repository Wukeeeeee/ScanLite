@echo off
rem ============================================================
rem  ScanLite 一键编译（Windows）
rem  用法：直接双击，或在 CMD 里执行  build.bat
rem  产物：仓库根目录 ScanLite-<版本号>.apk
rem  依赖：D:\jdk17  +  D:\AndroidSdk（路径写在 local.properties）
rem ============================================================
setlocal
cd /d "%~dp0"

if not defined JAVA_HOME set "JAVA_HOME=D:\jdk17"
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [错误] 找不到 JDK：%JAVA_HOME%
  echo        请把 build.bat 里的 JAVA_HOME 改成你本机 JDK 17 的路径。
  exit /b 1
)

call gradlew.bat testDebugUnitTest assembleRelease --no-daemon
if errorlevel 1 (
  echo.
  echo [失败] 编译或单元测试未通过，往上翻看第一条错误。
  exit /b 1
)

set "VN="
for /f "tokens=2 delims==" %%v in ('findstr /r /c:"versionName = " app\build.gradle.kts') do set "VN=%%v"
set "VN=%VN: =%"
set "VN=%VN:"=%"

copy /y "app\build\outputs\apk\release\app-release.apk" "ScanLite-%VN%.apk" >nul
if errorlevel 1 (
  echo [失败] 拷贝 APK 失败。
  exit /b 1
)

echo.
echo ============================================================
echo  编译成功： ScanLite-%VN%.apk
echo  安装到手机： adb install -r ScanLite-%VN%.apk
echo ============================================================
endlocal
