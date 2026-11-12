@echo off
setlocal EnableExtensions
cd /d "%~dp0"
echo.
echo ====================================================
echo       YI 4K+ COMPANION - BUILD AN ANDROID APK
 echo ====================================================
echo.
if not defined JAVA_HOME (
  if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
)
if not defined ANDROID_HOME (
  if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
)
if not defined ANDROID_SDK_ROOT if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if not defined JAVA_HOME (
  echo ERROR: Could not find a JDK. Install Android Studio with its bundled JDK.
  goto :fail
)
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo ERROR: JAVA_HOME does not point at a Java installation: %JAVA_HOME%
  goto :fail
)
if not defined ANDROID_HOME (
  echo ERROR: Android SDK was not found at %%LOCALAPPDATA%%\Android\Sdk
  echo In Android Studio, go to Settings - Android SDK and install API 35.
  goto :fail
)
if not exist "gradlew.bat" goto :fail
echo.
echo Building with the verified Gradle wrapper / Java 17+ / Android SDK 35...
call "%~dp0gradlew.bat" --no-daemon :app:assembleDebug
if errorlevel 1 goto :fail
if not exist "app\build\outputs\apk\debug\app-debug.apk" goto :fail
echo.
echo SUCCESS! APK created:
echo %CD%\app\build\outputs\apk\debug\app-debug.apk
start "" "app\build\outputs\apk\debug"
pause
exit /b 0
:fail
echo.
echo BUILD FAILED. Copy the red error lines above and send them back.
pause
exit /b 1
