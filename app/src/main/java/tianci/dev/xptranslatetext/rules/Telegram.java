package tianci.dev.xptranslatetext.rules;

/**
 * App specific rules for Telegram to skip certain classes from translation.
 */
public class Telegram {
    private static final String[] skippedPrefixes = {
            "org.telegram.ui.ActionBar.AlertDialog",
            "org.telegram.ui.Components.PagerSlidingTabStrip$TextTab",
    };

    public static boolean shouldSkipClass(String className) {
        for (String prefix : skippedPrefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
