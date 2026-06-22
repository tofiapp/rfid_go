package com.rfidw.app.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.rfidw.app.R;
import com.rfidw.app.csv.CsvStore;
import com.rfidw.app.data.Tudu;
import com.rfidw.app.data.TuduLoader;
import com.rfidw.app.epc.EpcModel;
import com.rfidw.app.rfid.UhfManager;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // klávesy spouště čtečky (Chainway C5 a příbuzné)
    private static final int[] TRIGGER_KEYS = {139, 280, 293, 311, 312, 522, 523, 0x3E8};

    private enum TriggerMode { EPC, PASSWORD, LOCK }

    private final UhfManager uhf = new UhfManager();
    private final EpcModel epc = new EpcModel();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private List<Tudu> tuduList = new ArrayList<>();
    private Tudu currentTudu;
    private Tudu.Vyhybka currentVyhybka;

    private CsvStore csvStore;
    private CsvAdapter csvAdapter;
    private SharedPreferences prefs;

    private TriggerMode triggerMode = TriggerMode.EPC;

    // view reference
    private TextView tvReaderStatus, tvEpcPreview, tvEpcValid, tvSourceFile,
            tvVyhybkaInfo, tvWriteResult, tvCsvPath, tvPwdWriteResult, tvLockResult;
    private Spinner spTudu, spVyhybka;
    private EditText etAccessPwd, etPower, etPwdAccess, etPwdNew, etLockAccessPwd;
    private CheckBox cbAutoCsv;
    private ChipGroup chipTriggerMode;

    // řádky šablony (kontejnery z include)
    private View[] rows = new View[7];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("rfidgo", MODE_PRIVATE);

        bindViews();
        setupCollapsibles();
        setupTriggerMode();
        setupTemplateRows();
        setupCsv();
        setupListeners();

        epc.idRfid = prefs.getLong("idRfid", 1);
        refreshTemplate();

        initReaderAsync();
    }

    private void bindViews() {
        tvReaderStatus = findViewById(R.id.tvReaderStatus);
        tvEpcPreview = findViewById(R.id.tvEpcPreview);
        tvEpcValid = findViewById(R.id.tvEpcValid);
        tvSourceFile = findViewById(R.id.tvSourceFile);
        tvVyhybkaInfo = findViewById(R.id.tvVyhybkaInfo);
        tvWriteResult = findViewById(R.id.tvWriteResult);
        tvCsvPath = findViewById(R.id.tvCsvPath);
        tvPwdWriteResult = findViewById(R.id.tvPwdWriteResult);
        tvLockResult = findViewById(R.id.tvLockResult);
        spTudu = findViewById(R.id.spTudu);
        spVyhybka = findViewById(R.id.spVyhybka);
        etAccessPwd = findViewById(R.id.etAccessPwd);
        etPower = findViewById(R.id.etPower);
        etPwdAccess = findViewById(R.id.etPwdAccess);
        etPwdNew = findViewById(R.id.etPwdNew);
        etLockAccessPwd = findViewById(R.id.etLockAccessPwd);
        cbAutoCsv = findViewById(R.id.cbAutoCsv);
        chipTriggerMode = findViewById(R.id.chipTriggerMode);

        rows[0] = findViewById(R.id.row1);
        rows[1] = findViewById(R.id.row2);
        rows[2] = findViewById(R.id.row3);
        rows[3] = findViewById(R.id.row4);
        rows[4] = findViewById(R.id.row5);
        rows[5] = findViewById(R.id.row6);
        rows[6] = findViewById(R.id.row7);
    }

    // ---------- rozbalovací karty ----------

    private void setupCollapsibles() {
        toggle(R.id.header1, R.id.body1);
        toggle(R.id.header2, R.id.body2);
        toggle(R.id.header3, R.id.body3);
        toggle(R.id.header4, R.id.body4);
        toggle(R.id.header5, R.id.body5);
    }

    private void toggle(int headerId, int bodyId) {
        TextView header = findViewById(headerId);
        View body = findViewById(bodyId);
        header.setOnClickListener(v -> {
            boolean vis = body.getVisibility() == View.VISIBLE;
            body.setVisibility(vis ? View.GONE : View.VISIBLE);
            String t = header.getText().toString();
            header.setText((vis ? "▸" : "▾") + t.substring(1));
        });
    }

    private void setupTriggerMode() {
        String saved = prefs.getString("triggerMode", TriggerMode.EPC.name());
        try {
            triggerMode = TriggerMode.valueOf(saved);
        } catch (Exception ignored) {
            triggerMode = TriggerMode.EPC;
        }
        selectTriggerChip(triggerMode);

        chipTriggerMode.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chipTriggerPwd) {
                triggerMode = TriggerMode.PASSWORD;
            } else if (id == R.id.chipTriggerLock) {
                triggerMode = TriggerMode.LOCK;
            } else {
                triggerMode = TriggerMode.EPC;
            }
            prefs.edit().putString("triggerMode", triggerMode.name()).apply();
        });
    }

    private void selectTriggerChip(TriggerMode mode) {
        int chipId = R.id.chipTriggerEpc;
        if (mode == TriggerMode.PASSWORD) chipId = R.id.chipTriggerPwd;
        else if (mode == TriggerMode.LOCK) chipId = R.id.chipTriggerLock;
        Chip chip = findViewById(chipId);
        if (chip != null) chip.setChecked(true);
    }

    // ---------- šablona EPC ----------

    private void setupTemplateRows() {
        String[] idx = {"1", "2", "3", "4", "5", "6", "7"};
        String[] names = {
                epc.nameYear, epc.nameTudu14, epc.nameTudu5, epc.nameTudu6,
                epc.nameVyhybka, epc.nameCast, epc.nameIdRfid
        };
        for (int i = 0; i < 7; i++) {
            View row = rows[i];
            ((TextView) row.findViewById(R.id.tvIdx)).setText(idx[i]);
            EditText etName = row.findViewById(R.id.etName);
            etName.setText(names[i]);
            EditText etVal = row.findViewById(R.id.etValue);

            boolean editableValue = (i == 0 || i == 5 || i == 6);
            etVal.setFocusable(editableValue);
            etVal.setFocusableInTouchMode(editableValue);
            etVal.setClickable(editableValue);
            if (i == 0) etVal.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            if (i == 5 || i == 6) etVal.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        }

        valueWatcher(0, s -> { epc.year = s; });
        valueWatcher(5, s -> { epc.cast = parseInt(s, epc.cast); });
        valueWatcher(6, s -> { epc.idRfid = parseLong(s, epc.idRfid); });

        nameWatcher(0, s -> epc.nameYear = s);
        nameWatcher(1, s -> epc.nameTudu14 = s);
        nameWatcher(2, s -> epc.nameTudu5 = s);
        nameWatcher(3, s -> epc.nameTudu6 = s);
        nameWatcher(4, s -> epc.nameVyhybka = s);
        nameWatcher(5, s -> epc.nameCast = s);
        nameWatcher(6, s -> epc.nameIdRfid = s);
    }

    private interface StrCb { void on(String s); }

    private void valueWatcher(int rowIdx, StrCb cb) {
        EditText et = rows[rowIdx].findViewById(R.id.etValue);
        et.addTextChangedListener(new SimpleWatcher(() -> {
            cb.on(et.getText().toString().trim());
            refreshHexAndPreview();
        }));
    }

    private void nameWatcher(int rowIdx, StrCb cb) {
        EditText et = rows[rowIdx].findViewById(R.id.etName);
        et.addTextChangedListener(new SimpleWatcher(() ->
                cb.on(et.getText().toString().trim())));
    }

    private void refreshTemplate() {
        setValue(0, epc.year);
        setValue(1, epc.f2Tudu14());
        setValue(2, tuduCharOr(4));
        setValue(3, tuduCharOr(5));
        setValue(4, String.valueOf(epc.vyhybka));
        setValue(5, String.valueOf(epc.cast));
        setValue(6, String.valueOf(epc.idRfid));
        refreshHexAndPreview();
    }

    private String tuduCharOr(int idx) {
        String t = epc.tudu == null ? "" : epc.tudu;
        return t.length() > idx ? String.valueOf(t.charAt(idx)) : "-";
    }

    private void setValue(int rowIdx, String v) {
        EditText et = rows[rowIdx].findViewById(R.id.etValue);
        if (!et.getText().toString().equals(v)) et.setText(v);
    }

    private void refreshHexAndPreview() {
        String[] hex = {
                epc.f1Year(), epc.f2Tudu14(), epc.f3Tudu5(), epc.f4Tudu6(),
                epc.f5Vyhybka(), epc.f6Cast(), epc.f7IdRfid()
        };
        for (int i = 0; i < 7; i++) {
            ((TextView) rows[i].findViewById(R.id.tvHex)).setText(hex[i]);
        }
        tvEpcPreview.setText(epc.buildEpcPreview());
        if (epc.isValid()) {
            tvEpcValid.setText("✓ EPC validní (24 hex znaků)");
            tvEpcValid.setTextColor(0xFF2E7D32);
        } else {
            tvEpcValid.setText("✗ EPC není validní – zkontrolujte hodnoty");
            tvEpcValid.setTextColor(0xFFC62828);
        }
    }

    // ---------- CSV ----------

    private void setupCsv() {
        File out = new File(getExternalFilesDir(null), "rfid_go_output.csv");
        csvStore = new CsvStore(out);
        tvCsvPath.setText(out.getAbsolutePath());

        csvAdapter = new CsvAdapter();
        RecyclerView rv = findViewById(R.id.rvCsv);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(csvAdapter);
        csvAdapter.setData(csvStore.getRows());
    }

    // ---------- listenery ----------

    private void setupListeners() {
        findViewById(R.id.btnPickSource).setOnClickListener(v -> pickSourceFile());

        spTudu.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= 0 && pos < tuduList.size()) selectTudu(tuduList.get(pos));
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });

        spVyhybka.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (currentTudu != null && pos >= 0 && pos < currentTudu.vyhybky.size()) {
                    selectVyhybka(currentTudu.vyhybky.get(pos), true);
                }
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });

        findViewById(R.id.btnApplyPower).setOnClickListener(v -> applyPower());
        findViewById(R.id.btnWrite).setOnClickListener(v -> {
            triggerMode = TriggerMode.EPC;
            selectTriggerChip(triggerMode);
            doWrite();
        });
        findViewById(R.id.btnWritePwd).setOnClickListener(v -> {
            triggerMode = TriggerMode.PASSWORD;
            selectTriggerChip(triggerMode);
            doWritePassword();
        });
        findViewById(R.id.btnLock).setOnClickListener(v -> {
            triggerMode = TriggerMode.LOCK;
            selectTriggerChip(triggerMode);
            doLock();
        });
        findViewById(R.id.btnExportCsv).setOnClickListener(v -> exportCsv());
        findViewById(R.id.btnClearCsv).setOnClickListener(v -> {
            csvStore.clear();
            csvAdapter.setData(csvStore.getRows());
            toast("Tabulka vymazána");
        });
    }

    // ---------- výběr souboru / TUDU ----------

    private final androidx.activity.result.ActivityResultLauncher<Intent> picker =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            Uri uri = result.getData().getData();
                            if (uri != null) loadSource(uri);
                        }
                    });

    private void pickSourceFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        picker.launch(i);
    }

    private void loadSource(Uri uri) {
        String name = queryName(uri);
        tvSourceFile.setText("Načítám: " + name);
        io.execute(() -> {
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                List<Tudu> loaded = TuduLoader.load(in, name);
                ui.post(() -> {
                    tuduList = loaded;
                    tvSourceFile.setText(name + "  •  TUDU: " + loaded.size());
                    fillTuduSpinner();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    tvSourceFile.setText("Chyba načtení: " + e.getMessage());
                    toast("Chyba načtení souboru");
                });
            }
        });
    }

    private void fillTuduSpinner() {
        List<String> labels = new ArrayList<>();
        for (Tudu t : tuduList) labels.add(t.code);
        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels);
        spTudu.setAdapter(a);
        if (!tuduList.isEmpty()) selectTudu(tuduList.get(0));
    }

    private void selectTudu(Tudu t) {
        currentTudu = t;
        epc.tudu = t.code;
        List<String> labels = new ArrayList<>();
        for (Tudu.Vyhybka v : t.vyhybky) labels.add("Výhybka " + v.cislo);
        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels);
        spVyhybka.setAdapter(a);
        if (!t.vyhybky.isEmpty()) {
            selectVyhybka(t.vyhybky.get(0), true);
        } else {
            currentVyhybka = null;
            tvVyhybkaInfo.setText("TUDU nemá definované výhybky – zadejte výhybku a část ručně.");
        }
        refreshTemplate();
    }

    private void selectVyhybka(Tudu.Vyhybka v, boolean resetCast) {
        currentVyhybka = v;
        epc.vyhybka = v.cislo;
        if (resetCast) epc.cast = v.castMin;
        tvVyhybkaInfo.setText("Výhybka " + v.cislo + " • části " + v.castMin + "–" + v.castMax
                + "  (TUDU " + (currentTudu != null ? currentTudu.code : "-") + ")");
        refreshTemplate();
    }

    // ---------- zápis EPC ----------

    private void applyPower() {
        int p = parseInt(etPower.getText().toString().trim(), 30);
        io.execute(() -> {
            boolean ok = uhf.setPower(p);
            ui.post(() -> toast(ok ? ("Výkon nastaven na " + p + " dBm") : "Nastavení výkonu selhalo"));
        });
    }

    private void doWrite() {
        if (!epc.isValid()) {
            toast("EPC není validní");
            return;
        }
        if (!uhf.isReady()) {
            toast("Čtečka není připravena");
            return;
        }
        final String pwd = etAccessPwd.getText().toString().trim();
        final String newEpc = epc.buildEpc();
        tvWriteResult.setText("Zapisuji…");
        tvWriteResult.setTextColor(0xFF5F6A76);

        io.execute(() -> {
            UhfManager.WriteResult r = uhf.writeEpc(pwd, newEpc);
            ui.post(() -> onWriteDone(r, newEpc));
        });
    }

    private void onWriteDone(UhfManager.WriteResult r, String writtenEpc) {
        if (r.success) {
            tvWriteResult.setTextColor(0xFF2E7D32);
            tvWriteResult.setText("✓ " + r.message
                    + (r.oldEpc != null ? ("\nPůvodní EPC: " + r.oldEpc) : "")
                    + (r.tid != null ? ("\nTID: " + r.tid) : ""));

            if (cbAutoCsv.isChecked()) saveRowToCsv(writtenEpc, r.tid);

            advanceAfterWrite();
        } else {
            tvWriteResult.setTextColor(0xFFC62828);
            tvWriteResult.setText("✗ " + r.message);
        }
    }

    // ---------- zápis access hesla ----------

    private void doWritePassword() {
        if (!uhf.isReady()) {
            toast("Čtečka není připravena");
            return;
        }
        final String accessPwd = etPwdAccess.getText().toString().trim();
        final String newPwd = etPwdNew.getText().toString().trim();
        if (!newPwd.matches("[0-9A-Fa-f]{8}")) {
            toast("NEW PWD musí mít 8 hex znaků");
            return;
        }
        tvPwdWriteResult.setText("Zapisuji heslo…");
        tvPwdWriteResult.setTextColor(0xFF5F6A76);

        io.execute(() -> {
            UhfManager.WriteResult r = uhf.writeAccessPassword(accessPwd, newPwd);
            ui.post(() -> onPwdWriteDone(r));
        });
    }

    private void onPwdWriteDone(UhfManager.WriteResult r) {
        if (r.success) {
            tvPwdWriteResult.setTextColor(0xFF2E7D32);
            tvPwdWriteResult.setText("✓ " + r.message
                    + (r.oldEpc != null ? ("\nEPC: " + r.oldEpc) : "")
                    + (r.tid != null ? ("\nTID: " + r.tid) : ""));
            etLockAccessPwd.setText(etPwdNew.getText().toString().trim().toUpperCase());
        } else {
            tvPwdWriteResult.setTextColor(0xFFC62828);
            tvPwdWriteResult.setText("✗ " + r.message);
        }
    }

    // ---------- zamčení tagu ----------

    private void doLock() {
        if (!uhf.isReady()) {
            toast("Čtečka není připravena");
            return;
        }
        final String accessPwd = etLockAccessPwd.getText().toString().trim();
        final String lockCode = getString(R.string.lock_code_value);
        tvLockResult.setText("Zamykám…");
        tvLockResult.setTextColor(0xFF5F6A76);

        io.execute(() -> {
            UhfManager.WriteResult r = uhf.lockTag(accessPwd, lockCode);
            ui.post(() -> onLockDone(r));
        });
    }

    private void onLockDone(UhfManager.WriteResult r) {
        if (r.success) {
            tvLockResult.setTextColor(0xFF2E7D32);
            tvLockResult.setText("✓ " + r.message
                    + (r.oldEpc != null ? ("\nEPC: " + r.oldEpc) : "")
                    + (r.tid != null ? ("\nTID: " + r.tid) : ""));
        } else {
            tvLockResult.setTextColor(0xFFC62828);
            tvLockResult.setText("✗ " + r.message);
        }
    }

    private void saveRowToCsv(String epc24, String tid) {
        try {
            EpcModel.Decoded d = EpcModel.decode(epc24);
            CsvStore.Row row = new CsvStore.Row();
            row.idRfid = d.idRfid;
            row.epc = d.epc;
            row.tid = tid == null ? "" : tid;
            row.rok = d.rok;
            row.tudu = d.tudu;
            row.vyhybka = d.vyhybka;
            row.cast = d.cast;
            csvStore.upsert(row);
            csvAdapter.setData(csvStore.getRows());
        } catch (Exception e) {
            toast("CSV: " + e.getMessage());
        }
    }

    private void advanceAfterWrite() {
        epc.idRfid += 1;
        prefs.edit().putLong("idRfid", epc.idRfid).apply();

        if (currentVyhybka != null) {
            int next = epc.cast + 1;
            if (next > currentVyhybka.castMax) {
                epc.cast = currentVyhybka.castMin;
                advanceToNextVyhybka();
            } else {
                epc.cast = next;
            }
        } else {
            epc.cast += 1;
        }
        refreshTemplate();
    }

    private void advanceToNextVyhybka() {
        if (currentTudu == null || currentTudu.vyhybky.isEmpty()) return;
        int idx = currentTudu.vyhybky.indexOf(currentVyhybka);
        if (idx >= 0 && idx + 1 < currentTudu.vyhybky.size()) {
            spVyhybka.setSelection(idx + 1);
        } else {
            toast("Poslední výhybka v TUDU – cyklus dokončen.");
        }
    }

    // ---------- export CSV ----------

    private void exportCsv() {
        try {
            File f = csvStore.getFile();
            if (!f.exists() || csvStore.size() == 0) {
                toast("Tabulka je prázdná");
                return;
            }
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", f);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/csv");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Sdílet CSV"));
        } catch (Exception e) {
            toast("Export selhal: " + e.getMessage());
        }
    }

    // ---------- čtečka ----------

    private void initReaderAsync() {
        tvReaderStatus.setText("Čtečka: inicializuji…");
        io.execute(() -> {
            boolean ok = uhf.init(this);
            int power = ok ? uhf.getPower() : -1;
            ui.post(() -> {
                if (ok) {
                    tvReaderStatus.setText("Čtečka: připravena" + (power > 0 ? (" • " + power + " dBm") : ""));
                    tvReaderStatus.setTextColor(0xFF2E7D32);
                    if (power > 0) etPower.setText(String.valueOf(power));
                } else {
                    tvReaderStatus.setText("Čtečka: NEDOSTUPNÁ");
                    tvReaderStatus.setTextColor(0xFFC62828);
                }
            });
        });
    }

    private void runTriggerAction() {
        switch (triggerMode) {
            case PASSWORD:
                doWritePassword();
                break;
            case LOCK:
                doLock();
                break;
            default:
                doWrite();
                break;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() == 0) {
            for (int k : TRIGGER_KEYS) {
                if (k == keyCode) {
                    runTriggerAction();
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.execute(uhf::free);
    }

    // ---------- pomocné ----------

    private String queryName(Uri uri) {
        String name = "soubor";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) { }
        return name;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.replaceAll("[^0-9-]", "")); } catch (Exception e) { return def; }
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s.replaceAll("[^0-9-]", "")); } catch (Exception e) { return def; }
    }

    static class SimpleWatcher implements TextWatcher {
        private final Runnable r;
        SimpleWatcher(Runnable r) { this.r = r; }
        public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        public void onTextChanged(CharSequence s, int a, int b, int c) { }
        public void afterTextChanged(Editable s) { r.run(); }
    }
}
