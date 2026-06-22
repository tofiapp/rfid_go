# RFID Go

Android aplikace pro čtečku **Chainway C5** (vestavěný UHF UART modul, RSCJA/Chainway SDK).
Slouží k **přepisu EPC** UHF tagů podle definované šablony, **zaheslování** a **zamčení** tagů a k **zápisu údajů o tagu do tabulky CSV**.

---

## Co aplikace umí

Aplikace má pět rozbalovacích karet (náhled celého EPC je vidět pořád). Akční tlačítka zápisu a zamčení zůstávají vždy viditelná i při sbalené kartě.

### 1. Zdroj dat – výběr TUDU
- Načte úseky **TUDU** ze souboru `.CSV` nebo `.SQL`.
- Po výběru TUDU se zobrazí seznam **výhybek** v tomto TUDU.
- Postupuje se výhybku po výhybce.

Formát vstupního souboru (oddělovač `;` nebo `,`, hlavička volitelná):

```
TUDU;VYHYBKA;CAST_MIN;CAST_MAX
1501J1;1;1;3
1501J1;10;1;4
1501A;5;1;3
```

Stačí i jen `TUDU;VYHYBKA` – části se doplní na `1–3`.
Vzorové soubory jsou ve složce [`sample_data/`](sample_data).

### 2. Šablona EPC a zápis
EPC = **24 hex znaků** (bank EPC, ptr 2, Len 6). Skládá se podle 7 řádků šablony:

| # | Délka | Kategorie | Pravidlo | Příklad |
|---|-------|-----------|----------|---------|
| 1 | 4 | Rok | fixně, lze přepsat | `2026` |
| 2 | 4 | TUDU 1.–4. znak | první 4 znaky TUDU | `1501` |
| 3 | 2 | TUDU 5. znak | ASCII hex | `J` → `4A` |
| 4 | 2 | TUDU 6. znak | 2-místně | `1` → `01` |
| 5 | 3 | Výhybka | 3-místně dekadicky | `10` → `010` |
| 6 | 1 | Část výhybky | 1 znak (1–4) | `1` |
| 7 | 8 | ID_RFID | 8-místně dekadicky, +1 | `30001` → `00030001` |

Příklad: `1501J1`, výhybka `10`, část `1`, ID `30001` →
`2026 1501 4A 01 010 1 00030001` = `202615014A01010100030001`.

- **Názvy kategorií** i hodnoty Rok / Část / ID_RFID lze ručně přepsat.
- Pod tím je rozhraní zápisu: `bank EPC`, `ptr 2`, `Len 6`, **Access pwd** (default `00000000`) a výkon v dBm.
- Tlačítko **ZAPSAT** přepíše EPC tagu v dosahu. Funguje i fyzickým **tlačítkem (spouští) čtečky**.
- Po úspěšném zápisu se automaticky:
  - `ID_RFID += 1`,
  - posune **část výhybky** o 1; po překročení maxima se vrátí na začátek a přepne na **další výhybku** v pořadí daného TUDU.

### 3. Tabulka CSV
Po každém zápisu EPC (lze vypnout) se uloží řádek do `rfid_go_output.csv`:

| Sloupec | Zdroj |
|---------|-------|
| ID_RFID | řádek 7 bez vodících nul (`00030001` → `30001`) |
| EPC | celých 24 znaků |
| TID | přečtený z tagu |
| Rok | řádek 1 |
| TUDU | řádek 2 + dekódovaný 3 (`4A`→`J`) + dekódovaný 4 (`01`→`1`) |
| Výhybka | řádek 5 (`010` → `10`) |
| Část | řádek 6 |

Při zápisu stejného `ID_RFID` se daný řádek **přepíše**.
Tabulku lze sdílet/exportovat tlačítkem **Sdílet / Export** nebo vymazat.

Soubor je uložen v `Android/data/com.rfidw.app/files/rfid_go_output.csv`.

### 4. Zaheslování – zápis access hesla
- **bank RESERVED**, `ptr 2`, `len 2` (access password, 8 hex znaků)
- Pole **ACCESS PWD** – aktuální heslo tagu (default `00000000`)
- Pole **NEW PWD** – nové heslo (8 hex znaků)
- Tlačítko **ZAPSAT** (vždy viditelné) zapíše nové access heslo na tag v dosahu

### 5. Zamčení tagu
- Pole **NEW ACCESS PWD** – heslo pro zamčení (po zápisu hesla se doplní automaticky)
- **Lock code** – pevná hodnota `008020`
- Tlačítko **ZAMKNOUT** (vždy viditelné) zamkne tag v dosahu

### Spouště čtečky
Nahoře lze vybrat, kterou akci provede fyzické tlačítko čtečky:
- **Zápis EPC** (výchozí)
- **Zápis hesla**
- **Zamknout**

Výběr se uloží a při stisku akčního tlačítka na obrazovce se automaticky přepne.

---

## Sestavení

Projekt je standardní Android (Gradle). Otevřete v **Android Studiu** nebo přes Cursor a:

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

- `compileSdk 34`, `minSdk 21`, `targetSdk 34`, Java 17, Material 3.
- Knihovny čtečky a Excelu jsou v `app/libs/`:
  - `DeviceAPI_ver20251103_release.aar` – Chainway/RSCJA UHF SDK (obsahuje i nativní `.so`),
  - `poi-*`, `jxl.jar`, `xUtils-*` – ponechány pro budoucí export do XLSX.

> Pozn.: Při prvním otevření Android Studio vygeneruje `local.properties` s cestou k SDK.

---

## Struktura kódu

```
app/src/main/java/com/rfidw/app/
├─ epc/EpcModel.java       – sestavení a rozklad EPC (jádro logiky)
├─ data/Tudu.java          – model TUDU + výhybky
├─ data/TuduLoader.java    – načítání z .csv / .sql
├─ csv/CsvStore.java       – výstupní CSV s přepisem podle ID_RFID
├─ rfid/UhfManager.java    – obal nad RFIDWithUHFUART (EPC, heslo, zamčení)
└─ ui/MainActivity.java    – obrazovka a propojení všeho
```

## Možné další kroky
- Export do `.xlsx` (knihovny POI/jxl jsou už přibalené).
- Nastavení (uložení access pwd, výchozí rok, výkon).
- Kontrolní zpětné přečtení EPC po zápisu.
