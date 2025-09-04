package tianci.dev.xptranslatetext.ui;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.text.format.DateUtils;
import android.widget.Button;
import android.graphics.Color;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.divider.MaterialDividerItemDecoration;
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

import tianci.dev.xptranslatetext.R;
import tianci.dev.xptranslatetext.util.ModelInfoUtil;

public class ModelManagerActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private TextView emptyView;
    private com.google.android.material.progressindicator.CircularProgressIndicator progress;
    private Button btnRefresh;
    private Button btnDeleteAll;

    private final List<TranslateRemoteModel> models = new ArrayList<>();
    private ModelAdapter adapter;

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_manager);

        setTitle(R.string.model_manager_title);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        recyclerView = findViewById(R.id.recycler_models);
        emptyView = findViewById(R.id.empty_view);
        progress = findViewById(R.id.progress);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnDeleteAll = findViewById(R.id.btn_delete_all);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ModelAdapter();
        recyclerView.setAdapter(adapter);
        try {
            int outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, Color.LTGRAY);
            MaterialDividerItemDecoration deco = new MaterialDividerItemDecoration(this, LinearLayoutManager.VERTICAL);
            deco.setDividerColor(outline);
            recyclerView.addItemDecoration(deco);
        } catch (Throwable ignored) {}

        btnRefresh.setOnClickListener(v -> refresh());
        btnDeleteAll.setOnClickListener(v -> confirmDeleteAll());

        refresh();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
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
        adapter.notifyDataSetChanged();
        boolean empty = models.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private String formatLastUsed(long ts) {
        if (ts <= 0L) return getString(R.string.last_used_label, getString(R.string.last_used_never));
        CharSequence rel = DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        return getString(R.string.last_used_label, rel);
    }

    private static String displayNameFromMlCode(String mlCode) {
        try {
            // Prefer BCP-47 Locale; most ML Kit codes are ISO 639-1/2 short codes.
            Locale loc = Locale.forLanguageTag(mlCode);
            String name = loc.getDisplayName(Locale.TRADITIONAL_CHINESE);
            if (name == null || name.trim().isEmpty() || name.equalsIgnoreCase(mlCode)) {
                // Fallback handling for common codes.
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

    private class ModelAdapter extends RecyclerView.Adapter<ModelAdapter.VH> {
        @Override
        public VH onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            android.view.LayoutInflater inflater = android.view.LayoutInflater.from(parent.getContext());
            android.view.View v = inflater.inflate(R.layout.item_model_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            TranslateRemoteModel m = models.get(position);
            String code = m.getLanguage();
            String name = displayNameFromMlCode(code);
            String lastUsed = formatLastUsed(ModelInfoUtil.getLastUsed(ModelManagerActivity.this, code));
            holder.title.setText(name + " (" + code + ")");
            holder.subtitle.setText(lastUsed);
        }

        @Override
        public int getItemCount() {
            return models.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final android.widget.ImageView icon;
            final android.widget.TextView title;
            final android.widget.TextView subtitle;
            VH(View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.icon);
                title = itemView.findViewById(R.id.title);
                subtitle = itemView.findViewById(R.id.subtitle);
                itemView.setOnClickListener(v -> confirmDeleteSingle(getBindingAdapterPosition()));
            }
        }
    }
}
