# Networking choices for v0.8–v0.9

## Verified Android guidance

- Android foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types  — `connectedDevice` is appropriate to continued communication with an external Wi-Fi device. Android 14+ requires its matching permission and one qualifying hardware/network permission.
- Restrictions on launching services from the background: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start — start only following the user's connection action while the app is visible.
- Wi-Fi locks: https://developer.android.com/reference/android/net/wifi/WifiManager.WifiLock — can keep radio awake, not guarantee that Android will remain joined to a non-internet Wi-Fi network. Release on explicit Disconnect.
- Wake-lock recommendations: https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/identify-wls — no unbounded CPU wake lock used.

## How this build applies them

- A visible connected-device foreground service is started after a successful camera handshake; a notification lets the user return to the app. It stops when the user explicitly disconnects or the activity is destroyed.
- Keep the RTSP video player off while minimised. Do not stop the camera's viewfinder just because the app leaves the foreground.
- Keep the JSON TCP control connection active with a lightweight eight-second heartbeat. Monitor camera Wi-Fi network changes; when lost, use a fresh Yi handshake instead of reusing a broken socket. Reconnect with bounded backoff, up to once a minute.
- HTTP gallery browsing is separate from the JSON control channel. A failed HTTP service is NOT by itself proof that the control channel has died; it does not cause a reset. Avoid old experimental binary-prone file-list protocol commands.
- Check the gallery every five seconds only while its tab is visible; avoid polling during download queues. Do not run these checks while the screen is locked.

## Limits

Physical phone testing on multiple vendors remains required. Android may remove or replace a Wi-Fi connection that has no internet, user may switch networks, and Doze / OEM restrictions may suspend background work despite the service. No code can guarantee uninterrupted connectivity on every Android version. This v0.8 implementation keeps the camera socket in the Activity; if Android destroys the Activity, reconnect when the app is recreated. Migrating camera ownership into the Service is a possible later architectural improvement if device testing shows process lifecycle issues.

## v0.9: ongoing notification visibility

Android 13+ requires the user-granted POST_NOTIFICATIONS runtime permission to show ordinary foreground-service notifications in the notification drawer. It is NOT required to start the foreground service. Without permission, Android may instead surface it in its Active apps/Task Manager interface. See https://developer.android.com/develop/ui/views/notifications/notification-permission and https://developer.android.com/develop/background-work/services/fgs/service-types.

## v0.9: recording file consistency

Camera `app_status` (control socket) is checked before and after a video download. The HTTP file size is checked before publication. The Android MediaStore `IS_PENDING` flag prevents partially copied files appearing in other gallery apps. The firmware does not expose a verified file-finalisation flag, so these are safeguards rather than an absolute filesystem guarantee. See https://developer.android.com/training/data-storage/shared/media.
