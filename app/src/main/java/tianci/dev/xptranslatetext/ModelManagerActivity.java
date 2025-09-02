package tianci.dev.xptranslatetext;

import android.os.Bundle;
import android.view.View;
import android.text.format.DateUtils;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ModelManagerActivity extends AppCompatActivity {

    private ListView listView;
    private TextView emptyView;
    private ProgressBar progress;
    private Button btnRefresh;
    private Button btnDeleteAll;

    private final List<TranslateRemoteModel> models = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_manager);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        listView = findViewById(R.id.list_models);
        emptyView = findViewById(R.id.empty_view);
        progress = findViewById(R.id.progress);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnDeleteAll = findViewById(R.id.btn_delete_all);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> confirmDeleteSingle(position));

        btnRefresh.setOnClickListener(v -> refresh());
        btnDeleteAll.setOnClickListener(v -> confirmDeleteAll());

        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void showLoading(boolean show) {
        progress.setVisibility(show ? View.VISIBLE : View.GONE);
        btnRefresh.setEnabled(!show);
        btnDeleteAll.setEnabled(!show);
    }

    private void refresh() {
        showLoading(true);
        RemoteModelManager manager = RemoteModelManager.getInstance();
        manager.getDownloadedModels(TranslateRemoteModel.class)
                .addOnSuccessListener(set -> {
                    models.clear();
                    models.addAll(set);
                    applyModelsToList();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                    models.clear();
                    applyModelsToList();
                })
                .addOnCompleteListener(task -> showLoading(false));
    }

    private void applyModelsToList() {
        List<String> items = new ArrayList<>();
        for (TranslateRemoteModel m : models) {
            String code = m.getLanguage();
            String name = displayNameFromMlCode(code);
            String lastUsed = formatLastUsed(ModelInfoUtil.getLastUsed(this, code));
            items.add(name + " (" + code + ") · " + lastUsed);
        }
        adapter.clear();
        adapter.addAll(items);
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        listView.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private String formatLastUsed(long ts) {
        if (ts <= 0L) return getString(R.string.last_used_label, getString(R.string.last_used_never));
        CharSequence rel = DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        return getString(R.string.last_used_label, rel);
    }

    private static String displayNameFromMlCode(String mlCode) {
        try {
            // 盡量以 BCP-47 建立 Locale；多數 ML Kit code 為 ISO 639-1/2 簡碼
            Locale loc = Locale.forLanguageTag(mlCode);
            String name = loc.getDisplayName(Locale.TRADITIONAL_CHINESE);
            if (name == null || name.trim().isEmpty() || name.equalsIgnoreCase(mlCode)) {
                // 後備處理常見代碼
                if ("zh".equalsIgnoreCase(mlCode)) return "中文";
            } else {
                return Character.toUpperCase(name.charAt(0)) + name.substring(1);
            }
        } catch (Throwable ignored) { }
        return mlCode;
    }

    private void confirmDeleteSingle(int position) {
        if (position < 0 || position >= models.size()) return;
        TranslateRemoteModel model = models.get(position);
        String code = model.getLanguage();
        String name = displayNameFromMlCode(code);
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_delete_title)
                .setMessage(getString(R.string.confirm_delete_single, name + " (" + code + ")"))
                .setPositiveButton(android.R.string.ok, (d, w) -> deleteSingle(model))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteSingle(TranslateRemoteModel model) {
        showLoading(true);
        RemoteModelManager.getInstance()
                .deleteDownloadedModel(model)
                .addOnSuccessListener(v -> Toast.makeText(this, R.string.delete_success, Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(this, getString(R.string.delete_failed, e.getMessage()), Toast.LENGTH_LONG).show())
                .addOnCompleteListener(t -> refresh());
    }

    private void confirmDeleteAll() {
        if (models.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_delete_title)
                .setMessage(R.string.confirm_delete_all)
                .setPositiveButton(android.R.string.ok, (d, w) -> deleteAll())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteAll() {
        showLoading(true);
        io.execute(() -> {
            Exception error = null;
            try {
                RemoteModelManager manager = RemoteModelManager.getInstance();
                Set<TranslateRemoteModel> set = Tasks.await(manager.getDownloadedModels(TranslateRemoteModel.class));
                for (TranslateRemoteModel m : set) {
                    Tasks.await(manager.deleteDownloadedModel(m));
                }
            } catch (Exception e) {
                error = e;
            }
            Exception finalError = error;
            runOnUiThread(() -> {
                if (finalError == null) {
                    Toast.makeText(this, R.string.delete_success, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.delete_failed, finalError.getMessage()), Toast.LENGTH_LONG).show();
                }
                refresh();
            });
        });
    }
}
