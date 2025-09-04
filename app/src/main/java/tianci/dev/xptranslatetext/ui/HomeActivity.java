package tianci.dev.xptranslatetext.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import tianci.dev.xptranslatetext.R;
import tianci.dev.xptranslatetext.service.LocalTranslationService;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.mlkit.nl.translate.TranslateLanguage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Simple home screen:
 * - Toggle start/stop of the local translation server.
 * - Configure source/target languages (stored in xp_translate_text_configs).
 */
public class HomeActivity extends AppCompatActivity {

    private MaterialAutoCompleteTextView sourceDropdown;
    private MaterialAutoCompleteTextView targetDropdown;
    private MaterialSwitch serverSwitch;
    private TextView statusText;

    private SharedPreferences prefs;

    private final List<String> sourceValues = new ArrayList<>();
    private final List<String> targetValues = new ArrayList<>();
    private final List<String> srcEntries = new ArrayList<>();
    private final List<String> dstEntries = new ArrayList<>();

    @SuppressLint("WorldReadableFiles")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        setTitle(R.string.home_title);

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

        sourceDropdown = findViewById(R.id.spinner_source_lang);
        targetDropdown = findViewById(R.id.spinner_target_lang);
        serverSwitch = findViewById(R.id.switch_server);
        statusText = findViewById(R.id.text_status);
        findViewById(R.id.btn_model_manager).setOnClickListener(v -> {
            startActivity(new Intent(this, ModelManagerActivity.class));
        });

        setupLanguageDropdowns();
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
            // Attempt MODE_WORLD_READABLE access; when the Xposed module is disabled it throws SecurityException.
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

    private void setupLanguageDropdowns() {
        // Build language list dynamically based on ML Kit support; show localized name + tag.
        // Source list includes "auto"; target list does not include "auto".
        srcEntries.clear();
        dstEntries.clear();

        // Add "auto" to the source list first.
        sourceValues.clear();
        targetValues.clear();
        srcEntries.add(getString(R.string.label_auto_detect));
        sourceValues.add("auto");

        // Get ML Kit supported language codes (e.g., "en", "ja", "zh").
        Set<String> supported = new HashSet<>(TranslateLanguage.getAllLanguages());

        // Offer zh-TW and zh-CN separately; remove "zh" and add both variants instead.
        if (supported.contains("zh")) {
            supported.remove("zh");
        }

        // Convert language code to BCP-47 tag and display name.
        List<LangItem> items = new ArrayList<>();
        for (String code : supported) {
            String tag = code;
            if (tag == null) continue;
            String name = getDisplayName(tag);
            items.add(new LangItem(tag, name));
        }
        // Add Traditional and Simplified Chinese explicitly.
        items.add(new LangItem("zh-TW", getString(R.string.label_lang_zh_tw)));
        items.add(new LangItem("zh-CN", getString(R.string.label_lang_zh_cn)));

        // Sort by display name using current UI locale.
        java.text.Collator collator = getUiCollator();
        Collections.sort(items, (a, b) -> collator.compare(a.displayName, b.displayName));

        // Prepare entries/values for the dropdowns.
        for (LangItem it : items) {
            String entry = it.displayName + " (" + it.tag + ")";
            srcEntries.add(entry);
            dstEntries.add(entry);
            sourceValues.add(it.tag);
            targetValues.add(it.tag);
        }

        ArrayAdapter<String> srcAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, srcEntries
        );
        sourceDropdown.setAdapter(srcAdapter);

        ArrayAdapter<String> dstAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, dstEntries
        );
        targetDropdown.setAdapter(dstAdapter);

        sourceDropdown.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < sourceValues.size()) {
                prefs.edit().putString("source_lang", sourceValues.get(position)).apply();
            }
        });

        targetDropdown.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < targetValues.size()) {
                prefs.edit().putString("target_lang", targetValues.get(position)).apply();
            }
        });
    }

    private void loadInitialSelections() {
        String src = prefs.getString("source_lang", "auto");
        String dst = prefs.getString("target_lang", "zh-TW");

        int srcIndex = indexOf(sourceValues.toArray(new String[0]), src);
        int dstIndex = indexOf(targetValues.toArray(new String[0]), dst);
        if (srcIndex >= 0 && srcIndex < srcEntries.size()) {
            sourceDropdown.setText(srcEntries.get(srcIndex), false);
        }
        if (dstIndex >= 0 && dstIndex < dstEntries.size()) {
            targetDropdown.setText(dstEntries.get(dstIndex), false);
        }
    }

    private int indexOf(String[] arr, String target) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equalsIgnoreCase(target)) return i;
        }
        return 0;
    }

    private String getDisplayName(String bcp47Tag) {
        try {
            Locale target = Locale.forLanguageTag(bcp47Tag);
            Locale ui = getCurrentLocale();
            String name = target.getDisplayName(ui);
            if (name == null || name.trim().isEmpty()) return bcp47Tag;
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        } catch (Throwable t) {
            return bcp47Tag;
        }
    }

    private Locale getCurrentLocale() {
        try {
            return getResources().getConfiguration().getLocales().get(0);
        } catch (Throwable t) {
            return Locale.getDefault();
        }
    }

    private java.text.Collator getUiCollator() {
        try {
            Locale ui = getCurrentLocale();
            java.text.Collator c = java.text.Collator.getInstance(ui);
            c.setStrength(java.text.Collator.PRIMARY);
            return c;
        } catch (Throwable t) {
            return java.text.Collator.getInstance();
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

    // Simple Chinese collator (Traditional Chinese locale).
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
        // Start as a foreground service to reduce process kills.
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
}
