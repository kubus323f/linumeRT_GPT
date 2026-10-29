# linumeRT Mobile: self-contained runtime — staged delivery (2026-10-29)

## Non-negotiable contract
- One Android APK starts and supervises its own local services, with no Termux, ADB, root, Docker or cloud runner required **at runtime**.
- Never represent interpreter readiness as WebHMI readiness. A local WebHMI backend must satisfy `GET /api/health` with HTTP 200, `ok=true`, `schema=1` and nonempty `task_state`. A port-open check alone does not qualify.
- Runtime priority in simulation mode: validated Chaquopy WebHMI -> validated PRoot WebHMI -> explicitly configured remote WebHMI -> unavailable. Never silently switch a real-machine session to simulation.
- No critical motion timing, machine E-STOP or watchdog responsibility on Android or under PRoot. Physical control stays on a separately qualified LinuxCNC host, with hardware E-STOP and independent watchdog.
- No generic commands, downloaded executable payloads, token-in-URL, or automatic retry of potentially executed machine commands.
- Runtime downloads, if ever added, require an explicit, pinned, cryptographically verified manifest and rollback strategy. No installing arbitrary images or code from internet.

## Stage S1 — source implementation in this branch
- Android WebView client preserved.
- Chaquopy 17.2 / embedded CPython 3.11 for arm64-v8a declared in Gradle.
- At Activity launch: launch an idempotent, loopback-only Python probe and show its actual result.
- Probe listens only on `127.0.0.1:8766/probe`, reports `kind=python-runtime-probe`, `schema=0`, `webhmi=false`.
- Pure Java backend selection policy and regression tests.
- No PRoot binary, Debian filesystem or LinuxCNC bundle. No WebHMI server on the phone yet.
- Existing remote connection remains manual and keeps HTTP opt-in, same-origin restrictions and close-on-background behavior.
- No continuous Android foreground service yet; probe lifetime is process-dependent.
- S1 source tests can run without device. **Android APK build/install remains unverified on the phone**.

## Stage S2 — full Chaquopy simulation backend
1. Resolve dependency compatibility first. Existing `linumert_webhmi` requires FastAPI, Uvicorn and Pydantic 2 including native `pydantic-core`. Android wheels must match CPython, ABI and page size; ordinary manylinux wheels are NOT substitutes.
2. Package original `core/linumert` and `webhmi/server/linumert_webhmi` Python sources and web static files in the APK; avoid copying/reimplementing machine semantics.
3. Instantiate only FakeBackend; expose WebHMI on `127.0.0.1:8765` with per-start random token and same-origin loopback policy.
4. Verify health, schema, authenticated REST, authenticated WebSocket, stop/deadman and reconnection. Never accept probe port 8766 as WebHMI.
5. Bring up and stop through an Android-managed foreground service with appropriate notification type, explicit user control, lifecycle handling and log export.
6. Gate on real-device build, launch, screen-off, thermal, memory, crash-recovery, and reboot tests. No release without verification.

## Stage S3 — optional managed PRoot fallback
- Bundle only a reviewed, pinned PRoot binary and ARM64 Debian rootfs, with SHA-256 verification, signature/provenance audit, storage limits and rollback. Check Android 16/HyperOS compatibility and executable restrictions on the exact device.
- No Termux dependency. Launch by managed process only when explicitly supported by OS privileges; no `adb shell` bootstrap after installation.
- Same real WebHMI health contract as Chaquopy; fallback ONLY if startup failed before any machine control operation. On runtime failure show an explicit mode change.
- Compare performance: median/p95 startup, CPU load, resident memory, WebSocket round trip, idle drain, and under-screen-off stability. No percent claims without device measurements.

## Stage S4 — remote physical LinuxCNC
- Connect to separately hosted LinuxCNC, not a local PRoot RT controller.
- Require independent hardware E-STOP and watchdog/heartbeat integration before physical motion tests.
- No automatic failover to fake backend while connected to a real machine.

## Current acceptance gates
- S1 Java policy tests: PASS only once executed against the committed source.
- S1 Python probe tests: PASS only once executed against committed source.
- S1 Gradle/Chaquopy build: PENDING.
- S1 APK on Android 16 / HyperOS: PENDING.
- S2 actual local WebHMI: NOT IMPLEMENTED.
- S3 PRoot: NOT IMPLEMENTED.
- Real hardware control: OUT OF SCOPE.
