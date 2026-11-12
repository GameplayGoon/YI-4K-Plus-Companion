# v0.10.0-beta validation — 12 November 2026

- Full Gradle `:app:assembleDebug :app:lintDebug`: passed. Android lint reported 0 errors and 42 warnings (including existing UI, compatibility and dependency recommendations).
- 12 production TCP transport regression groups: passed.
- 16 local HTTP transfer integration scenarios: passed.
- 15 subnet-routing checks: passed.
- Thumbnail URL, JSON/HTML helper and diagnostic redaction checks: passed.
- APK signature verification and 16 KiB ZIP alignment: passed.
- All ARM64/x86_64 native PT_LOAD alignments are 16 KiB compatible.
- Universal APK includes ARM32, ARM64, x86 and x86_64. Minimum API29, target API34, compile API35.

APK size: 210,620,462 bytes.

APK SHA-256: `f264b73e843ac99c3fbdce26a47b62baab2c5ad3b73ac421b15de983b3dc21c4`

No physical camera or phone is attached to the workspace. Actual cross-phone radio behavior, OEM power management, live RTSP and camera firmware interactions still require hardware testing. No emulator/UI or physical-device pass is claimed.

The debug test certificate is generated for this workspace. The uploaded source contains no key, so this APK may have a different signature from the installed v0.9 app. Use the original signing key locally for a normal in-place update; preserve the existing installation and its settings if signatures differ.

Private signing material, SDKs and local build caches are excluded from the source archive.
