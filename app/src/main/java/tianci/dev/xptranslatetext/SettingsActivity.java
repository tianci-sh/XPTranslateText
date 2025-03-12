package tianci.dev.xptranslatetext;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = "SettingsActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_container, new MainPreferenceFragment())
                    .commit();
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
