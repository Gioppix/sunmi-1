# Device verification — 2026-09-29

Device: SUNMI V2, Android API 25; printer model `POS-V2`, service 4.4.1, firmware 1.02, paper width 384 dots.

## Passed

- Debug/release/test APK compilation and Android lint. Final build: [build output](tests/evidence/build.txt). Lint has zero errors and four non-blocking warnings: old-API cleartext attribute, battery-exemption policy for Play Store distributions (this app is sideloaded), intentional service-lifetime wake lock, and debug-text localization.
- Real-device, no-paper WebSocket suite: [99 response events](tests/evidence/no-paper.json).
- Optimized release on the real V2: [114 response events](tests/evidence/release-print.json). Included one compact receipt containing text/style, columns, a small bitmap, QR, barcode, raw newline, and feed. All seven printing commands returned success; transaction callback returned code 0, `Transaction print successful!`.
- Resending that receipt's ID returned its stored result with no new execution/progress.
- Both suites covered opaque IDs (including a 64-bit integer, object, null, and boolean), status/read methods, dry runs, 10 malformed-command cases, malformed JSON, FIFO jobs, duplicate-ID conflict, reconnect, final-result replay, and acknowledgments.
- Five device-side instrumentation tests passed: opaque IDs, SQLite restart recovery/acknowledgments, queue limit, callback semantics, and preflight/byte conversion. These used isolated databases and no paper.
- **23 automated host tests passed on the latest source**, using Android API 25 under Robolectric: [JUnit XML evidence](tests/evidence/host-tests). They cover dry-run isolation, whole-job preflight, physical completion versus acceptance, missing/failed callbacks, individual command errors, paper-out admission, Binder death, manual buffers, QR font isolation, cleanup failures, native return values, journal restart/ack/retention/backpressure, typed IDs, endpoint isolation, server-close reconnect, oversized/binary frames, and a 35-second real localhost connection through the actual `SocketLink`. No physical printer calls are made.
- Regression tests exposed and verified fixes for QR module size incorrectly altering text font size, and a remote cleanup exception overriding a confirmed print result. Graceful handling of newer WebSocket close codes was also added.
- Host OkHttp connection test passed after the final timeout correction: socket stayed open for 40 seconds, exchanged ping/pong and echoed a message beyond the HTTP client's 30-second deadline. This is a library-level check, not a substitute for a long device soak.
- The latest installed release passed its paper-free protocol suite: [current release evidence](tests/evidence/current-device-no-paper.json). Its installed APK hash matched the local APK below.
- After USB was restored on September 29, the latest controlled reboot's saved URL, interrupted-job `outcome_unknown`, queued status result, and duplicate suppression all passed: [latest recovery evidence](tests/evidence/latest-recovered-device.json). No manual app launch or additional reboot was needed. The foreground service was already running: [service evidence](tests/evidence/service-after-reconnect.txt). The earlier interrupted build also passed recovery: [earlier evidence](tests/evidence/recovered-device.json).
- Three minutes of screen-off operation passed with 18 ping/status pairs on one uninterrupted WebSocket connection and an unchanged paper counter: [screen-off evidence](tests/evidence/device-screen-off.json). Android independently reported `mWakefulness=Asleep` and display `OFF` during the test: [power-state evidence](tests/evidence/screen-off-power-state.txt). The screen was woken afterward.

## Limits and device findings

- The controlled reboot initially timed out waiting for USB ADB: [original partial evidence](tests/evidence/controlled-reboot.json). The boot ID subsequently changed from `c29bc20f-c3a3-4088-a819-6c8be517fc7e` to `dc9a7015-3648-46be-b2cc-8990e65ae2a4`. Recovery is now verified, but the boot receiver's specific startup log had rolled out by reconnection, so the exact startup trigger was not directly captured. This V2 uses block-encrypted storage; any startup unlock requirement precedes normal app startup.
- This firmware has both light and deep Doze disabled, with no motion sensor: [power configuration](tests/evidence/device-power-configuration.txt). The forced-Doze attempt remained `ACTIVE`, and its power-test settings were restored. It is not a passed Doze test. The app-specific battery exemption remains enabled.
- LAN debugging reached the V2 at `192.168.68.102:5555` through a localhost relay, but Android reported `unauthorized`. Its firmware could not launch `UsbDebuggingActivity`: [authorization errors](tests/evidence/wifi-debug-authorization.txt). Sunmi's native approval handler did not resolve it. The relay was stopped and the V2 restored to USB-only debugging; normal USB access works.
- Physical receipt layout/scannability has not been visually inspected; the evidence is printer callbacks and protocol responses.
- Extended unattended operation and physical paper-out/overheat faults have not been exercised.

The saved test endpoint is `ws://127.0.0.1:8765`. Temporary test servers have exited. No production endpoint was supplied.

## Repeat paper-free checks

```sh
export ANDROID_SERIAL=VB26205T00303
adb reverse tcp:8765 tcp:8765
/tmp/sunmi-test-venv/bin/python tools/idle_test.py --seconds 180
```

Use `--force-doze` only on a device with Doze enabled. Do not resume the completed lifecycle prefix again: its final results have now been acknowledged.

The power exemption applies only to this app and can also be requested through its **Always-on power** menu. The default server suite uses no paper; another receipt is unnecessary for the latest networking/lifecycle changes.

Final release APK SHA-256:

```text
ba39b9efe580ff0d2377c646ca571310d0a65acba274595da48868781140f9ae
```
