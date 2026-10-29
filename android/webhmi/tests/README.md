# Mobile runtime tests

Run offline where Python 3.13 and JDK 17+ are available:

```sh
python3 -m unittest discover -s android/webhmi/tests -p 'test_*.py' -v
mkdir -p /tmp/linumert-mobile-policy
javac -d /tmp/linumert-mobile-policy \
  android/webhmi/app/src/main/java/pl/jakubzietarski/linumert/mobile/MobileRuntimePolicy.java \
  android/webhmi/tests/MobileRuntimePolicyTest.java
java -cp /tmp/linumert-mobile-policy \
  pl.jakubzietarski.linumert.mobile.MobileRuntimePolicyTest
```

Build Android APK (requires Android SDK and Chaquopy-compatible Python 3.11 at build time):

```sh
gradle --no-daemon -p android/webhmi :app:assembleDebug
```

Never claim these host tests prove on-device build, PRoot operation, WebHMI operation, or LinuxCNC safety.
