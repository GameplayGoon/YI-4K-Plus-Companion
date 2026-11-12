# Build the YI 4K+ Companion v0.10 project on Windows

1. Extract the entire ZIP into a fresh folder (do not open the ZIP itself in Android Studio).
2. Open the project in Android Studio; allow Gradle sync. Java 17 and Android SDK 35 are required.
3. Select Build → Build APK(s), or use `BUILD_APK_WINDOWS.bat`.
4. Find the debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

VersionCode is **11**, versionName **0.10.0-beta**, and application ID remains `org.yi4kplus.companion`. Updating an existing debug installation requires signing with the same debug key. A different key may require uninstall/reinstall; this can erase app-specific preferences, so keep any important logs first.

The default is a universal APK. Smaller, optional architecture-specific APKs can be produced with Gradle `-PyiAbiSplits=true` but are not necessary for compatibility.

This archive contains **source**, not a verified APK: full Android SDK compilation was not possible here.

The Gradle wrapper is now included. You can run `gradlew.bat :app:assembleDebug` without a global Gradle installation. Java 17 or newer is required; SDK35 and Build Tools35.0.0 are selected. Keep your existing signing key to update your installed app without uninstalling.
