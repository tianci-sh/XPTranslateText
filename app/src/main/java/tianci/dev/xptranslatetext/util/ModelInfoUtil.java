package tianci.dev.xptranslatetext.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Utilities for tracking on-device ML Kit translation model usage.
 */
public class ModelInfoUtil {

    private static final String PREF_USAGE = "xp_mlkit_model_usage";

    /** Record last-used timestamp for a language model. */
    public static void markModelUsed(Context context, String langCode) {
        if (langCode == null) return;
        SharedPreferences sp = context.getSharedPreferences(PREF_USAGE, Context.MODE_PRIVATE);
        sp.edit().putLong("last_used_" + langCode, System.currentTimeMillis()).apply();
    }

    /** Get last-used timestamp for a language model, or 0 when unknown. */
    public static long getLastUsed(Context context, String langCode) {
        if (langCode == null) return 0L;
        SharedPreferences sp = context.getSharedPreferences(PREF_USAGE, Context.MODE_PRIVATE);
        return sp.getLong("last_used_" + langCode, 0L);
    }
}
