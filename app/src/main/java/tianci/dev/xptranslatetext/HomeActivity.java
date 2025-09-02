package tianci.dev.xptranslatetext;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.mlkit.nl.translate.TranslateLanguage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 簡單首頁：
 * - 切換啟動/停止本地翻譯服務器
 * - 設定來源/目標語言（寫入 xp_translate_text_configs）
 */
public class HomeActivity extends AppCompatActivity {

    private Spinner sourceSpinner;
    private Spinner targetSpinner;
    private Switch serverSwitch;
    private TextView statusText;

    private SharedPreferences prefs;

    private final List<String> sourceValues = new ArrayList<>();
    private final List<String> targetValues = new ArrayList<>();

    @SuppressLint("WorldReadableFiles")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        boolean moduleEnabled = isXposedModuleEnabled();
        if (moduleEnabled) {
            try {
                prefs = getSharedPreferences("xp_translate_text_configs", MODE_WORLD_READABLE);
            } catch (SecurityException e) {
                prefs = getSharedPreferences("xp_translate_text_configs", Context.MODE_PRIVATE);
            }
        } else {
            prefs = getSharedPreferences("xp_translate_text_configs", Context.MODE_PRIVATE);
            new AlertDialog.Builder(this)
                    .setTitle("Module Not Enabled")
                    .setMessage("The Xposed module is not enabled. Please enable it in your Xposed framework.")
                    .setCancelable(false)
                    .setPositiveButton("OK", (dialogInterface, i) -> forceCloseApp())
                    .show();
            return;
        }

        sourceSpinner = findViewById(R.id.spinner_source_lang);
        targetSpinner = findViewById(R.id.spinner_target_lang);
        serverSwitch = findViewById(R.id.switch_server);
        statusText = findViewById(R.id.text_status);

        setupLanguageSpinners();
        loadInitialSelections();

        serverSwitch.setChecked(LocalTranslationService.isRunning());
        updateStatusText(LocalTranslationService.isRunning());

        serverSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                startLocalServer();
            } else {
                stopLocalServer();
            }
            updateStatusText(checked);
        });
    }

    private boolean isXposedModuleEnabled() {
        try {
            // 嘗試以 MODE_WORLD_READABLE 存取；若未啟用 Xposed 模組會拋出 SecurityException
            getSharedPreferences("prefs", MODE_WORLD_READABLE);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void forceCloseApp() {
        try {
            finishAffinity();
        } catch (Throwable ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                finishAndRemoveTask();
            }
        } catch (Throwable ignored) {
        }
        try {
            Process.killProcess(Process.myPid());
        } catch (Throwable ignored) {
        }
        System.exit(0);
    }

    private void setupLanguageSpinners() {
        // 動態建置語言清單：以 ML Kit 支援集合為準，顯示本地化名稱 + 語言標籤
        // 來源：含 auto；目標：不含 auto
        List<String> srcEntries = new ArrayList<>();
        List<String> dstEntries = new ArrayList<>();

        // 先加入 auto 到來源
        sourceValues.clear();
        targetValues.clear();
        srcEntries.add("自動偵測 (auto)");
        sourceValues.add("auto");

        // 取得 ML Kit 支援語言代碼集合（如 "en","ja","zh"）
        Set<String> supported = new HashSet<>(TranslateLanguage.getAllLanguages());

        // 我們希望同時提供 zh-TW 與 zh-CN 兩個選項，因此從集合中移除 zh，改成兩個變體
        if (supported.contains("zh")) {
            supported.remove("zh");
        }

        // 將代碼轉換為 BCP-47 標籤與顯示名稱
        List<LangItem> items = new ArrayList<>();
        for (String code : supported) {
            String tag = code;
            if (tag == null) continue;
            String name = getDisplayName(tag);
            items.add(new LangItem(tag, name));
        }
        // 特別加入繁簡中文兩個選項
        items.add(new LangItem("zh-TW", "中文（繁體）"));
        items.add(new LangItem("zh-CN", "中文（簡體）"));

        // 以顯示名稱排序（繁中）
        Collections.sort(items, Comparator.comparing(a -> a.displayName, CollatorCompat.get()));

        // 準備 entries/values
        for (LangItem it : items) {
            String entry = it.displayName + " (" + it.tag + ")";
            srcEntries.add(entry);
            dstEntries.add(entry);
            sourceValues.add(it.tag);
            targetValues.add(it.tag);
        }

        ArrayAdapter<String> srcAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, srcEntries
        );
        srcAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sourceSpinner.setAdapter(srcAdapter);

        ArrayAdapter<String> dstAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, dstEntries
        );
        dstAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        targetSpinner.setAdapter(dstAdapter);

        sourceSpinner.setOnItemSelectedListener(new SimpleOnItemSelectedListener(() -> {
            int pos = sourceSpinner.getSelectedItemPosition();
            if (pos >= 0 && pos < sourceValues.size()) {
                prefs.edit().putString("source_lang", sourceValues.get(pos)).apply();
            }
        }));

        targetSpinner.setOnItemSelectedListener(new SimpleOnItemSelectedListener(() -> {
            int pos = targetSpinner.getSelectedItemPosition();
            if (pos >= 0 && pos < targetValues.size()) {
                prefs.edit().putString("target_lang", targetValues.get(pos)).apply();
            }
        }));
    }

    private void loadInitialSelections() {
        String src = prefs.getString("source_lang", "auto");
        String dst = prefs.getString("target_lang", "zh-TW");

        sourceSpinner.setSelection(indexOf(sourceValues.toArray(new String[0]), src));
        targetSpinner.setSelection(indexOf(targetValues.toArray(new String[0]), dst));
    }

    private int indexOf(String[] arr, String target) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equalsIgnoreCase(target)) return i;
        }
        return 0;
    }

    private String getDisplayName(String bcp47Tag) {
        try {
            Locale locale = Locale.forLanguageTag(bcp47Tag);
            String name = locale.getDisplayName(Locale.TRADITIONAL_CHINESE);
            if (name == null || name.trim().isEmpty()) return bcp47Tag;
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        } catch (Throwable t) {
            return bcp47Tag;
        }
    }

    private static class LangItem {
        final String tag;
        final String displayName;
        LangItem(String tag, String displayName) {
            this.tag = tag;
            this.displayName = displayName;
        }
    }

    // 簡單中文排序器（以繁中 Locale 排序）
    private static class CollatorCompat {
        static java.text.Collator get() {
            try {
                java.text.Collator c = java.text.Collator.getInstance(Locale.TRADITIONAL_CHINESE);
                c.setStrength(java.text.Collator.PRIMARY);
                return c;
            } catch (Throwable t) {
                return java.text.Collator.getInstance();
            }
        }
    }

    private void startLocalServer() {
        Intent i = new Intent(this, LocalTranslationService.class);
        i.setAction(LocalTranslationService.ACTION_START);
        // 使用前景服務以避免被系統回收
        startForegroundService(i);
    }

    private void stopLocalServer() {
        Intent i = new Intent(this, LocalTranslationService.class);
        i.setAction(LocalTranslationService.ACTION_STOP);
        startService(i);
    }

    private void updateStatusText(boolean running) {
        statusText.setText(running ? getString(R.string.server_running, LocalTranslationService.PORT)
                : getString(R.string.server_stopped));
    }

    // 簡化版 OnItemSelectedListener：只有在選擇變動時觸發 runnable
    private static class SimpleOnItemSelectedListener implements android.widget.AdapterView.OnItemSelectedListener {
        private final Runnable onSelected;

        SimpleOnItemSelectedListener(Runnable onSelected) {
            this.onSelected = onSelected;
        }

        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
            onSelected.run();
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
        }
    }
}
