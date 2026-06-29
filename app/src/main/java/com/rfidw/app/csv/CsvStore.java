package com.rfidw.app.csv;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Výstupní tabulka .CSV.
 *
 * Sloupce:
 *   ID_RFID ; EPC ; TID ; rok ; TUDU ; vyhybka ; cip
 *
 * Klíčem je ID_RFID – při zápisu stejného ID_RFID se daný řádek přepíše.
 */
public class CsvStore {

    public static final String[] HEADER = {
            "ID_RFID", "EPC", "TID", "rok", "TUDU", "vyhybka", "cip"
    };
    private static final String SEP = ";";

    public static class Row {
        public String idRfid;
        public String epc;
        public String tid;
        public String rok;
        public String tudu;
        public String vyhybka;
        public String cast;

        public String[] toArray() {
            return new String[]{ idRfid, epc, tid, rok, tudu, vyhybka, cast };
        }
    }

    private final File file;
    // zachovává pořadí vložení, klíč = ID_RFID
    private final Map<String, Row> rows = new LinkedHashMap<>();
    // rychlý index: TUDU|výhybka → bitmaska zapsaných částí (bit N = část N+1)
    private final Map<String, Integer> castsByVyhybka = new LinkedHashMap<>();

    public CsvStore(File file) {
        this.file = file;
        load();
    }

    public File getFile() { return file; }

    public List<Row> getRows() {
        return new ArrayList<>(rows.values());
    }

    public int size() { return rows.size(); }

    /** Vrátí poslední vložený řádek nebo null, pokud je tabulka prázdná. */
    public Row getLastRow() {
        if (rows.isEmpty()) return null;
        List<Row> list = getRows();
        return list.get(list.size() - 1);
    }

    /** Vrátí nejvyšší hodnotu ID_RFID v tabulce, nebo 0 pokud je tabulka prázdná. */
    public synchronized long getMaxIdRfid() {
        long max = 0;
        for (Row r : rows.values()) {
            long id = parseLong(r.idRfid, 0);
            if (id > max) max = id;
        }
        return max;
    }

    /** Vloží nebo přepíše řádek podle ID_RFID (jen v paměti). */
    public synchronized void upsert(Row row) {
        Row previous = rows.get(row.idRfid);
        if (previous != null) removeFromCastIndex(previous);
        rows.put(row.idRfid, row);
        addToCastIndex(row);
    }

    public synchronized void clear() {
        rows.clear();
        castsByVyhybka.clear();
    }

    /** Vrátí posledních {@code max} vložených řádků (chronologicky od nejstaršího). */
    public List<Row> getLastRows(int max) {
        List<Row> all = getRows();
        if (max <= 0 || all.isEmpty()) return new ArrayList<>();
        int from = Math.max(0, all.size() - max);
        return new ArrayList<>(all.subList(from, all.size()));
    }

    /** Odstraní poslední vložený řádek (jen v paměti). Vrátí smazaný řádek nebo null. */
    public synchronized Row removeLast() {
        if (rows.isEmpty()) return null;
        List<Row> list = getRows();
        Row last = list.get(list.size() - 1);
        rows.remove(last.idRfid);
        removeFromCastIndex(last);
        return last;
    }

    /** Počet zapsaných částí v rozmezí. */
    public synchronized int countWrittenCastsInRange(String tuduCode, int vyhybkaCislo,
            int castMin, int castMax) {
        int mask = castsMask(tuduCode, vyhybkaCislo);
        if (mask == 0) return 0;
        int count = 0;
        for (int c = castMin; c <= castMax; c++) {
            if (hasCast(mask, c)) count++;
        }
        return count;
    }

    /** True pokud jsou v CSV všechny části výhybky v daném rozmezí. */
    public synchronized boolean isVyhybkaComplete(String tuduCode, int vyhybkaCislo,
            int castMin, int castMax) {
        if (castMin > castMax) return true;
        int mask = castsMask(tuduCode, vyhybkaCislo);
        for (int c = castMin; c <= castMax; c++) {
            if (!hasCast(mask, c)) return false;
        }
        return true;
    }

    /** První chybějící část v rozmezí, nebo castMin pokud je vše zapsáno. */
    public synchronized int firstMissingCast(String tuduCode, int vyhybkaCislo,
            int castMin, int castMax) {
        int mask = castsMask(tuduCode, vyhybkaCislo);
        for (int c = castMin; c <= castMax; c++) {
            if (!hasCast(mask, c)) return c;
        }
        return castMin;
    }

    /** Uloží aktuální stav na disk. Volat mimo UI vlákno. */
    public synchronized void persist() {
        save();
    }

    // ----------------------------------------------------------- IO

    private void load() {
        rows.clear();
        castsByVyhybka.clear();
        if (file == null || !file.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] c = line.split(SEP, -1);
                if (first) {
                    first = false;
                    if (c.length > 0 && c[0].trim().equalsIgnoreCase("ID_RFID")) {
                        continue; // hlavička
                    }
                }
                Row r = new Row();
                r.idRfid  = get(c, 0);
                r.epc     = get(c, 1);
                r.tid     = get(c, 2);
                r.rok     = get(c, 3);
                r.tudu    = get(c, 4);
                r.vyhybka = get(c, 5);
                r.cast    = get(c, 6);
                if (r.idRfid != null && !r.idRfid.isEmpty()) {
                    rows.put(r.idRfid, r);
                    addToCastIndex(r);
                }
            }
        } catch (Exception e) {
            // poškozený soubor – začneme s prázdnou tabulkou
            rows.clear();
            castsByVyhybka.clear();
        }
    }

    private void save() {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (Writer w = new OutputStreamWriter(
                    new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
                w.write(join(HEADER));
                w.write("\n");
                for (Row r : rows.values()) {
                    w.write(join(r.toArray()));
                    w.write("\n");
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Nepodařilo se uložit CSV: " + e.getMessage(), e);
        }
    }

    private static String normalizeTudu(String tuduCode) {
        if (tuduCode == null) return "";
        return tuduCode.trim().toUpperCase(Locale.ROOT);
    }

    private static String vyhybkaKey(String tuduCode, int vyhybkaCislo) {
        return normalizeTudu(tuduCode) + '\0' + vyhybkaCislo;
    }

    private int castsMask(String tuduCode, int vyhybkaCislo) {
        Integer mask = castsByVyhybka.get(vyhybkaKey(tuduCode, vyhybkaCislo));
        return mask == null ? 0 : mask;
    }

    private static boolean hasCast(int mask, int cast) {
        if (cast < 1 || cast > 31) return false;
        return (mask & (1 << (cast - 1))) != 0;
    }

    private static int castBit(int cast) {
        if (cast < 1 || cast > 31) return 0;
        return 1 << (cast - 1);
    }

    private void addToCastIndex(Row row) {
        int cast = parseInt(row.cast, -1);
        int vyhybka = parseInt(row.vyhybka, -1);
        if (row.tudu == null || row.tudu.isEmpty() || vyhybka < 0 || cast < 0) return;
        String key = vyhybkaKey(row.tudu, vyhybka);
        int bit = castBit(cast);
        if (bit == 0) return;
        castsByVyhybka.merge(key, bit, (a, b) -> a | b);
    }

    private void removeFromCastIndex(Row row) {
        int cast = parseInt(row.cast, -1);
        int vyhybka = parseInt(row.vyhybka, -1);
        if (row.tudu == null || row.tudu.isEmpty() || vyhybka < 0 || cast < 0) return;
        String key = vyhybkaKey(row.tudu, vyhybka);
        Integer mask = castsByVyhybka.get(key);
        if (mask == null) return;
        int next = mask & ~castBit(cast);
        if (next == 0) castsByVyhybka.remove(key);
        else castsByVyhybka.put(key, next);
    }

    private static String get(String[] arr, int i) {
        return i < arr.length ? arr[i].trim() : "";
    }

    /** Rychlé parsování celého čísla bez regexu. */
    static int parseInt(String s, int def) {
        if (s == null) return def;
        int len = s.length();
        int i = 0;
        while (i < len && s.charAt(i) <= ' ') i++;
        if (i >= len) return def;

        boolean neg = false;
        if (s.charAt(i) == '-') {
            neg = true;
            i++;
            if (i >= len) return def;
        }

        long val = 0;
        boolean any = false;
        while (i < len) {
            char c = s.charAt(i++);
            if (c < '0' || c > '9') {
                if (!any) return def;
                break;
            }
            any = true;
            val = val * 10 + (c - '0');
            if (val > Integer.MAX_VALUE) return def;
        }
        if (!any) return def;
        int out = (int) val;
        return neg ? -out : out;
    }

    static long parseLong(String s, long def) {
        if (s == null) return def;
        int len = s.length();
        int i = 0;
        while (i < len && s.charAt(i) <= ' ') i++;
        if (i >= len) return def;

        boolean neg = false;
        if (s.charAt(i) == '-') {
            neg = true;
            i++;
            if (i >= len) return def;
        }

        long val = 0;
        boolean any = false;
        while (i < len) {
            char c = s.charAt(i++);
            if (c < '0' || c > '9') {
                if (!any) return def;
                break;
            }
            any = true;
            val = val * 10 + (c - '0');
            if (val < 0) return def;
        }
        if (!any) return def;
        return neg ? -val : val;
    }

    private static String join(String[] cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) sb.append(SEP);
            sb.append(escape(cols[i]));
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        // nahradíme oddělovač/nové řádky, ať se tabulka nerozbije
        return s.replace(SEP, " ").replace("\n", " ").replace("\r", " ");
    }
}
