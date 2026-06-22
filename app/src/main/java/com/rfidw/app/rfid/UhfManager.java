package com.rfidw.app.rfid;

import android.content.Context;

import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.UHFTAGInfo;

/**
 * Obal nad Chainway / RSCJA UHF čtečkou (vestavěný UART modul – platí pro C5).
 *
 * Banky paměti UHF tagu (Gen2):
 *   0 = RESERVED (kill/access pwd), 1 = EPC, 2 = TID, 3 = USER
 */
public class UhfManager {

    public static final int BANK_RESERVED = 0;
    public static final int BANK_EPC      = 1;
    public static final int BANK_TID      = 2;
    public static final int BANK_USER     = 3;

    // EPC: ptr=2 (přeskočí CRC+PC), Len=6 wordů = 96 bitů = 24 hex znaků
    public static final int EPC_PTR = 2;
    public static final int EPC_LEN = 6;

    private RFIDWithUHFUART reader;
    private boolean ready = false;

    public boolean isReady() { return ready; }

    /** Inicializace čtečky. Volat z UI vlákna při startu. */
    public synchronized boolean init(Context ctx) {
        try {
            if (reader == null) {
                reader = RFIDWithUHFUART.getInstance();
            }
            ready = reader.init(ctx.getApplicationContext());
            return ready;
        } catch (Exception e) {
            ready = false;
            return false;
        }
    }

    public synchronized void free() {
        try {
            if (reader != null) reader.free();
        } catch (Exception ignored) {
        } finally {
            ready = false;
        }
    }

    public int getPower() {
        try { return reader != null ? reader.getPower() : -1; }
        catch (Exception e) { return -1; }
    }

    public boolean setPower(int dbm) {
        try { return reader != null && reader.setPower(dbm); }
        catch (Exception e) { return false; }
    }

    /** Výsledek operace zápisu. */
    public static class WriteResult {
        public boolean success;
        public String oldEpc;     // EPC před přepisem (pokud byl tag přečten)
        public String tid;        // TID přečteného tagu
        public String message;
    }

    /**
     * Přečte tag v dosahu (EPC + TID) a přepíše jeho EPC na nový.
     *
     * @param accessPwd 8 hex znaků (default "00000000")
     * @param newEpc    nový EPC, 24 hex znaků
     */
    public synchronized WriteResult writeEpc(String accessPwd, String newEpc) {
        WriteResult r = new WriteResult();
        if (!ready || reader == null) {
            r.message = "Čtečka není připravena.";
            return r;
        }
        if (accessPwd == null || accessPwd.isEmpty()) accessPwd = "00000000";
        if (newEpc == null || newEpc.length() != 24) {
            r.message = "EPC musí mít 24 znaků.";
            return r;
        }

        try {
            // 1) přečíst aktuální tag (EPC + TID)
            UHFTAGInfo info = reader.inventorySingleTag();
            if (info != null) {
                r.oldEpc = info.getEPC();
                r.tid = info.getTid();
            }
            // pokud TID nepřišel z inventáře, dočti ho přímo
            if (r.tid == null || r.tid.isEmpty()) {
                try {
                    String tid = reader.readData(accessPwd, BANK_TID, 0, EPC_LEN);
                    if (tid != null) r.tid = tid;
                } catch (Exception ignored) { }
            }

            // 2) zapsat nový EPC: bank EPC, ptr 2, len 6
            boolean ok = reader.writeData(accessPwd, BANK_EPC, EPC_PTR, EPC_LEN, newEpc);
            r.success = ok;
            r.message = ok ? "EPC zapsáno." : "Zápis EPC se nezdařil (tag mimo dosah / špatné heslo).";
            return r;
        } catch (Exception e) {
            r.success = false;
            r.message = "Chyba zápisu: " + e.getMessage();
            return r;
        }
    }

    /** Jednorázové přečtení tagu (EPC + TID), např. pro kontrolu. */
    public synchronized UHFTAGInfo readSingle() {
        try {
            return reader != null ? reader.inventorySingleTag() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
