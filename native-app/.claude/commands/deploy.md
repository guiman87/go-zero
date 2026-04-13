# Deploy Go Zero to Android Device

Build the debug APK and install it on the connected Android device.

## Steps

1. Build:
```bash
cd /home/guille/dev/ha/go-zero/native-app
JAVA_HOME=/home/guille/dev/ha/go-zero/android-studio/jbr ./gradlew assembleDebug
```

2. Install:
```bash
/home/guille/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

3. Optionally launch the app:
```bash
/home/guille/Android/Sdk/platform-tools/adb shell am start -n com.guiman87.gozero/.MainActivity
```

4. To stream logs from the device:
```bash
/home/guille/Android/Sdk/platform-tools/adb logcat -s TFLite WakeWordService STT HA
```

## Notes
- Requires JDK from Android Studio JBR, NOT system Java (system is Java 25 which is incompatible with AGP)
- `adb` is at `/home/guille/Android/Sdk/platform-tools/adb` (not in PATH by default)
- Device must be connected via USB with USB debugging enabled
- Use `adb devices` to verify the device is visible before installing
