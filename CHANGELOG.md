# v0.10.0 beta — connection and transfer hardening

This update keeps the v0.9 layout and package ID. Version code is 11.

- Control requests have fixed overall deadlines; a timeout discards the session so a late reply cannot be mistaken for the next command. Photo-event acknowledgements remain supported.
- Reconnection and queued camera writes belong to a specific connection generation. Explicit disconnect cancels opening sockets immediately; uncertain capture commands are never automatically replayed.
- Wi-Fi routing uses the phone's actual subnet mask, preserves the previous process binding and can follow a replacement Wi-Fi network handle during a file retry.
- Rotating the phone retains the Activity and session. Camera/gallery/settings tab navigation pauses only the local decoder and preserves the remote viewfinder. The visible connection service uses a renewable, bounded CPU wake lease and tolerates unavailable optional Wi-Fi locks.
- Downloads use four bounded GET attempts. Partial data is resumed only with matching range headers and an entity validator; otherwise the whole file is safely restarted. Redirects and changed files are rejected.
- Files stage privately and remain pending in MediaStore until verified. Video size and actual camera recording state are checked before publication. Incomplete pending files cannot appear as saved media.
- Optional thumbnail and file-size traffic yields to downloads. Resized camera thumbnails are preferred. Gallery scans and VLC preparation have explicit time budgets.
- Diagnostic redaction covers quoted JSON credentials and URL serial values; logs and individual entries remain bounded.

Validation: complete Gradle debug build and Android lint, simulated TCP camera tests, local HTTP failure/retry tests, subnet/thumbnail/parser checks, APK signatures and 16 KiB native-library alignment checks. Physical phone/camera coverage is still required; this remains a beta.

---

# v0.9.0 beta — recording-aware media and visible connection notifications

- **Background notification permission:** Android 13+ now asks once whether the user wants YI's connected-device notification visible in the standard notification drawer. The service can still operate without permission; its entry may instead be under Active apps. A direct "Connection notification settings" shortcut is in the Settings tab. We do not repeatedly ask after a refusal.
- **Camera recording awareness:** The existing 3-second camera state checks drive recording status. The control socket also counts firmware `video_record_complete` events, which trigger fresh metadata queries. A remote MP4 can appear in HTTP's file listing before it is finalised; its first size is NOT cached as permanent when recording is active.
- **Gallery media sizes:** The visible video sizes are refreshed while recording, after recording stops, after `video_record_complete`, and periodically thereafter without redrawing all thumbnails. The recording badge and download button update automatically when the camera reports idle.
- **Avoid incomplete videos:** Remote video downloads are paused during camera recording. Before a video transfer, and again before publishing its Android MediaStore item, the app queries the camera's actual `app_status` through its serial control worker; it must be explicitly idle/viewfinder. While transferring, it monitors the local recording flag. The HTTP Content-Length of the initial response is compared with actual bytes written, and then rechecked through HTTP HEAD after transfer. If changed, or the camera cannot be verified idle, the pending phone copy is deleted. The Android media remains private until successful publish using `IS_PENDING`.
- **No arbitrary finish timer:** Camera state and file consistency checks are used, not an assumed delay. Limit: the firmware does not expose a documented per-file finalized flag. A size check cannot prove the SD-card write has finished if the camera does not report it truthfully; when in doubt the app refuses the copy rather than claiming completion.
- Retains v0.8's socket auto-reconnect, five-second foreground Gallery sync, camera mode controls, universal LibVLC APK defaults and read-only camera-side media access.

Build status: Java parser and standalone JSON/HTML protocol tests passed here. Full Android SDK compilation and a physical-camera test were not available; this is source beta, not a compiled APK.
