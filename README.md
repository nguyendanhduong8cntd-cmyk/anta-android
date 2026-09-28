# Alobo Android clone

Native Android reconstruction of the inspected `com.alobo.sportUser` app (version 2.10.1). The project is intentionally self-contained and renders the explored product flows without depending on the original backend.

## Build and install

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.alobo.sportUser.clone/com.alobo.clone.MainActivity
```

The app is locked to portrait and uses immersive mode to hide the system navigation bar. Runtime permission prompts cover location, notifications, and camera.

## Diagnostics

All screen displays, dialogs/overlays, clickable controls, permission results, and external intents use the `VN_NAMI` log tag:

```powershell
adb logcat -s VN_NAMI:D *:S
```

`NetworkLogger` emits `CURL`, `REQUEST`, and `RESPONSE` entries for backend integrations. The reconstructed demo currently makes no backend request.

See [docs/CLONE_REPORT.md](docs/CLONE_REPORT.md) for the explored screen graph and drawable provenance.

See [docs/BACKEND_ARCHITECTURE.md](docs/BACKEND_ARCHITECTURE.md) for the APK-grounded backend architecture, domain boundaries, API contract, booking/payment consistency rules, and implementation roadmap.

## Backend implementation

The backend MVP is maintained in a [separate backend repository](https://github.com/nguyendanhduong8cntd-cmyk/alobo-backend): NestJS API, PostgreSQL/PostGIS, Redis/BullMQ worker, booking holds, sandbox signed payment webhooks, notifications, and a separate SSE/Socket.IO domain-event gateway. See its README for setup, tests, OpenAPI, and the remaining architecture phases. The Android demo is **not yet connected** to this backend; real SMS/social/payment providers and production operations remain unfinished.
