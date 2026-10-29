# linumeRT WebHMI — Android APK

Natywny klient Android WebView (Java) z eksperymentalnym interpreterem CPython przez Chaquopy.
**Wariant standardowy** nie uruchamia LinuxCNC ani serwera WebHMI na telefonie:
sprawdza lokalny runtime Pythona na \`127.0.0.1:8766/probe\`. **Wariant eksperymentalny**
zawiera kod uruchamiania serwera WebHMI na \`127.0.0.1:8765\`, ale jego zgodność i kompilacja
na Androidzie nie zostały jeszcze potwierdzone.
Szczegóły: [plan autonomicznego runtime](MOBILE_RUNTIME_ROADMAP.md).

## Warianty wykonawcze

- Standardowy `assembleDebug`: wbudowany Python 3.13 i diagnostyczny lokalny
  serwer `127.0.0.1:8766/probe`. Nie jest to serwer WebHMI.
- Eksperymentalny `assembleDebug -PlinumertFullWebHmi=true`: próba spakowania
  istniejących źródeł `linumert` i `linumert_webhmi`, FastAPI, Uvicorn,
  wsproto i Pydantic 1.10.24 w wariancie pure Python. Po uruchomieniu aplikacja próbuje wystartować
  `FakeBackend` na `127.0.0.1:8765`, potwierdza endpointy
  `/api/health` i uwierzytelniony `/api/state`, a dopiero potem otwiera panel.
  Komunikat błędu pozostawia możliwość połączenia z serwerem zdalnym.

Wariant eksperymentalny **nie jest jeszcze skompilowany ani przetestowany na
Androidzie 16**. Zamiast zależeć od natywnego `pydantic-core` używa kompatybilnego
z FastAPI Pydantic 1.10.24 (pakiet pure Python). Pozostałe wymagania pakietowe
muszą zostać sprawdzone w rzeczywistej kompilacji Chaquopy.

Serwer nie uruchamia LinuxCNC na telefonie i nie zastępuje sterownika
ruchu. Eksperymentalny serwer lokalny ma usługę pierwszoplanową Androida z
powiadomieniem i zatrzymywaniem; zgodność i stabilność tej usługi nie są jeszcze
zweryfikowane na urządzeniu. PRoot pozostaje dalszym etapem.

Lokalny serwer nigdy nie podmienia zdalnego połączenia z maszyną. Przy awarii
wbudowanego serwera aplikacja może automatycznie wrócić tylko do poprzednio
wybranego przez użytkownika adresu **HTTPS**, bez automatycznego uwierzytelniania.

## Podpis instalacyjny i rownolegle wydanie testowe

Istniejaca instalacja `pl.jakubzietarski.linumert.mobile` jest podpisana innym
kluczem niz nowe lokalne kompilacje debug; Android slusznie odrzuca aktualizacje
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Nie usuwamy starej aplikacji ani jej danych.

Od tej galęzi **wariant debug** używa odrębnego identyfikatora
`pl.jakubzietarski.linumert.mobile.preview` i etykiety **linumeRT Mobile (TEST)**.
Do aktualizacji starego identyfikatora wymagany jest jego oryginalny prywatny
klucz podpisujący lub przejście po świadomie zatwierdzonej migracji.
Lokalna kompilacja na telefonie:

```sh
bash /sdcard/Download/linumert-mobile-build.sh --install
```

Skrypt weryfikuje `output-metadata.json` przed instalacją i może testowo uruchomić
nowe WebHMI. Zapisuje `build.log`, SHA-256, `launch.txt`, `health.json` (gdy
serwer odpowiada). Lokalny debug keystore musi pozostać bezpiecznie zachowany,
aby kolejne aktualizacje tej samej wersji preview miały ten sam podpis.

## Pobranie i instalacja APK
1. Otwórz [Android WebHMI APK — GitHub Actions](https://github.com/kubus323f/linumeRT_GPT/actions/workflows/android-apk.yml).
2. Wybierz pomyślny przebieg \`Android WebHMI APK\` i pobierz artefakt \`linumeRT-WebHMI-Android16-APK\`.
3. Rozpakuj pobrany ZIP i otwórz \`app-debug.apk\` na telefonie; Android może poprosić o jednorazowe
   zezwolenie na instalację pakietu z aplikacji, z której go otwarto.
4. Wpisz adres WebHMI w APK i wybierz **Połącz**. Token wpisz dopiero w panelu WebHMI.

Workflow buduje APK osobno, bez LinuxCNC, ISO i pełnego CI. Uruchamia się po zmianach
projektu Android na gałęzi \`feat/android-webhmi-apk\`; ręcznie można go uruchomić,
gdy plik workflow jest dostępny również w gałęzi domyślnej.

## Próba na symulatorze
Na komputerze z linumeRT i Python:
\`\`\`bash
python -m pip install -e '.[dev]'
python -m linumert_webhmi --fake --host 192.168.1.10 --allow-remote
\`\`\`
W miejsce \`192.168.1.10\` wpisz rzeczywisty adres IP komputera w sieci Wi-Fi.
W APK wpisz \`http://192.168.1.10:8765/\`, zaznacz świadomie **HTTP tylko w prywatnej sieci**,
następnie token wypisany w konsoli serwera. Telefon i serwer muszą się widzieć w tej samej
zaufanej sieci, a zapora musi dopuszczać połączenie do portu 8765. Dla HTTP token
jest przesyłany bez szyfrowania. Preferuj HTTPS z prawidłowym certyfikatem.
Uwaga: \`--host 0.0.0.0\` może kolidować z kontrolą Host; używaj konkretnego adresu serwera.

## Ochrona i ograniczenia
- Przy starcie zgoda na niezabezpieczone HTTP jest wyłączona. HTTP dopuszczane jest tylko
  dla dosłownych prywatnych adresów IP/loopback (lub localhost). HTTPS wymaga poprawnego certyfikatu.
- WebView odrzuca nawigację i pobieranie podzasobów z obcych adresów, nie udostępnia
  natywnego mostka JavaScript, nie ma dostępu do plików i treści content://.
- Token nie jest przechowywany w ustawieniach ani w adresie URL.
- Przejście w tło zamyka stronę i połączenie WebSocket, ale **to nie gwarantuje zatrzymania
  maszyny** przy błędzie łączności lub awarii bramy.
- Fizyczny E-STOP oraz watchdog po stronie sterownika są niezbędne przed użyciem na maszynie.
  Aplikacja ani programowy STOP nie są elementami bezpieczeństwa funkcjonalnego.
- Artefakt to debug APK z kluczem debug środowiska kompilującego: kolejne buildy
  na innych urządzeniach mogą mieć inną sygnaturę. Nie odinstalowuj automatycznie aplikacji.
  Do trwałych aktualizacji potrzebny jest stały klucz podpisywania, poza repozytorium.
- Android 10+ (minSdk 29); Android 16/HyperOS 3 obsługiwany (targetSdk 35).
- Standardowy wariant nie działa offline bez zewnętrznego WebHMI. Wariant
  eksperymentalny ma działać offline z własnym serwerem symulacji, ale nie jest
  jeszcze zweryfikowany. Nie są potrzebne uprawnienia ADB/root.
- GPL-2.0-or-later.

## Kompilacja lokalna (opcjonalnie)
JDK 17 lub nowszy, Python 3.13 na urządzeniu kompilującym, Android SDK 35, Build Tools 35.0.0,
Gradle 8.11.1, Chaquopy 17.2.0 pobierane przez Gradle:
\`\`\`bash
cd android/webhmi
gradle :app:assembleDebug
\`\`\`
Wynik: \`app/build/outputs/apk/debug/app-debug.apk\`.
