@echo off
set JAVA_HOME=C:\jdk17\jdk-17.0.2
set ANDROID_SDK_ROOT=C:\android-sdk
echo y| C:\android-sdk\cmdline-tools\latest\bin\sdkmanager.bat --sdk_root=C:\android-sdk "platforms;android-34" "build-tools;34.0.0" "platform-tools"
echo.
echo === SDK Install Done ===
