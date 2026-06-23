package com.rfidw.app.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

import com.rfidw.app.R;
import com.rfidw.app.csv.CsvStore;
import com.rfidw.app.data.Tudu;
import com.rfidw.app.data.TuduLoader;
import com.rfidw.app.epc.EpcModel;
import com.rfidw.app.rfid.UhfManager;

import java.io.File;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // klávesy spouště čtečky (Chainway C5 a příbuzné)
    private static final int[] TRIGGER_KEYS = {139, 280, 293, 311, 312, 522, 523, 0x3E8};

    private static final int COLOR_STATUS_READY = 0xFF2E7D32;
    private static final int COLOR_STATUS_BUSY = 0xFF5F6A76;
    private static final int COLOR_STATUS_ERROR = 0xFFC62828;
    private static final int WORKFLOW_DONE_DELAY_MS = 1500;

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

    private boolean step1Done, step2Done, step3Done;
    private boolean workflowRunning, chainWorkflow;
    private int activeStep;

    // view reference
    private TextView tvReaderStatus, tvEpcPreview, tvEpcValid, tvSourceFile,
            tvWriteResult, tvCsvPath, tvPwdWriteResult, tvLockResult,
            tvSummaryTudu, tvSummaryVyhybka, tvSummaryCast,
            tvCastHintAction, tvCastHintPart,
            step1Circle, step2Circle, step3Circle;
    private View summary1, colSummaryTudu, colSummaryVyhybka, castHintBox;
    private BottomSheetBehavior<View> workflowBehavior;
    private EditText etAccessPwd, etPower, etPwdAccess, etPwdNew, etLockAccessPwd;
    private CheckBox cbAutoCsv;

    // řádky šablony (kontejnery z include)
    private View[] rows = new View[7];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("rfidgo", MODE_PRIVATE);

        bindViews();
        setupWorkflowSheet();
        setupCollapsibles();
        setupTemplateRows();
        setupCsv();
        setupListeners();

        epc.idRfid = prefs.getLong("idRfid", 1);
        refreshTemplate();
        updateSummary1();
        updateStepIndicators();

        initReaderAsync();
    }

    private void bindViews() {
        tvReaderStatus = findViewById(R.id.tvReaderStatus);
        tvEpcPreview = findViewById(R.id.tvEpcPreview);
        tvEpcValid = findViewById(R.id.tvEpcValid);
        tvSourceFile = findViewById(R.id.tvSourceFile);
        tvWriteResult = findViewById(R.id.tvWriteResult);
        tvCsvPath = findViewById(R.id.tvCsvPath);
        tvPwdWriteResult = findViewById(R.id.tvPwdWriteResult);
        tvLockResult = findViewById(R.id.tvLockResult);
        tvSummaryTudu = findViewById(R.id.tvSummaryTudu);
        tvSummaryVyhybka = findViewById(R.id.tvSummaryVyhybka);
        tvSummaryCast = findViewById(R.id.tvSummaryCast);
        castHintBox = findViewById(R.id.castHintBox);
        tvCastHintAction = findViewById(R.id.tvCastHintAction);
        tvCastHintPart = findViewById(R.id.tvCastHintPart);
        summary1 = findViewById(R.id.summary1);
        colSummaryTudu = findViewById(R.id.colSummaryTudu);
        colSummaryVyhybka = findViewById(R.id.colSummaryVyhybka);
        step1Circle = findViewById(R.id.step1Circle);
        step2Circle = findViewById(R.id.step2Circle);
        step3Circle = findViewById(R.id.step3Circle);
        etAccessPwd = findViewById(R.id.etAccessPwd);
        etPower = findViewById(R.id.etPower);
        etPwdAccess = findViewById(R.id.etPwdAccess);
        etPwdNew = findViewById(R.id.etPwdNew);
        etLockAccessPwd = findViewById(R.id.etLockAccessPwd);
        cbAutoCsv = findViewById(R.id.cbAutoCsv);

        rows[0] = findViewById(R.id.row1);
        rows[1] = findViewById(R.id.row2);
        rows[2] = findViewById(R.id.row3);
        rows[3] = findViewById(R.id.row4);
        rows[4] = findViewById(R.id.row5);
        rows[5] = findViewById(R.id.row6);
        rows[6] = findViewById(R.id.row7);
    }

    // ---------- rozbalovací karty a spodní panel ----------

    private void setupWorkflowSheet() {
        View sheet = findViewById(R.id.workflowSheet);
        workflowBehavior = BottomSheetBehavior.from(sheet);
        workflowBehavior.setHideable(false);
        workflowBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);

        findViewById(R.id.workflowSheetHandle).setOnClickListener(v -> {
            if (workflowBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
                workflowBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            } else {
                workflowBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });

        colSummaryTudu.setOnClickListener(v -> showTuduPicker());
        colSummaryVyhybka.setOnClickListener(v -> showVyhybkaPicker());
    }

    private void expandCard1Body() {
        View body = findViewById(R.id.body1);
        TextView header = findViewById(R.id.header1);
        body.setVisibility(View.VISIBLE);
        String t = header.getText().toString();
        if (t.startsWith("▸")) header.setText("▾" + t.substring(1));
    }

    private void showTuduPicker() {
        if (tuduList.isEmpty()) {
            toast("Nejdříve vyberte soubor se zdrojem dat");
            expandCard1Body();
            return;
        }

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_tudu_picker, null);
        EditText etSearch = dialogView.findViewById(R.id.etTuduSearch);
        ListView listView = dialogView.findViewById(R.id.lvTudu);

        List<String> filteredCodes = new ArrayList<>();
        for (Tudu t : tuduList) filteredCodes.add(t.code);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_single_choice, filteredCodes);
        listView.setAdapter(adapter);

        int checked = filteredCodes.indexOf(currentTudu != null ? currentTudu.code : "");
        if (checked >= 0) listView.setItemChecked(checked, true);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Vyberte TUDU")
                .setView(dialogView)
                .setNegativeButton("Zrušit", null)
                .create();

        listView.setOnItemClickListener((parent, v, position, id) -> {
            String code = filteredCodes.get(position);
            for (Tudu t : tuduList) {
                if (t.code.equals(code)) {
                    selectTudu(t);
                    break;
                }
            }
            dialog.dismiss();
        });

        etSearch.addTextChangedListener(new SimpleWatcher(() -> {
            String q = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
            filteredCodes.clear();
            for (Tudu t : tuduList) {
                if (q.isEmpty() || t.code.toLowerCase(Locale.ROOT).contains(q)) {
                    filteredCodes.add(t.code);
                }
            }
            adapter.notifyDataSetChanged();
            String selected = currentTudu != null ? currentTudu.code : "";
            int pos = filteredCodes.indexOf(selected);
            if (pos >= 0) listView.setItemChecked(pos, true);
        }));

        dialog.show();
        etSearch.requestFocus();
    }

    private void showVyhybkaPicker() {
        if (currentTudu == null || currentTudu.vyhybky.isEmpty()) {
            toast("TUDU nemá výhybky – vyberte soubor nebo TUDU");
            expandCard1Body();
            return;
        }
        final String tuduCode = currentTudu.code;
        final List<Tudu.Vyhybka> vyhybky = currentTudu.vyhybky;
        List<String> labels = new ArrayList<>();
        for (Tudu.Vyhybka v : vyhybky) {
            labels.add("Výhybka " + v.cislo);
        }

        ListView listView = new ListView(this);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_single_choice, labels) {
            @Override
            public boolean isEnabled(int position) {
                return !isVyhybkaCompleteInCsv(tuduCode, vyhybky.get(position));
            }

            @Override
            public android.view.View getView(int position, android.view.View convertView,
                    android.view.ViewGroup parent) {
                android.view.View view = super.getView(position, convertView, parent);
                TextView tv = (TextView) view;
                boolean done = isVyhybkaCompleteInCsv(tuduCode, vyhybky.get(position));
                if (done) {
                    tv.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.text_muted));
                    tv.setAlpha(0.45f);
                } else {
                    tv.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.text));
                    tv.setAlpha(1f);
                }
                return view;
            }
        };
        listView.setAdapter(adapter);

        int checked = currentVyhybka != null ? vyhybky.indexOf(currentVyhybka) : 0;
        if (checked < 0) checked = 0;
        listView.setItemChecked(checked, true);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Vyberte výhybku")
                .setView(listView)
                .setNegativeButton("Zrušit", null)
                .create();

        listView.setOnItemClickListener((parent, v, position, id) -> {
            if (isVyhybkaCompleteInCsv(tuduCode, vyhybky.get(position))) {
                toast("Výhybka je již zapsaná v CSV");
                return;
            }
            selectVyhybka(vyhybky.get(position), true);
            dialog.dismiss();
        });

        dialog.show();
    }

    private void setupCollapsibles() {
        toggle(R.id.header1, R.id.body1, 0);
        toggle(R.id.header2, R.id.body2, 0);
        toggle(R.id.header3, R.id.body3, 0);
        toggle(R.id.header4, R.id.body4, 0);
        toggle(R.id.header5, R.id.body5, 0);
    }

    private void toggle(int headerId, int bodyId, int summaryId) {
        TextView header = findViewById(headerId);
        View body = findViewById(bodyId);
        View summary = summaryId != 0 ? findViewById(summaryId) : null;
        header.setOnClickListener(v -> {
            boolean vis = body.getVisibility() == View.VISIBLE;
            body.setVisibility(vis ? View.GONE : View.VISIBLE);
            String t = header.getText().toString();
            header.setText((vis ? "▸" : "▾") + t.substring(1));
            if (summary != null) {
                summary.setVisibility(vis ? View.VISIBLE : View.GONE);
            }
        });
    }

    // ---------- indikátor kroků ----------

    private void updateStepIndicators() {
        setStepCircle(step1Circle, step1Done, activeStep == 1, "1");
        setStepCircle(step2Circle, step2Done, activeStep == 2, "2");
        setStepCircle(step3Circle, step3Done, activeStep == 3, "3");
    }

    private void setStepCircle(TextView circle, boolean done, boolean active, String number) {
        if (done) {
            circle.setText("✓");
            circle.setBackgroundResource(R.drawable.step_circle_done);
            circle.setTextColor(0xFFFFFFFF);
        } else if (active) {
            circle.setText(number);
            circle.setBackgroundResource(R.drawable.step_circle_active);
            circle.setTextColor(0xFFFFFFFF);
        } else {
            circle.setText(number);
            circle.setBackgroundResource(R.drawable.step_circle_pending);
            circle.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        }
    }

    private void updateStep1() {
        step1Done = currentTudu != null && currentVyhybka != null
                && epc.tudu != null && !epc.tudu.isEmpty();
        updateStepIndicators();
    }

    private void updateSummary1() {
        tvSummaryTudu.setText(epc.tudu == null || epc.tudu.isEmpty() ? "—" : epc.tudu);
        tvSummaryVyhybka.setText(epc.vyhybka > 0 ? String.valueOf(epc.vyhybka) : "—");
        if (epc.cast > 0) {
            int total = currentVyhybka != null
                    ? currentVyhybka.castMax - currentVyhybka.castMin + 1
                    : 3;
            String current = String.valueOf(epc.cast);
            String rest = "/" + total;
            SpannableString span = new SpannableString(current + rest);
            int accent = ContextCompat.getColor(this, R.color.accent);
            span.setSpan(new ForegroundColorSpan(accent), 0, current.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new StyleSpan(Typeface.BOLD), 0, current.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int muted = ContextCompat.getColor(this, R.color.text_muted);
            span.setSpan(new ForegroundColorSpan(muted), current.length(), span.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tvSummaryCast.setText(span);
        } else {
            tvSummaryCast.setText("—");
        }
        updateCastHint();
    }

    private void updateCastHint() {
        if (currentVyhybka == null || epc.cast <= 0
                || currentVyhybka.castMax - currentVyhybka.castMin + 1 != 3) {
            castHintBox.setVisibility(View.GONE);
            return;
        }
        String partName = castPartName(epc.cast);
        if (partName == null) {
            castHintBox.setVisibility(View.GONE);
            return;
        }
        String prefix = getString(R.string.cast_hint_prefix);
        String suffix = getString(R.string.cast_hint_suffix);
        String num = String.valueOf(epc.cast);
        SpannableString span = new SpannableString(prefix + num + suffix);
        int accent = ContextCompat.getColor(this, R.color.accent);
        int numStart = prefix.length();
        int numEnd = numStart + num.length();
        span.setSpan(new ForegroundColorSpan(accent), numStart, numEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new StyleSpan(Typeface.BOLD), numStart, numEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvCastHintAction.setText(span);
        tvCastHintPart.setText(partName);
        castHintBox.setVisibility(View.VISIBLE);
    }

    private String castPartName(int cast) {
        switch (cast) {
            case 1: return getString(R.string.cast_part_1);
            case 2: return getString(R.string.cast_part_2);
            case 3: return getString(R.string.cast_part_3);
            default: return null;
        }
    }

    private void resetTagWorkflow() {
        workflowRunning = false;
        chainWorkflow = false;
        activeStep = 0;
        step2Done = false;
        step3Done = false;
        updateStepIndicators();
        setActionStatusReady();
    }

    private void setActionStatus(String text, int color) {
        tvReaderStatus.setText(text);
        tvReaderStatus.setTextColor(color);
    }

    private void setActionStatusReady() {
        setActionStatus("připraveno", COLOR_STATUS_READY);
    }

    private void onWorkflowFailed(String status) {
        workflowRunning = false;
        chainWorkflow = false;
        activeStep = 0;
        step2Done = false;
        step3Done = false;
        updateStepIndicators();
        setActionStatus(status, COLOR_STATUS_ERROR);
        ui.postDelayed(this::setActionStatusReady, WORKFLOW_DONE_DELAY_MS + 500);
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
        valueWatcher(5, s -> {
            epc.cast = parseInt(s, epc.cast);
            updateSummary1();
        });
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

        findViewById(R.id.btnApplyPower).setOnClickListener(v -> applyPower());
        findViewById(R.id.btnWrite).setOnClickListener(v -> doWrite());
        findViewById(R.id.btnWritePwd).setOnClickListener(v -> doWritePassword());
        findViewById(R.id.btnLock).setOnClickListener(v -> doLock());
        findViewById(R.id.btnExportCsv).setOnClickListener(v -> exportCsv());
        findViewById(R.id.btnClearCsv).setOnClickListener(v -> deleteLastCsvRow());
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
                    onTuduListLoaded();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    tvSourceFile.setText("Chyba načtení: " + e.getMessage());
                    toast("Chyba načtení souboru");
                });
            }
        });
    }

    private void onTuduListLoaded() {
        if (!tuduList.isEmpty()) showTuduPicker();
    }

    private void selectTudu(Tudu t) {
        currentTudu = t;
        epc.tudu = t.code;
        if (!t.vyhybky.isEmpty()) {
            Tudu.Vyhybka first = firstAvailableVyhybka(t);
            selectVyhybka(first != null ? first : t.vyhybky.get(0), true);
        } else {
            currentVyhybka = null;
            refreshTemplate();
            updateStep1();
            updateSummary1();
        }
    }

    private void selectVyhybka(Tudu.Vyhybka v, boolean resetCast) {
        currentVyhybka = v;
        epc.vyhybka = v.cislo;
        if (resetCast) {
            epc.cast = currentTudu != null
                    ? firstMissingCast(currentTudu.code, v)
                    : v.castMin;
        }
        refreshTemplate();
        updateStep1();
        updateSummary1();
    }

    private void restoreSelectionFromRow(CsvStore.Row row) {
        if (row == null) return;
        epc.year = row.rok;
        epc.tudu = row.tudu;
        epc.vyhybka = parseInt(row.vyhybka, epc.vyhybka);
        epc.cast = parseInt(row.cast, epc.cast);
        epc.idRfid = parseLong(row.idRfid, epc.idRfid);
        prefs.edit().putLong("idRfid", epc.idRfid).apply();

        int tuduPos = -1;
        for (int i = 0; i < tuduList.size(); i++) {
            if (tuduList.get(i).code.equals(row.tudu)) {
                tuduPos = i;
                break;
            }
        }
        if (tuduPos >= 0) {
            currentTudu = tuduList.get(tuduPos);
            int vyhybkaPos = -1;
            for (int i = 0; i < currentTudu.vyhybky.size(); i++) {
                if (currentTudu.vyhybky.get(i).cislo == epc.vyhybka) {
                    vyhybkaPos = i;
                    break;
                }
            }
            if (vyhybkaPos >= 0) {
                currentVyhybka = currentTudu.vyhybky.get(vyhybkaPos);
            }
        }

        refreshTemplate();
        updateStep1();
        updateSummary1();
        resetTagWorkflow();
    }

    private void deleteLastCsvRow() {
        CsvStore.Row last = csvStore.removeLast();
        if (last == null) {
            toast("Tabulka je prázdná");
            return;
        }
        csvAdapter.setData(csvStore.getRows());
        restoreSelectionFromRow(last);
        toast("Poslední záznam vymazán");
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
            if (chainWorkflow) onWorkflowFailed("EPC není validní");
            return;
        }
        if (!uhf.isReady()) {
            toast("Čtečka není připravena");
            if (chainWorkflow) onWorkflowFailed("čtečka nedostupná");
            return;
        }
        if (!chainWorkflow) {
            setActionStatus("zapisuji EPC…", COLOR_STATUS_BUSY);
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

            if (chainWorkflow) {
                setActionStatus("zapisuji heslo…", COLOR_STATUS_BUSY);
                doWritePassword();
            } else {
                onTagCycleComplete();
                setActionStatusReady();
            }
        } else {
            tvWriteResult.setTextColor(0xFFC62828);
            tvWriteResult.setText("✗ " + r.message);
            if (chainWorkflow) onWorkflowFailed("chyba EPC");
            else setActionStatus("chyba EPC", COLOR_STATUS_ERROR);
        }
    }

    // ---------- zápis access hesla ----------

    private void doWritePassword() {
        if (!uhf.isReady()) {
            toast("Čtečka není připravena");
            if (chainWorkflow) onWorkflowFailed("čtečka nedostupná");
            return;
        }
        final String accessPwd = etPwdAccess.getText().toString().trim();
        final String newPwd = etPwdNew.getText().toString().trim();
        if (!newPwd.matches("[0-9A-Fa-f]{8}")) {
            toast("NEW PWD musí mít 8 hex znaků");
            if (chainWorkflow) onWorkflowFailed("neplatné heslo");
            return;
        }
        if (!chainWorkflow) {
            setActionStatus("zapisuji heslo…", COLOR_STATUS_BUSY);
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
            if (chainWorkflow) {
                setActionStatus("zamykám…", COLOR_STATUS_BUSY);
                doLock();
            } else {
                setActionStatusReady();
            }
        } else {
            tvPwdWriteResult.setTextColor(0xFFC62828);
            tvPwdWriteResult.setText("✗ " + r.message);
            if (chainWorkflow) onWorkflowFailed("chyba hesla");
            else setActionStatus("chyba hesla", COLOR_STATUS_ERROR);
        }
    }

    // ---------- zamčení tagu ----------

    private void doLock() {
        if (!uhf.isReady()) {
            toast("Čtečka není připravena");
            if (chainWorkflow) onWorkflowFailed("čtečka nedostupná");
            return;
        }
        if (!chainWorkflow) {
            setActionStatus("zamykám…", COLOR_STATUS_BUSY);
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
            if (chainWorkflow) {
                workflowRunning = false;
                chainWorkflow = false;
                activeStep = 0;
                step2Done = true;
                step3Done = true;
                updateStepIndicators();
                setActionStatus("hotovo", COLOR_STATUS_READY);
                ui.postDelayed(() -> {
                    onTagCycleComplete();
                    step2Done = false;
                    step3Done = false;
                    updateStepIndicators();
                    setActionStatusReady();
                }, WORKFLOW_DONE_DELAY_MS);
            } else {
                onTagCycleComplete();
                setActionStatus("hotovo", COLOR_STATUS_READY);
                ui.postDelayed(this::setActionStatusReady, WORKFLOW_DONE_DELAY_MS);
            }
        } else {
            tvLockResult.setTextColor(0xFFC62828);
            tvLockResult.setText("✗ " + r.message);
            if (chainWorkflow) onWorkflowFailed("chyba zamčení");
            else setActionStatus("chyba zamčení", COLOR_STATUS_ERROR);
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

    /** Po dokončení zápisu tagu (EPC samostatně, nebo celý řetězec EPC→heslo→lock). */
    private void onTagCycleComplete() {
        epc.idRfid += 1;
        prefs.edit().putLong("idRfid", epc.idRfid).apply();
        advanceCastAndVyhybka();
        refreshTemplate();
        updateSummary1();
    }

    private void advanceCastAndVyhybka() {
        syncCurrentVyhybka();
        if (currentVyhybka != null) {
            int next = epc.cast + 1;
            if (next > currentVyhybka.castMax) {
                advanceToNextVyhybka();
            } else {
                epc.cast = next;
            }
        } else {
            epc.cast += 1;
        }
    }

    private void syncCurrentVyhybka() {
        if (currentVyhybka != null || currentTudu == null || epc.vyhybka <= 0) return;
        int idx = findVyhybkaIndex(epc.vyhybka);
        if (idx >= 0) currentVyhybka = currentTudu.vyhybky.get(idx);
    }

    private int findVyhybkaIndex(int cislo) {
        if (currentTudu == null) return -1;
        for (int i = 0; i < currentTudu.vyhybky.size(); i++) {
            if (currentTudu.vyhybky.get(i).cislo == cislo) return i;
        }
        return -1;
    }

    private void advanceToNextVyhybka() {
        if (currentTudu == null || currentTudu.vyhybky.isEmpty()) return;
        syncCurrentVyhybka();
        int idx = currentVyhybka != null
                ? findVyhybkaIndex(currentVyhybka.cislo)
                : findVyhybkaIndex(epc.vyhybka);
        if (idx < 0) return;
        for (int i = idx + 1; i < currentTudu.vyhybky.size(); i++) {
            Tudu.Vyhybka next = currentTudu.vyhybky.get(i);
            if (!isVyhybkaCompleteInCsv(currentTudu.code, next)) {
                selectVyhybka(next, true);
                return;
            }
        }
        toast("Poslední výhybka v TUDU – cyklus dokončen.");
    }

    private Tudu.Vyhybka firstAvailableVyhybka(Tudu t) {
        for (Tudu.Vyhybka v : t.vyhybky) {
            if (!isVyhybkaCompleteInCsv(t.code, v)) return v;
        }
        return null;
    }

    private boolean isVyhybkaCompleteInCsv(String tuduCode, Tudu.Vyhybka v) {
        Set<Integer> written = getWrittenCastsForVyhybka(tuduCode, v);
        for (int c = v.castMin; c <= v.castMax; c++) {
            if (!written.contains(c)) return false;
        }
        return true;
    }

    private int firstMissingCast(String tuduCode, Tudu.Vyhybka v) {
        Set<Integer> written = getWrittenCastsForVyhybka(tuduCode, v);
        for (int c = v.castMin; c <= v.castMax; c++) {
            if (!written.contains(c)) return c;
        }
        return v.castMin;
    }

    private Set<Integer> getWrittenCastsForVyhybka(String tuduCode, Tudu.Vyhybka v) {
        Set<Integer> casts = new HashSet<>();
        for (CsvStore.Row row : csvStore.getRows()) {
            if (!tuduCode.equals(row.tudu)) continue;
            if (parseInt(row.vyhybka, -1) != v.cislo) continue;
            int cast = parseInt(row.cast, -1);
            if (cast >= 0) casts.add(cast);
        }
        return casts;
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
        setActionStatus("inicializuji…", COLOR_STATUS_BUSY);
        io.execute(() -> {
            boolean ok = uhf.init(this);
            int power = ok ? uhf.getPower() : -1;
            ui.post(() -> {
                if (ok) {
                    setActionStatusReady();
                    if (power > 0) etPower.setText(String.valueOf(power));
                } else {
                    setActionStatus("nedostupná", COLOR_STATUS_ERROR);
                }
            });
        });
    }

    private void runTriggerAction() {
        if (workflowRunning) return;
        if (!epc.isValid()) {
            toast("EPC není validní");
            return;
        }
        if (!uhf.isReady()) {
            toast("Čtečka není připravena");
            return;
        }
        final String newPwd = etPwdNew.getText().toString().trim();
        if (!newPwd.matches("[0-9A-Fa-f]{8}")) {
            toast("NEW PWD musí mít 8 hex znaků");
            return;
        }
        chainWorkflow = true;
        workflowRunning = true;
        activeStep = 2;
        step2Done = false;
        step3Done = false;
        updateStepIndicators();
        setActionStatus("zapisuji EPC…", COLOR_STATUS_BUSY);
        doWrite();
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
