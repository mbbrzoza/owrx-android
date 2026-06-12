# OWRX Mobile

Natywny klient Android dla OpenWebRX+ (fork luarvique, v1.2.x). By SP8MB.

Aplikacja do słuchania własnych odbiorników OpenWebRX w terenie — z audio
działającym w tle (foreground service), skanerem pasm i panelem TETRA Monitor.

## Funkcje
- **Audio w tle**: foreground service + MediaSession, wake/wifi lock, watchdog,
  automatyczny reconnect z wykładniczym backoffem i wznowieniem ostatniego stanu
  (częstotliwość/tryb/squelch) po zerwaniu LTE.
- **Odbiornik**: wodospad + widmo (pinch-zoom, tap-to-tune), tryby AM/NFM/SSB/CW,
  squelch, S-meter, profile serwera, zakładki (bookmarki + dial frequencies).
- **Skaner**: detekcja nośnych z danych FFT (próg nad medianą szumu, raster
  8.33/12.5/25 kHz), stany SCANNING→LOCKED→HANG, cooldown, blacklista,
  historia trafień (Room). Tryb wieloprofilowy z twardym limitem ≥10 s/profil
  (ochrona przed banem serwera — robotScore). Komunikat `backoff` zatrzymuje skaner.
- **TETRA Monitor**: port webowego panelu — sieć (MCC/MNC/LA, czas ETSI,
  szyfrowanie), szczeliny, sąsiedzi, log aktywności, aktywne SSI w 3 kategoriach
  (🟢 Real / 🔵 Adres / 🔒 ESI), rejestracje MS, SDS, aktywność szyfrowana.
- **Serwery**: lista z basic auth (np. nginx przed publicznym OWRX).

## Budowanie
```
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # release (wymaga keystore.properties)
./gradlew :app:testDebugUnitTest      # testy JVM (fixture'y z żywego serwera)
```
Fixture'y protokołu można odświeżyć: `python3 tools/capture_fixtures.py ws://HOST:8073/ws/ 15`

## Architektura
`OwrxSession` (singleton) trzyma WebSocket + dekodery + stan; UI tylko obserwuje
StateFlow. Dekodery ADPCM to port 1:1 z `htdocs/lib/AudioEngine.js` OpenWebRX+.
Ramki binarne: `[0]=1` FFT, `2` audio, `4` HD audio. Szczegóły: `protocol/`.
