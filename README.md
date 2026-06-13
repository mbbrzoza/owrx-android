# OWRX Mobile

Natywny klient Android dla [OpenWebRX+](https://github.com/luarvique/openwebrx)
(fork luarvique, v1.2.x). Autor: **SP8MB**.

Aplikacja do słuchania własnych odbiorników OpenWebRX w terenie — z audio
działającym **w tle** (foreground service), skanerem pasm, monitorem TETRA i mapą
APRS. Powstała, bo przeglądarka na Androidzie nie nadaje się do terenu: audio pada
przy zgaszonym ekranie, a zerwanie LTE wymaga ręcznego odświeżania.

> Interfejs jest po polsku. Aplikacja łączy się z **Twoimi** serwerami OpenWebRX+
> (obsługuje basic auth nginx). To czysty klient — nie zawiera ani nie obchodzi
> żadnego szyfrowania.

## Funkcje

### Odbiornik
- **Wodospad + widmo** z podziałką częstotliwości w stylu OpenWebRX: pinch-zoom,
  przesuwanie, tap = strojenie. Tagi zakładek nad skalą (w dwóch rzędach, gdy
  gęsto), auto-poziomy kolorów z percentyli.
- **Cyfrowy odczyt częstotliwości** — tap otwiera wpisanie MHz; jeśli częstotliwość
  jest poza bieżącym pasmem, aplikacja sama przeskakuje do profilu, który ją
  pokrywa (z wyuczonych zakresów), albo przesuwa środek SDR (`setfrequency`,
  jeśli serwer pozwala + magic key).
- **Jeden pasek narzędzi**: wyciszenie + głośność programowa (long-press),
  squelch z trybem Auto, AGC / ręczny gain urządzenia, redukcja szumów (NR),
  nagrywanie, paleta wodospadu.
- **Tryby** AM/NFM/SSB/CW i inne z listy serwera. **Digimody** (Packet/APRS/POCSAG/
  FT8/SSTV…) — wybór z pickera, kliknięcie zakładki na wodospadzie lub ulubionej
  automatycznie przełącza nośnik analogowy (np. Packet→`empty`, FT8→`usb`) i włącza
  dekoder wtórny (`mod` + `secondary_mod` w jednej komendzie, jak web-klient); panel
  zdekodowanych wiadomości pokazuje się sam.
- **Kto nadaje** — pasek z metadanymi cyfrowego głosu (DMR/YSF/D-STAR/NXDN).
- **Profile** (wysuwany panel z lewej) i **Ulubione** (z prawej) — zakładki uczą
  się automatycznie z odwiedzanych profili, można je dodawać ręcznie z bieżącej
  częstotliwości oraz eksportować/importować do JSON. Ulubiona z innego pasma
  sama przełącza profil.
- **Licznik słuchaczy + czat** OWRX.

### Audio w tle
- Foreground service + MediaSession, wake/wifi lock.
- **Watchdog** (30 s ciszy) + automatyczny reconnect z wykładniczym backoffem;
  po zerwaniu LTE wznawia ostatni stan (częstotliwość/tryb/squelch).
- Dekodery IMA ADPCM (audio + FFT) to port 1:1 z `htdocs/lib/AudioEngine.js`.
- **Zamknij aplikację** — przycisk pełnego zamknięcia (rozłącza sesję, zatrzymuje
  serwis i pracę w tle, zwalnia wakelock, usuwa z ostatnich). Akcja Stop w
  powiadomieniu też w pełni rozłącza.

### Nagrywanie
- Do plików **.m4a (AAC)** w `Android/data/pl.sp8mb.owrx/files/Music/`.
- Tryb **VOX** — automatyczne nagrywanie każdej transmisji (start przy otwarciu
  squelcha, koniec po ciszy). Idealne ze skanerem.

### Skaner
- Trzy tryby: **całe pasmo**, **zakres** (od–do w MHz), **zakładki** (skan
  pamięciowy — nasłuch tylko na wybranych zakładkach, z trybem zapisanym w zakładce).
- Detekcja nośnych z danych FFT, próg ręczny lub **Auto** (liczony z rozrzutu
  szumu każdej ramki), raster 8.33 / 12.5 / 25 kHz.
- Maszyna stanów SCANNING → LOCKED → HANG, cooldown per częstotliwość, blacklista,
  historia trafień (Room), **beep + wibracja** przy złapaniu.
- Tryb wieloprofilowy z twardym limitem **≥ 10 s/profil** (ochrona przed banem
  serwera — robotScore). Komunikat `backoff` natychmiast zatrzymuje skaner.

### TETRA Monitor
Port webowego panelu: sieć (MCC/MNC/LA, czas ETSI, szyfrowanie), szczeliny,
sąsiedzi, log aktywności, aktywne SSI w 3 kategoriach (🟢 Real / 🔵 Adres / 🔒 ESI),
rejestracje MS, SDS, aktywność szyfrowana, zakładka DMO. W sieciach szyfrowanych
(same aliasy ESI) lista SSI pokazuje stopkę „N ukryte — pokaż" do odsłonięcia.

### Mapa
Mapa OSM (OSMDroid) z dwóch źródeł: drugie połączenie WebSocket (`type=map`,
agregat serwera) **oraz lokalnie zdekodowane ramki** (APRS/AIS… wyłuskane z
`secondary_demod` — co sam dekodujesz, ląduje na mapie).
- **Prawdziwe ikony APRS** (sprite symboli, wycinane po `index`/`tableindex` jak
  web) per stacja; **samoloty** obrócone wg kursu dla trybów lotniczych
  (ADSB/VDL2/HFDL); marker odbiornika z auto-centrowaniem.
- **Klastrowanie** gęstych warstw stałych z licznikiem (tap = zoom); **zdekodowane
  stacje zawsze pojedynczo** (widać symbol każdej); lokalne dekody wyróżnione.
- **Przełączniki warstw** — sieć innych odbiorników (KiwiSDR/OpenWebRX/WebSDR) i
  bazy (stacje/repeatery) domyślnie **ukryte**, żeby nie zaciemniać mapy.
- Tap markera = info (znak, tryb, wiek, komentarz). TTL 1 h.

### Admin SDR
Zarządzanie serwerem przez panel `/settings` (wymaga danych admina OWRX):
lista urządzeń, gain urządzenia (AGC/ręczny), tworzenie nowych profili.

## Budowanie

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # podpisany release (wymaga keystore.properties)
./gradlew :app:testDebugUnitTest  # testy JVM (dekodery + skaner + TETRA)
```

APK trafia do `app/build/outputs/apk/`. Podpisywanie release wymaga pliku
`keystore.properties` (poza repo) wskazującego na keystore — patrz `app/build.gradle.kts`.

Fixture'y protokołu do testów można odświeżyć z żywego serwera:
```bash
python3 tools/capture_fixtures.py ws://HOST:8073/ws/ 15
```

**Wymagania:** Android 8.0+ (minSdk 26), JDK 17, Android SDK 35.

## Architektura

- **`protocol/`** — czysty Kotlin, bez zależności od Androida, testowalny na JVM:
  parser wiadomości, dekodery ADPCM (audio + FFT), budowniczy komend.
- **`OwrxSession`** (Hilt singleton) — trzyma WebSocket, dekodery i stan; cała
  reszta (ViewModele, serwis) obserwuje `StateFlow`, więc UI może się odłączać
  i podłączać, a audio leci dalej.
- Ramki binarne WebSocket: pierwszy bajt = typ (`1` FFT, `2` audio, `4` HD audio).
- Protokół zweryfikowany empirycznie wobec źródeł luarvique/openwebrx oraz
  przechwyconego ruchu (m.in.: serwer nie odpowiada na pingi klienta → brak
  pingInterval; S-meter jest liniowy → konwersja `10·log10`).

## Licencja

Kod własny SP8MB. OpenWebRX+ jest projektem osobnym (GAGPLv3) — ta aplikacja to
niezależny klient komunikujący się z nim po sieci.
