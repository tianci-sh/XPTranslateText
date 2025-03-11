package tianci.dev.xptranslatetext;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookMain implements IXposedHookLoadPackage {

    private static boolean isTranslating = false;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        XposedBridge.log("package => " + lpparam.packageName);

        XposedHelpers.findAndHookMethod(
                "android.widget.TextView",
                lpparam.classLoader,
                "setText",
                CharSequence.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        CharSequence originalText = (CharSequence) param.args[0];
                        if (originalText == null || originalText.length() == 0) {
                            return; // 不翻譯空字串
                        }

                        XposedBridge.log("Original String => " + originalText);
                        param.args[0] = originalText.toString().toUpperCase();

                        // 非同步翻譯
                        new TranslateTask(param).execute(originalText.toString(), "en", "zh-TW");
                    }
                }
        );
    }

    public static void applyTranslatedText(XC_MethodHook.MethodHookParam param, String translatedText) {
        try {
            isTranslating = true;
            param.args[0] = translatedText;
            XposedBridge.log("Translated => " + translatedText);

            // 以新的 param.args 呼叫原本 TextView#setText(CharSequence)
            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
        } catch (Throwable t) {
            XposedBridge.log("Error applying translated text: " + t.getMessage());
        } finally {
            isTranslating = false;
        }
    }
}
