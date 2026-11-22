# WS Printer for Sunmi V2

A small native Java app: one foreground service, one serialized printer worker, OkHttp WebSocket, SQLite job journal, and a debug screen. It starts on boot, reconnects with a 1–30 second backoff and jitter, and keeps the chosen URL across app restarts, updates, and reboots. The screen has no printing controls; its overflow menu changes the WebSocket URL.

## Build and install

Use JDK 17 and Android SDK 34. Set `sdk.dir` in `local.properties` for your machine.

```sh
./gradlew assembleRelease lintDebug
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n app.wsprinter/.MainActivity
```

Open the app once after installation and enter the endpoint. Android requires this initial launch before boot broadcasts can start a newly installed app. `wss://` uses normal certificate validation. `ws://` is supported for localhost or trusted networks. Credentials can be included in the URL; debug logs redact its path, query, and user information. Configuration is private app storage and backup is disabled.

The release APK is optimized and signed with this machine's **debug key** for direct device installation. Preserve the signing key for future updates; configure a private release keystore before distributing beyond your own devices. This is a dedicated Sunmi deployment targeting Android 7.1 behavior, not a Play Store project or a claim of compatibility with every Android version.

For battery operation, use **Always-on power** in the overflow menu and approve Android’s battery optimization exemption. The debug screen reports whether it is enabled. Android can suspend WebSocket networking during Doze without this exemption ([Android documentation](https://developer.android.com/training/monitoring-device-state/doze-standby)). On a provisioned USB device the equivalent is `adb shell dumpsys deviceidle whitelist +app.wsprinter`.

The service holds CPU/Wi-Fi wake locks while running so it can receive jobs with the screen off. Keep the terminal powered for always-on use. Android force-stop intentionally prevents automatic restart until the app is opened again. OEM background/autostart controls can also override normal service behavior.

## Wire protocol, version 1

Each WebSocket text frame contains one JSON object. The app connects as the **client**; your server sends jobs. On each connection the app sends:

```json
{"type":"hello","protocol":1,"version":"1.0","model":"V2","max_pending":64,"result_ack":true}
```

A minimal print job:

```json
{
  "id": "your-opaque-id",
  "commands": [
    {"op":"text","text":"Hello\n"},
    {"op":"feed","lines":1}
  ]
}
```

IDs are echoed without interpreting them. Strings are recommended; JSON null, booleans, arrays, objects, and numbers representable by Android's JSON parser are also supported (including signed 64-bit integers). For IDs longer than 64 bits use strings to avoid numeric rounding. IDs are scoped to the exact saved endpoint, including its path/query.

Typical responses, all with the original ID:

```json
{"id":"your-opaque-id","type":"progress","stage":"queued"}
{"id":"your-opaque-id","type":"progress","stage":"validating"}
{"id":"your-opaque-id","type":"progress","stage":"running","commands":2}
{"id":"your-opaque-id","type":"progress","stage":"command","index":0,"total":2,"state":"buffered"}
{"id":"your-opaque-id","type":"progress","stage":"command","index":1,"total":2,"state":"buffered"}
{"id":"your-opaque-id","type":"progress","stage":"printing"}
{"id":"your-opaque-id","type":"result","ok":true,"data":{"confirmation":"printed","commands":[{"callback":{"success":true}},{"callback":{"success":true}}],"callback":{"print_result":{"code":0,"message":"Transaction print successful!"}}}}
```

Callback fields/messages vary by firmware. The final `printed` confirmation requires the transaction's `onPrintResult(0, ...)`; an earlier `onRunResult(true)` is not treated as physical print completion. This distinction follows [SUNMI's printer documentation](https://file.cdn.sunmi.com/SUNMIDOCS/SunmiPrinter-Developer-Docs-1-1.pdf), transaction printing and callback sections.

After saving a final result on your server, acknowledge it:

```json
{"type":"ack","id":"your-opaque-id"}
```

Unacknowledged final results and the latest progress state replay after reconnect. Intermediate progress events are not a complete durable event stream. The same ID and job payload replay the current/final response without printing again while its journal record remains. Reusing an ID with a different payload returns `id_conflict`. Object key ordering is ignored when comparing payloads. Explicit defaults and omitted defaults are different payloads.

The journal keeps at most 1,000 jobs, evicting the oldest 100 acknowledged completions when full. It never evicts unacknowledged results. Keep unique job IDs on your server permanently: duplicate protection is bounded, and clearing app data also clears its journal. Completed image/text payloads are replaced by their SHA-256 digest and ID to keep storage small.

Accepted jobs survive disconnects. Queued jobs resume after restart; a job interrupted after it began returns `outcome_unknown` and is **not automatically reprinted**. Inspect the receipt before deliberately retrying with a new ID. Exactly-once physical printing cannot be guaranteed across power loss. Jobs accepted before a URL change still finish; their responses remain scoped to the old URL.

Errors look like:

```json
{"id":"your-opaque-id","type":"result","ok":false,"error":{"code":"bad_request","message":"lines must be in 0..255","index":1}}
```

Other codes include `queue_full`, `printer_unavailable`, `printer_error`, `download_failed`, and `outcome_unknown`. An unparseable envelope or missing ID returns `id:null`. `queue_full` admission failures are not retained, so the server can retry later. Command errors may occur after some paper has printed; a failed result does not imply zero paper.

## Jobs and commands

Job options:

| Field | Default | Meaning |
| --- | --- | --- |
| `type` | `job` | `job`, `status`, `ping`, or `ack` |
| `commands` | required for jobs | 1–128 commands, executed in order |
| `transaction` | `true` | Buffer the receipt and wait for physical completion |
| `reset` | `true` | Initialize printer formatting before the commands |
| `dry_run` | `false` | Validate arguments and decode/download images; never contact printer |
| `timeout_ms` | `30000` | Callback timeout, 1,000–120,000 ms; not a whole-job deadline |

Paper-free requests:

```json
{"id":"health-1","type":"status"}
{"id":"ping-1","type":"ping"}
{"id":"validate-1","dry_run":true,"commands":[{"op":"text","text":"Example\n"}]}
```

`status` returns printer state/name, model, service version, firmware, and paper width. `ping` returns `{"pong":true}` immediately. Dry runs check the app's argument conversions and resource limits, not whether firmware accepts every value or whether a barcode fits.

| `op` | Fields |
| --- | --- |
| `text` | `text`; include `\n` to end a line |
| `original_text` | `text`, using SUNMI's original/vector text method |
| `text_font` | `text`, optional `font` and `size` (24 default) |
| `feed` | `lines` (default 1, 0–255) |
| `style` | Persistent formatting, listed below |
| `columns` | Equal-length `texts`, positive `widths`, `aligns`; `weighted:true` (default) uses proportional widths; false uses character widths |
| `image` | `base64` or HTTP(S) `url`; optional `mode`: 0 normal, 1 threshold, 2 grayscale |
| `qr` | `data`, `size` 1–16 (default 4), `error` 0–3 (default 1) |
| `barcode` | `data`, `symbology` 0–8 (default 8/CODE128), `height` 1–255 (64), `width` 2–6 (2), `text_position` 0–3 (0) |
| `code2d` | `data`, `symbology` 0–2, `size` 1–16, `error` 0–8; interpretation depends on firmware |
| `raw` | One of `hex`, `base64`, or `bytes`; passes ESC/POS bytes unchanged |
| `call` | `method`, `args`, optional `wait_ms`; full device AIDL access |

Formatting: `align` is `left`, `center`, `right` (or 0, 1, 2); `size` is 1–256 dots. Boolean flags: `double_width`, `double_height`, `bold`, `underline`, `anti_white`, `strikethrough`, `italic`, `invert`. A `style` command changes subsequent commands. Inline `align`, `size`, and these flags apply to one command, then restore the job's tracked style. Keep low-level/raw formatting changes separate from this style tracking.

The `style` operation also accepts `font`, `right_spacing`, `relative_position`, `absolute_position`, `line_spacing`, `left_spacing`, `strikethrough_style`. Font selection is a no-op on this V2 firmware. These spacing/position fields are forwarded to SUNMI's `setPrinterStyle`; firmware determines supported ranges.

### Full printer interface

The generic `call` reaches every method declared in [IWoyouService.aidl](app/src/main/aidl/woyou/aidlservice/jiuiv5/IWoyouService.aidl). The AIDL transaction order matches the V2's **SunmiPrinterService 4.4.1**; do not substitute another model's AIDL without verifying its Binder transaction numbers. Hardware features absent from the V2 (such as a cutter or cash drawer) cannot be added by a job format.

```json
{
  "id":"query-1",
  "transaction":false,
  "commands":[
    {"op":"call","method":"getPrintedLength"},
    {"op":"call","method":"getPrinterDensity"}
  ]
}
```

List arguments in AIDL order, omitting callback arguments; the app supplies them. Primitive types must match. Byte arrays accept a base64 string, `{"hex":"1b40"}`, `{"base64":"G0A="}`, `{"bytes":[27,64]}`, or `[27,64]`. Bitmap arguments accept a base64 string or `{"url":"https://..."}` / `{"base64":"..."}`. `TransBean[]` accepts an array of `{"type":0,"text":"...","data":{"hex":"..."}}`. Return values appear under `return`, printer callbacks under `callback`, and tax bytes as base64 under `tax`.

Manual buffer controls, `commitPrint`, `printerSelfChecking`, `tax`, and `postPrintData` require `transaction:false`. A manually entered buffer must be exited in the same job. Callback waits are deferred until a manual commit. With `transaction:false`, the final confirmation is `submitted`, not a claim of physical printing; inspect any explicit commit callback. `wait_ms:0` skips waiting for a generic method that does not call back (for example `setFontName` on this firmware). System-only or unsupported methods may return failure, no-op, or time out.

Limits: 64 outstanding jobs, 128 commands/job, 1,024 serialized ID characters, roughly 1 MiB of JSON text/frame, 32 levels of canonicalized JSON nesting, 16 MiB/image download including chunked responses. Images must be <=576 dots wide and <=240,000 pixels each, <=1,000,000 pixels combined/job; the **V2 is 384 dots wide**, so wider images are cropped by its firmware. Split large images into strips. No hidden image scaling, feed, self-test, or receipt is performed at startup. Firmware buffer commits can flush an unfinished text line.

## Localhost testing

```sh
python3 -m venv /tmp/sunmi-test-venv
/tmp/sunmi-test-venv/bin/pip install -r tools/requirements.txt
adb reverse tcp:8765 tcp:8765
/tmp/sunmi-test-venv/bin/python tools/test_server.py
```

Set the app URL to `ws://127.0.0.1:8765`. USB reverse makes the computer's localhost reachable from the device; it must be restored after USB reconnection/reboot. Run the suite with `--print` to additionally print **one small mixed-content receipt**. The default uses no paper. `--report PATH` saves all response evidence; `--stay` keeps the test server running after the suite. These servers bind to localhost only.

Device-side queue/recovery/callback tests also use no paper:

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w app.wsprinter.test/app.wsprinter.ProtocolInstrumentation
```

Expect `OK (5 tests)`. These tests use isolated databases, not the production job journal. The debug build additionally accepts `adb shell am start -n app.wsprinter/.MainActivity --es url ws://127.0.0.1:8765` on a fresh activity launch. The release build accepts URL changes only through its settings dialog.

A separate no-paper lifecycle test reboots the connected device with one blocked callback and one queued status job, verifies boot startup and recovery, then checks the screen-off connection:

```sh
/tmp/sunmi-test-venv/bin/python tools/lifecycle_test.py --reboot
```

## Paper-free host fault tests

```sh
./gradlew testDebugUnitTest
```

The test-only Robolectric harness runs Android API 25 JSON, Binder callbacks, and SQLite on the development machine. A simulated printer exercises the real job runner; local TCP/WebSocket tests exercise `SocketLink`. No device or paper is needed. The 23 tests cover printer errors and missing completion callbacks, manual buffering, cleanup failures, QR formatting, durable recovery/retention, endpoint isolation, reconnects, rejected frames, and a 35-second connection-duration regression check. Test dependencies are excluded from the app APK. Results are in `app/build/reports/tests/testDebugUnitTest/index.html`. These tests complement physical device checks; they do not prove OEM boot behavior or receipt appearance.

For a longer paper-free battery-idle test on a connected device:

```sh
/tmp/sunmi-test-venv/bin/python tools/idle_test.py --force-doze --seconds 180
```

This temporarily simulates unplugged power, turns the screen off, forces Android Doze, checks the existing connection and printer status every 10 seconds, and compares printed-length counters. Its cleanup restores normal battery/idle state and wakes the screen. The app must already have its battery exemption.
