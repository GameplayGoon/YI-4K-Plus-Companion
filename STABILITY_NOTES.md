# v0.10 stability changes and validation

## Connection

- Fixed monotonic overall command deadlines (10 seconds normally, 20 seconds for photo capture), bounded JSON framing and immediate cancellation of blocked reads/writes.
- Explicit camera rejection keeps a healthy socket. Ambiguous transport failure closes it; no recording/photo/mode mutation is automatically replayed.
- Generation-guarded reconnect, late UI callbacks, settings changes and queued capture commands. Backoff is bounded and Wi-Fi availability wakes a pending reconnect.
- Actual IPv4 prefix matching supports /16, /20, /23, /24 and /32 without assuming every hotspot is /24.
- The prior process network binding is restored on explicit disconnect. Existing transfer attempts can obtain the replacement camera Wi-Fi handle.
- Rotation keeps the existing Activity/session. Tab navigation pauses only the local decoder and preserves the camera viewfinder, as minimizing the app does. Background preview stops while the visible connection service maintains control and transfer wake protection.

## Transfers and recording safety

- Up to four GET attempts, with 1.5/3/4-second retry delays for immediately failing requests and bounded socket waits.
- Resume requires a valid matching Content-Range, consistent total length and an ETag or Last-Modified validator. Missing validators cause a full restart. A server ignoring Range never causes a duplicate prefix to be appended.
- HTML/error responses, unexpected encoding, redirects, truncation, overlong content and changed sources are not published.
- Original data stages in private cache, then copies to a pending MediaStore entry. Publication requires success from MediaStore, verified size, cancellation checks and—for video—a fresh read-only camera state check. Pending/incomplete rows cannot appear as finished downloads.
- A video verification can wait up to 30 seconds for the control connection to recover. It never starts/stops recording. Optional viewfinder writes check the camera's actual state first and preserve unrecognized/recording states.
- User disconnect, address change and Activity destruction cancel active transfers and invalidate queued transfers. An automatic control reconnect does not cancel a valid transfer intent.
- Metadata/thumbnail HTTP yields to active downloads, and only one optional thumbnail/metadata worker is used for each category.

## Automated validation

- 12 production TCP transport test groups: fragmented UTF-8 and concatenated JSON, negotiated tokens, explicit rejection, deadlines despite event/trickle streams, EOF/malformed data, concurrent requests/close, event-only photo completion, and cancelled connect intents.
- 16 real local HTTP integration scenarios: validated resume, ignored Range, changed validators/totals, bad offsets, no-validator restart, permanent/transient HTTP errors, redirects, misleading HTML, unknown length, cancellation during stalled reads, camera-state cancellation and late failures.
- 15 IPv4 subnet cases plus thumbnail URL, HTML/JSON helper and diagnostic redaction checks.
- Full Android build, Android lint and APK/native alignment validation are recorded in `VALIDATION.md` after packaging.

## Practical limits

No physical camera or phone is attached to this workspace. The uploaded v0.9 build's working behavior is the baseline; the modifications need checking on real phones and the user's camera firmware. Android 10/API29+ is the supported build range; an automated build is not a claim that every OEM/Android version has been tested.

Private staging temporarily uses roughly twice a clip's size on phone storage. Downloads do not resume after process termination. Android/OEM power management can still affect a Wi-Fi network without internet; keep the camera network selected and use the existing diagnostic export if a problem occurs.

The included test APK has a new signing key. Rebuild with the original key for an in-place update. The source contains no signing key, SDK cache, input ZIP or personal diagnostic log.
