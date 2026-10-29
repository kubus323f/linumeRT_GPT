#!/data/data/com.termux/files/usr/bin/bash
# SPDX-License-Identifier: GPL-2.0-or-later
# Builds the experimental linumeRT WebHMI APK on the user's *existing* Termux toolchain.
# Does not install packages, remove applications, alter Android settings or enable ADB.
set -Eeuo pipefail

BRANCH="feat/mobile-embedded-runtime-stage1-20261029"
REPOSITORY="https://github.com/kubus323f/linumeRT_GPT.git"
BASE="/sdcard/Download"
STAMP="$(date +%Y%m%d_%H%M%S)"
OUT="$BASE/linumert-mobile-build-$STAMP"
WORK="$HOME/.cache/linumert-mobile-build"
LOG="$OUT/build.log"
mkdir -p "$OUT"
exec > >(tee -a "$LOG") 2>&1
completed=0
finish() {
    code=$?
    if [ "$completed" -eq 1 ] && [ "$code" -eq 0 ]; then
        echo "PASS" > "$OUT/STATUS.txt"
        echo "Wynik: PASS | $OUT"
    else
        echo "FAIL ($code)" > "$OUT/STATUS.txt"
        echo "Wynik: FAIL | $OUT"
        echo "Przekaz do diagnostyki: $OUT/build.log"
    fi
}
trap finish EXIT
trap 'echo "ERROR command=$BASH_COMMAND line=$LINENO"' ERR

INSTALL=0
case "${1:-}" in
    "") ;;
    --install) INSTALL=1 ;;
    *) echo "Uzycie: bash build-on-phone-termux.sh [--install]"; exit 2 ;;
esac

echo "=== linumeRT MOBILE LOCAL BUILD ==="
date
echo "Logi i APK: $OUT"
echo "Instalacja po buildzie: $INSTALL"
if [ -z "${TERMUX_VERSION:-}" ] && [[ "${PREFIX:-}" != /data/data/com.termux/* ]]; then
    echo "ERROR: Ten skrypt uruchom w terminalu Termux, nie wewnatrz adb shell."
    exit 2
fi

for cmd in git gradle java javac python3 sha256sum; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "ERROR: Brakuje $cmd w istniejacym srodowisku telefonu."
        exit 3
    fi
done

python3 - <<'PY'
import sys
print("Python host:", sys.version)
if sys.version_info[:2] != (3, 13):
    raise SystemExit("ERROR: ta galaz wymaga host Python 3.13 dla Chaquopy 3.13")
PY
java -version
gradle --version

SDK=""
for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/android-sdk"; do
    if [ -n "$candidate" ] && [ -f "$candidate/platforms/android-35/android.jar" ]; then
        SDK="$candidate"
        break
    fi
done
if [ -z "$SDK" ]; then
    echo "ERROR: Nie znaleziono Android SDK 35 w znanych sciezkach; nic nie instaluje."
    exit 4
fi
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
echo "SDK: $SDK"

mkdir -p "$(dirname "$WORK")"
if [ ! -d "$WORK/.git" ]; then
    if [ -e "$WORK" ]; then
        echo "ERROR: $WORK istnieje i nie jest repozytorium Git; nie nadpisuje go."
        exit 5
    fi
    git clone --depth=1 --branch="$BRANCH" --single-branch "$REPOSITORY" "$WORK"
else
    case "$(git -C "$WORK" remote get-url origin)" in
        "$REPOSITORY"|"https://github.com/kubus323f/linumeRT_GPT") ;;
        *) echo "ERROR: Nieoczekiwane repozytorium w katalogu roboczym"; exit 5 ;;
    esac
    if [ -n "$(git -C "$WORK" status --porcelain --untracked-files=no)" ]; then
        echo "ERROR: Lokalne zmiany w repozytorium; nie nadpisuje ich."
        exit 5
    fi
    git -C "$WORK" fetch --depth=1 origin "$BRANCH"
    git -C "$WORK" checkout "$BRANCH"
    git -C "$WORK" merge --ff-only FETCH_HEAD
fi
git -C "$WORK" rev-parse HEAD | tee "$OUT/source-commit.txt"
printf 'sdk.dir=%s\n' "$SDK" > "$WORK/android/webhmi/local.properties"

echo "=== SOURCE TESTS ==="
cd "$WORK"
python3 -m unittest discover -s android/webhmi/tests -p 'test_*.py' -v
mkdir -p "$WORK/.linumert-java-tests"
javac -d "$WORK/.linumert-java-tests" \
    android/webhmi/app/src/main/java/pl/jakubzietarski/linumert/mobile/MobileRuntimePolicy.java \
    android/webhmi/tests/MobileRuntimePolicyTest.java \
    android/webhmi/app/src/main/java/pl/jakubzietarski/linumert/mobile/ServerUrlPolicy.java \
    android/webhmi/tests/ServerUrlPolicyTest.java
java -cp "$WORK/.linumert-java-tests" pl.jakubzietarski.linumert.mobile.MobileRuntimePolicyTest
java -cp "$WORK/.linumert-java-tests" pl.jakubzietarski.linumert.mobile.ServerUrlPolicyTest

echo "=== BUILD EXPERIMENTAL LOCAL WEBHMI ==="
# Android's stock build-tools aapt2 is commonly Linux x86-64 and cannot run
# on this ARM64 phone. Never select a candidate solely because it is executable.
AAPT2=""
AAPT2_PROBE="$OUT/aapt2-probe.txt"
: > "$AAPT2_PROBE"
candidates=(
    "${LINUMERT_AAPT2:-}"
    "${PREFIX:-/data/data/com.termux/files/usr}/bin/aapt2"
    "$(command -v aapt2 || true)"
    "$HOME/bin/aapt2"
    "$HOME/android-sdk/build-tools/34.0.4/aapt2"
    "$SDK/build-tools/34.0.4/aapt2"
    "$SDK/build-tools/35.0.0/aapt2"
    "$SDK/build-tools/36.0.0/aapt2"
)
# Scan installed SDK tools too, but independently verify ELF architecture.
if [ -d "$SDK/build-tools" ]; then
    while IFS= read -r candidate; do candidates+=("$candidate"); done < <(
        find "$SDK/build-tools" -mindepth 2 -maxdepth 2 -name aapt2 -type f 2>/dev/null
    )
fi
for candidate in "${candidates[@]}"; do
    if [ -z "$candidate" ] || [ ! -f "$candidate" ] || [ ! -x "$candidate" ]; then
        continue
    fi
    if probe="$(python3 - "$candidate" 2>&1 <<'PY'
import pathlib
import subprocess
import sys

p = pathlib.Path(sys.argv[1])
resolved = p.resolve()
try:
    with resolved.open("rb") as stream:
        header = stream.read(20)
    if header.startswith(bytes([127]) + b"ELF"):
        endian = "little" if header[5] == 1 else "big"
        machine = int.from_bytes(header[18:20], endian)
        if machine != 183:
            raise RuntimeError(f"ELF e_machine={machine} (requires ARM64/183)")
    elif not header.startswith(b"#!"):
        raise RuntimeError("not ELF or an executable wrapper script")
    run = subprocess.run([str(p), "version"], stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT, text=True, timeout=8, check=False)
    output = run.stdout.strip().replace(chr(10), " ")[:280]
    if run.returncode != 0 or "aapt" not in output.lower() or "2" not in output:
        raise RuntimeError(f"version probe failed (exit={run.returncode}): {output}")
    print(f"ACCEPT {p} -> {resolved}: {output}")
except Exception as exc:
    print(f"REJECT {p} -> {resolved}: {type(exc).__name__}: {exc}")
    sys.exit(1)
PY
)"; then
        echo "$probe" | tee -a "$AAPT2_PROBE"
        AAPT2="$candidate"
        break
    else
        echo "$probe" | tee -a "$AAPT2_PROBE"
    fi
done
if [ -z "$AAPT2" ]; then
    echo "ERROR: Brak zgodnego, uruchamialnego AAPT2 ARM64." | tee -a "$AAPT2_PROBE"
    echo "Podaj aapt2-probe.txt i build.log z: $OUT" | tee -a "$AAPT2_PROBE"
    echo "Nie uruchamiam x86-64 ani nie instaluje niezweryfikowanych binarek."
    exit 8
fi
args=(
    --no-daemon
    --stacktrace
    -p "$WORK/android/webhmi"
    :app:assembleDebug
    -PlinumertFullWebHmi=true
    "-Pandroid.aapt2FromMavenOverride=$AAPT2"
)
echo "Wybrany natywny AAPT2 ARM64: $AAPT2"

gradle "${args[@]}"
APK="$WORK/android/webhmi/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -s "$APK" ]; then
    echo "ERROR: Gradle nie wygenerowal APK"
    exit 6
fi
cp "$APK" "$OUT/linumeRT-Mobile-dev2-experimental.apk"
sha256sum "$OUT/linumeRT-Mobile-dev2-experimental.apk" | tee "$OUT/SHA256.txt"
echo "APK: $OUT/linumeRT-Mobile-dev2-experimental.apk"

SIGNER="$SDK/build-tools/35.0.0/apksigner"
if [ ! -x "$SIGNER" ]; then SIGNER="$SDK/build-tools/36.0.0/apksigner"; fi
if [ -x "$SIGNER" ]; then
    "$SIGNER" verify --verbose --print-certs "$OUT/linumeRT-Mobile-dev2-experimental.apk" \
        | tee "$OUT/apk-signing.txt"
else
    echo "OSTRZEZENIE: apksigner nie znaleziony; brak potwierdzenia podpisu."
fi

if [ "$INSTALL" -eq 1 ]; then
    echo "=== SAFE ADB INSTALL -r ==="
    if ! command -v adb >/dev/null 2>&1; then
        echo "ERROR: adb niedostepne; APK zostalo zachowane."
        exit 7
    fi
    if ! adb -s 127.0.0.1:5555 get-state 2>/dev/null | grep -qx device; then
        echo "ERROR: Lokalna autoryzowana sesja ADB nie jest aktywna. APK zostalo zachowane."
        exit 7
    fi
    echo "Instalacja zachowuje dane aplikacji. Przy innym certyfikacie podpisu zatrzyma sie bez odinstalowania."
    adb -s 127.0.0.1:5555 install -r "$OUT/linumeRT-Mobile-dev2-experimental.apk"
    echo "adb install -r: sukces (nie oznacza jeszcze testu dzialania serwera)."
fi
completed=1
echo "=== END ==="
