package tianci.dev.xptranslatetext;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

public class SettingsActivity extends AppCompatActivity {

    /**
     * Normally [MODE_WORLD_READABLE] causes a crash.
     * But if "xposedsharedprefs" flag is present in AndroidManifest,
     * then the file is accordingly taken care by lsposed framework.
     *
     * If an exception is thrown, means module is not enabled,
     * hence Android throws a security exception.
     */

    private static final String TAG = "SettingsActivity";

    @SuppressLint("WorldReadableFiles")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        SharedPreferences pref;
        try {
            pref = getSharedPreferences("prefs", MODE_WORLD_READABLE);
        } catch (Exception e) {
            pref = null;
        }

        if (pref == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Module Not Enabled")
                    .setMessage("The Xposed module is not enabled. Please enable it in your Xposed framework.")
                    .setPositiveButton("OK", (dialogInterface, i) -> finish())
                    .show();

        } else {
            if (savedInstanceState == null) {
                getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.settings_container, new MainPreferenceFragment())
                        .commit();
            }

        }
    }

    public static class MainPreferenceFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            PreferenceManager pm = getPreferenceManager();

            pm.setSharedPreferencesName("xp_translate_text_configs");

            // LSPosed(>=93)
            try {
                pm.setSharedPreferencesMode(Context.MODE_WORLD_READABLE);
            } catch (SecurityException e) {
                Log.w(TAG, "LSPosed XSharedPreferences not enabled or older version. Fallback => MODE_PRIVATE", e);
                pm.setSharedPreferencesMode(Context.MODE_PRIVATE);
            }

            setPreferencesFromResource(R.xml.preferences, rootKey);
        }
    }
}
