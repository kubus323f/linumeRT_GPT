# linumeRT WebHMI — Android APK

Natywny klient Android WebView (Java, bez zależności aplikacji). **APK nie uruchamia LinuxCNC
na telefonie:** jest klientem serwera WebHMI działającego na komputerze CNC/symulatorze.

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
- Artefakt to debug APK z kluczem debug runnera: kolejne buildy mogą mieć inną sygnaturę,
  a instalacja nowszego pliku może wymagać odinstalowania poprzedniej wersji.
  Do trwałych aktualizacji potrzebny jest stały klucz podpisywania, poza repozytorium.
- Android 10+ (minSdk 29); Android 16/HyperOS 3 obsługiwany (targetSdk 35).
- Offline nie działa bez serwera. Brak uprawnień ADB/root; tylko dostęp do sieci.
- GPL-2.0-or-later.

## Kompilacja lokalna (opcjonalnie)
JDK 17, Android SDK 35, Build Tools 35.0.0, Gradle 8.11.1:
\`\`\`bash
cd android/webhmi
gradle :app:assembleDebug
\`\`\`
Wynik: \`app/build/outputs/apk/debug/app-debug.apk\`.
