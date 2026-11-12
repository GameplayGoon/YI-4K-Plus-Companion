# v0.10 real-device follow-up

Automated tests are described in STABILITY_NOTES.md and VALIDATION.md. Check these scenarios with the actual camera before relying on the update:

- Connect, rotate the phone, lock/unlock it, then verify battery and actual camera recording status remain synchronized.
- Interrupt camera Wi-Fi briefly during a download, rejoin the camera network, and confirm the saved original has the correct size and plays fully.
- Use Disconnect during connection setup and during a large transfer; no later reconnect or capture should occur until explicitly requested.
- Start recording with the camera's physical shutter during a video download. The pending copy must not appear as a finished video.
- Try multiple-file queues and limited phone storage. Failed/cancelled transfers must not receive a saved badge.
- Export the diagnostic log after any failure. It should identify request/retry timing without exposing session tokens, passwords or serial numbers.

The code supports Android10/API29 and newer. Minimum-API compilation and lint are checked, but the author has not tested a physical device matrix in this workspace.

---

# v0.9 testing checklist

1. On Android 13+, connect and choose Allow when asked to show the connection notification. Minimise the app and check the notification drawer / Silent section. If it still does not appear, Settings → Connection → Connection notification settings, and check Android's notification controls. Android may list an unpermitted foreground service under Quick Settings → Active apps instead.
2. Start recording using the *physical camera*, switch to Gallery, and confirm a new MP4 can appear but its size is labelled not final and its download button is disabled. After the camera stops, ensure the button re-enables without a manual refresh.
3. Leave the Gallery visible during a recording; confirm sizes visibly update as file grows, and refresh again on completion without removing thumbnails. Check the debug log for `video_record_complete` and the metadata refresh.
4. Download the completed video. Verify the saved file appears in the phone's Movies/YI4KPlus and its duration is readable in the gallery. If the remote file grows during transfer, the app should discard the incomplete phone copy, not publish it.
5. Bulk-select photos and videos during active camera recording: photos may download, but remote videos must wait until the camera reports idle.
6. Minimise/reopen while connected; camera controls and preview should recover as in v0.8. Notifications are opt-in and the Wi-Fi must remain connected.
7. Export a debug log if the recording status is wrong or a completed video stays blocked. The YI firmware has no documented per-file finalize API, so don't attempt copying an apparently active recording through other tools.

Not tested against the physical camera or a full Android SDK build in this environment.
