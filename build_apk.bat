@echo off
set JAVA_HOME=C:\jdk17\jdk-17.0.2
set ANDROID_SDK_ROOT=C:\android-sdk
set ANDROID_HOME=C:\android-sdk

echo === Building Teliki APK ===
cd /d "C:\teliki-build"
call gradlew.bat assembleDebug --no-daemon

echo.
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    echo === SUCCESS! ===
    echo APK: C:\teliki-build\app\build\outputs\apk\debug\app-debug.apk
    copy "app\build\outputs\apk\debug\app-debug.apk" "e:\kavabanga\teliki-signage.apk"
    echo Copied to: e:\kavabanga\teliki-signage.apk
) else (
    echo === BUILD FAILED ===
)
