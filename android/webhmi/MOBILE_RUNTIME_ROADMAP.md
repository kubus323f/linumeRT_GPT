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
- Chaquopy 17.2 / embedded CPython 3.13 for arm64-v8a declared in Gradle.
- At Activity launch: launch an idempotent, loopback-only Python probe and show its actual result.
- Probe listens only on `127.0.0.1:8766/probe`, reports `kind=python-runtime-probe`, `schema=0`, `webhmi=false`.
- Pure Java backend selection policy and regression tests.
- No PRoot binary, Debian filesystem or LinuxCNC bundle. No WebHMI server on the phone yet.
- Existing remote connection remains manual and keeps HTTP opt-in, same-origin restrictions and close-on-background behavior.
- Foreground service is only compiled and used for the experimental verified local WebHMI; the standard Python probe remains process-dependent.
- S1 source tests can run without device. **Android APK build/install remains unverified on the phone**.

## Stage S2 — full Chaquopy simulation backend (EXPERIMENTAL SOURCE IMPLEMENTED)

Experimental build switch:
```sh
gradle --no-daemon -p android/webhmi :app:assembleDebug -PlinumertFullWebHmi=true
```
This switch bundles original sources from `core/linumert` and `webhmi/server/linumert_webhmi`
using Chaquopy sourceSets, and requests pinned FastAPI/Uvicorn/wsproto/Pydantic 1.10.24
dependencies. Pydantic 1 can use a pure-Python wheel, so native pydantic-core is not required. It instantiates `FakeBackend` only. The default build does not download
those dependencies and remains a remote client with an embedded Python diagnostics probe.

The Android app automatically tries the local server in the experimental build,
requires both `/api/health` and authenticated `/api/state` to pass before connecting,
and uses a per-session token held only in process memory. On error it leaves the
remote client available. No successful wheel resolution, Gradle build or device test
has yet been confirmed. There must be no silent substitution of host Linux binaries
for any missing Android-native dependency.

**S2 remains NOT ACCEPTED until real-APK tests pass.**

## S2 acceptance criteria / outstanding implementation
1. Confirm compatibility of the current mobile FastAPI/Uvicorn/wsproto/Pydantic 1.10.24 pins (universal-wheel route). Original schema now uses the Pydantic v1/v2 field-validator compatibility import; desktop Pydantic 2 remains supported. Android wheels must still match CPython, ABI and page size.
2. Package original `core/linumert` and `webhmi/server/linumert_webhmi` Python sources and web static files in the APK; avoid copying/reimplementing machine semantics.
3. Instantiate only FakeBackend; expose WebHMI on `127.0.0.1:8765` with per-start random token and same-origin loopback policy.
4. Verify health, schema, authenticated REST, authenticated WebSocket, stop/deadman and reconnection. Never accept probe port 8766 as WebHMI.
5. Source now declares a private Android specialUse foreground service with notification and explicit stop. Device lifecycle, permitted foreground-service starts and diagnostic log export still require verification and further work.
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
- S2 original local WebHMI startup: experimental code implemented using Pydantic 1 compatible mode and wsproto; end-to-end API verification, actual build and Android validation PENDING.
- Android foreground host: source implementation present; on-device permission/service lifetime tests PENDING.
- Automatic HTTPS fallback: implemented only for previously user-selected HTTPS addresses; explicit HTTP consent and machine-mode safety remain required.
- APK update signing: pinned stable certificate and continuity with installed debug APK NOT SOLVED.
- S3 PRoot: NOT IMPLEMENTED.
- Real hardware control: OUT OF SCOPE.

## 2026-10-29 device build: signer mismatch resolved non-destructively
- Device build with ARM64 aapt2 reached installation of the experimental APK, but Android rejected an in-place update due to `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- The new debug build uses a separate application ID `.mobile.preview`, leaving the original `.mobile` package and data intact.
- The installer refuses to install unless Gradle's `output-metadata.json` matches the preview ID and versionCode 2.
- After installation, a diagnostic launch and loopback health probe are attempted. A successful APK install is never treated as proven WebHMI operation.
- A future in-place migration to the original package requires its original signing key; no unattended uninstall or destructive data migration.
- On-device installation and runtime smoke results for the new preview are still PENDING.
