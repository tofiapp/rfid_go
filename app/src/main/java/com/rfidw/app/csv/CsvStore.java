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
import java.util.Map;

/**
 * Výstupní tabulka .CSV.
 *
 * Sloupce:
 *   ID_RFID ; EPC ; TID ; Rok ; TUDU ; Vyhybka ; CastVyhybky
 *
 * Klíčem je ID_RFID – při zápisu stejného ID_RFID se daný řádek přepíše.
 */
public class CsvStore {

    public static final String[] HEADER = {
            "ID_RFID", "EPC", "TID", "Rok", "TUDU", "Vyhybka", "CastVyhybky"
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

    public CsvStore(File file) {
        this.file = file;
        load();
    }

    public File getFile() { return file; }

    public List<Row> getRows() {
        return new ArrayList<>(rows.values());
    }

    public int size() { return rows.size(); }

    /** Vloží nebo přepíše řádek podle ID_RFID a uloží na disk. */
    public synchronized void upsert(Row row) {
        rows.put(row.idRfid, row);
        save();
    }

    public synchronized void clear() {
        rows.clear();
        save();
    }

    // ----------------------------------------------------------- IO

    private void load() {
        rows.clear();
        if (file == null || !file.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
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
                }
            }
        } catch (Exception e) {
            // poškozený soubor – začneme s prázdnou tabulkou
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

    private static String get(String[] arr, int i) {
        return i < arr.length ? arr[i].trim() : "";
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
