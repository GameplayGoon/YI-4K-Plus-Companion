# YI 4K+ Companion

**Unofficial Android camera companion — v0.10.0-beta**

YI 4K+ Companion connects an Android phone directly to a YI 4K+ action camera over the camera's Wi-Fi. It provides camera controls, live preview and access to photos and videos without requiring a cloud account.

## Experimental software: use at your own risk

**This application was developed with substantial assistance from AI tools and hands-on testing by one independent developer using one smartphone. It has not undergone heavy testing, a professional security audit or broad testing across phone brands, Android versions and camera firmware versions.**

AI-assisted code can contain mistakes. Working on the developer's phone does not guarantee that the app will work reliably on yours. Automated checks help find problems but do not replace testing with a real camera and phone.

**Use this application at your own risk.** Bugs may interrupt recording, disconnect the camera, display incorrect status or cause a transfer to fail. Do not rely on this beta as your only way to capture or preserve important footage. Check recordings on the camera, verify downloaded files play completely and keep backups of important media.

The software is provided without a guarantee of reliability, compatibility or fitness for a particular purpose. See `LICENSE` for the applicable license terms and warranty disclaimer.

## What the app does

- Connects to the camera's local Wi-Fi control service.
- Starts and stops video recording and takes photos.
- Displays battery information, camera status and available storage information.
- Offers supported camera modes and settings.
- Shows a live viewfinder using LibVLC.
- Browses photos and videos on the camera and downloads originals to the phone.
- Opens saved media and provides options to delete phone copies; camera originals are not deleted by those phone-copy actions.
- Maintains a visible connection service while connected and attempts to reconnect after interruptions.
- Records a bounded local diagnostic log that the user can export for troubleshooting.

The app is independent of YI Technology and is not an official or endorsed YI application.

## Compatibility

The project is intended for the **YI 4K+**. Other camera models and firmware versions are not guaranteed to work.

| Item | Current configuration |
| --- | --- |
| Minimum Android version | Android 10 / API 29 |
| Compile SDK | Android API 35 |
| Target SDK | Android API 34 |
| Build Tools | 35.0.0 |
| Java source compatibility | Java 17 |
| Gradle wrapper | 8.9 |
| Android Gradle Plugin | 8.6.1 |
| Application ID | `org.yi4kplus.companion` |
| Version | `0.10.0-beta`, version code `11` |

The universal APK includes ARM64, ARM32, x86 and x86_64 libraries. Architecture support does not mean that every device using that architecture has been tested. Phone power management, VPN configuration, Wi-Fi behavior and camera firmware can affect operation.

## Connect and use

1. Turn on the camera and enable its Wi-Fi.
2. Join the camera's network in Android's Wi-Fi settings.
3. If Android reports that the network has no internet, choose to stay connected.
4. Open the app and tap **Connect camera**. The default camera address is `192.168.42.1`.
5. Try a short recording and a small download before relying on a longer session.

The app does not require internet to control the camera. First-time builds do require internet to download development dependencies.

Closing or disconnecting the app does not send a Stop recording command. Use the app's Stop control or the camera's physical shutter button, and verify the actual camera state.

Downloads use private temporary storage before publishing a verified phone copy. Allow roughly twice the clip's size in free phone storage. Retry/resume is supported within the current transfer when the camera provides suitable headers; downloads do not persist across app/process termination.

## Compile with Android Studio

1. Extract the entire source ZIP into a normal folder.
2. Install Android Studio and open the project root containing `settings.gradle`.
3. Use **JDK 17** or **JDK 21** for Gradle. JDK 21 was used for the build checks for this version.
4. In SDK Manager, install **Android SDK Platform 35** and **Android SDK Build-Tools 35.0.0**. Accept the SDK licenses.
5. Allow Gradle sync to download the required dependencies.
6. Use Android Studio's **Build APK(s)** action, or run a command below in the project directory.

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Windows

In PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

Alternatively, run `BUILD_APK_WINDOWS.bat` after installing Android Studio and its SDK/JDK.

### Linux or macOS

```sh
chmod +x gradlew
./gradlew :app:assembleDebug
```

Use `ANDROID_HOME`, Android Studio's generated `local.properties`, or your normal SDK configuration to point Gradle to the installed Android SDK. Do not commit machine-specific paths or signing keys.

### Optional smaller APKs

The default APK is universal and comparatively large because it contains LibVLC for several CPU architectures. To generate architecture-specific APKs as well:

```sh
./gradlew :app:assembleDebug -PyiAbiSplits=true
```

On Windows, use `gradlew.bat` instead of `./gradlew`. Select the APK matching the phone's CPU; `arm64-v8a` is appropriate for the Galaxy S24 Ultra and most current Android phones.

## Updating an existing installation

**An in-place update must use the same signing key as the installed app.** Keeping the package name and increasing the version number is not sufficient if the key changes.

Rebuild on the original development machine with its existing debug key, or configure the original release key locally. The separately supplied test APK uses a workspace-generated test key and may not match your installed version.

If Android reports a signature mismatch, do not uninstall an installation containing important settings simply to force the update. Preserve your data and resolve signing first. No private signing key is included in this source archive.

Debug APKs are for testing. A production distribution needs an appropriate release-signing configuration, review of current distribution/target-SDK requirements and substantially more real-device testing.

## Checks and testing limits

For v0.10, the Android debug build and lint checks passed. Additional automated checks covered command timeouts and cancellation, fragmented camera responses, HTTP retry/resume, truncated transfers, subnet matching and diagnostic redaction. See `VALIDATION.md` and `STABILITY_NOTES.md` for details.

These results do **not** mean the application is thoroughly tested or release-certified. Hands-on development testing has been limited to one developer and one smartphone. The automated work performed in this workspace did not have a physical camera or phone attached.

Before wider use, test minimizing/restoring the app, tab changes, screen lock, rotation, weak Wi-Fi, interrupted downloads, large files, limited storage and recording started with the camera's own buttons. OEM background restrictions may still interrupt a connection.

## Troubleshooting and logs

- Keep the phone connected to the camera Wi-Fi even when it has no internet.
- Close other applications that may already be controlling the camera.
- After a timeout, check the camera's actual recording state before repeating a capture action.
- Export the diagnostic report through the app's diagnostic screen when reporting a problem.

Include the app version, phone model, Android version, camera firmware version, what you were doing and the exact error message. Diagnostic reports may contain phone details, the camera address and filenames. Sensitive fields are redacted on a best-effort basis; review a report before sharing it. Logs are stored locally and are not automatically uploaded.

## Main dependencies

- LibVLC for Android `3.6.5` — live camera preview.
- AndroidX Media3 ExoPlayer/UI `1.5.1` — media playback.
- Android platform networking and MediaStore APIs — camera communication and phone storage.

Dependencies have their own licenses. See the Gradle files for the exact configuration, `CHANGELOG.md` for changes and `LICENSE` for this project's license.
