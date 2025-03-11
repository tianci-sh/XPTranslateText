package tianci.dev.xptranslatetext;

import android.widget.TextView;

import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookMain implements IXposedHookLoadPackage {

    private static boolean isTranslating = false;

    private static final AtomicInteger atomicIdGenerator = new AtomicInteger(1);

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        XposedBridge.log("package => " + lpparam.packageName);

        XposedHelpers.findAndHookMethod(
                "android.widget.TextView", lpparam.classLoader,
                "setText",
                CharSequence.class, TextView.BufferType.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        CharSequence originalText = (CharSequence) param.args[0];
                        if (originalText == null || originalText.length() == 0) {
                            return; // 不翻譯空字串
                        }

                        //純數字
                        if (originalText.toString().matches("^\\d+$")) {
                            return;
                        }

                        int translationId = atomicIdGenerator.getAndIncrement();

                        // 把翻譯ID存在 TextView 裏
                        TextView tv = (TextView) param.thisObject;
                        tv.setTag(translationId);

                        XposedBridge.log("Original String => " + originalText);

                        // 非同步翻譯
                        new TranslateTask(param, translationId).execute(originalText.toString(), "en", "zh-TW");
                    }
                }
        );
    }

    public static void applyTranslatedText(XC_MethodHook.MethodHookParam param, String translatedText) {
        try {
            isTranslating = true;
            // 直接把翻譯結果放回去呼叫原始方法
            param.args[0] = translatedText;
            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
        } catch (Throwable t) {
            XposedBridge.log("Error applying translated text: " + t.getMessage());
        } finally {
            isTranslating = false;
        }
    }
}
