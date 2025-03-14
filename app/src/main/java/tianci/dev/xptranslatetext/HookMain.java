package tianci.dev.xptranslatetext;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import dalvik.system.DexFile;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import de.robv.android.xposed.XSharedPreferences;
import tianci.dev.xptranslatetext.rules.Telegram;

public class HookMain implements IXposedHookLoadPackage {

    private static boolean isTranslating = false;

    private static final AtomicInteger atomicIdGenerator = new AtomicInteger(1);

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals("tianci.dev.xptranslatetext")) return;

        XposedBridge.log("package => " + lpparam.packageName);

        XSharedPreferences prefs = new XSharedPreferences("tianci.dev.xptranslatetext", "xp_translate_text_configs");

        String sourceLang = "en";
        String targetLang = "zh-TW";

        if (prefs.getFile().canRead()) {
            prefs.reload();
            sourceLang = prefs.getString("source_lang", sourceLang);
            targetLang = prefs.getString("target_lang", targetLang);

            XposedBridge.log("sourceLang=" + sourceLang + ", targetLang=" + targetLang);
        } else {
            XposedBridge.log("Cannot read XSharedPreferences => " + prefs.getFile().getAbsolutePath()
                    + ". Fallback to default: en->zh-TW");
        }

        final String finalSourceLang = sourceLang;
        final String finalTargetLang = targetLang;

        hookAllCustomSetTextClasss(lpparam, finalSourceLang, finalTargetLang);
        hookTextView(lpparam, finalSourceLang, finalTargetLang);

        XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                lpparam.classLoader,
                "onCreate",
                Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity activity = (Activity) param.thisObject;
                        Context context = activity.getApplicationContext();

                        XposedBridge.log("Context: " + context.getPackageName());
                        MultiSegmentTranslateTask.initDatabaseHelper(context);
                    }
                }
        );
    }

    private void hookTextView(XC_LoadPackage.LoadPackageParam lpparam, String finalSourceLang, String finalTargetLang) {
        XposedHelpers.findAndHookMethod(
                "android.widget.TextView",
                lpparam.classLoader,
                "setText",
                CharSequence.class,
                TextView.BufferType.class,
                boolean.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        CharSequence originalText = (CharSequence) param.args[0];

                        if (originalText == null || originalText.length() == 0) {
                            return;
                        }

                        XposedBridge.log(String.format("[ translate ] %s string => %s", param.thisObject.getClass(), originalText));

                        //Debugging: Check if calling invokeOriginalMethod causes text to disappear
                        //XposedBridge.log("TextView Class => " + param.thisObject.getClass().getName());

                        if (isTranslationSkippedForClass(lpparam.packageName, param.thisObject.getClass().getName())) {
                            return;
                        }

                        int translationId = atomicIdGenerator.getAndIncrement();
                        TextView tv = (TextView) param.thisObject;
                        tv.setTag(translationId);

                        List<Segment> segments;
                        if (originalText instanceof Spanned) {
                            segments = parseAllSegments((Spanned) originalText);
                        } else {
                            segments = new ArrayList<>();
                            segments.add(new Segment(0, originalText.length(), originalText.toString()));
                        }

                        MultiSegmentTranslateTask.translateSegmentsAsync(
                                param,
                                translationId,
                                segments,
                                finalSourceLang,
                                finalTargetLang
                        );
                    }
                }
        );
    }

    private void hookAllCustomSetTextClasss(XC_LoadPackage.LoadPackageParam lpparam, String finalSourceLang, String finalTargetLang) {
        try {
            Field pathListField = XposedHelpers.findField(lpparam.classLoader.getClass(), "pathList");
            Object pathList = pathListField.get(lpparam.classLoader);

            Field dexElementsField = XposedHelpers.findField(pathList.getClass(), "dexElements");
            Object[] dexElements = (Object[]) dexElementsField.get(pathList);

            Field dexFileField = null;
            for (Object element : dexElements) {
                if (element == null) continue;
                if (dexFileField == null) {
                    dexFileField = XposedHelpers.findFieldIfExists(element.getClass(), "dexFile");
                }
                if (dexFileField == null) {
                    XposedBridge.log("Can't find dexFile field in dexElement!");
                    continue;
                }
                DexFile dexFile = (DexFile) dexFileField.get(element);
                if (dexFile == null) {
                    continue;
                }

                Enumeration<String> classNames = dexFile.entries();
                while (classNames.hasMoreElements()) {
                    String className = classNames.nextElement();

                    try {
                        Class<?> clazz = lpparam.classLoader.loadClass(className);

                        // skip extends textview class
                        if (TextView.class.isAssignableFrom(clazz)) {
                            continue;
                        }

                        if (isTranslationSkippedForClass(lpparam.packageName, className)) {
                            continue;
                        }

                        for (final Method method : clazz.getDeclaredMethods()) {
                            if (!method.getName().equals("setText")) {
                                continue;
                            }
                            Class<?>[] pTypes = method.getParameterTypes();
                            if (pTypes.length == 1 && (pTypes[0] == CharSequence.class || pTypes[0] == String.class)) {

                                XposedBridge.hookMethod(method, new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                        CharSequence originalText = (CharSequence) param.args[0];
                                        if (originalText == null || originalText.length() == 0) {
                                            return;
                                        }

                                        XposedBridge.log(String.format("[ translate ] %s string => %s", param.thisObject.getClass(), originalText));

                                        int translationId = atomicIdGenerator.getAndIncrement();
                                        Method setTagMethod = XposedHelpers.findMethodExactIfExists(param.thisObject.getClass(), "setTag", Object.class);
                                        if (setTagMethod != null) {
                                            XposedHelpers.callMethod(param.thisObject, "setTag", translationId);
                                        }


                                        List<Segment> segments;
                                        if (originalText instanceof Spanned) {
                                            segments = parseAllSegments((Spanned) originalText);
                                        } else {
                                            segments = new ArrayList<>();
                                            segments.add(new Segment(0, originalText.length(), originalText.toString()));
                                        }

                                        MultiSegmentTranslateTask.translateSegmentsAsync(
                                                param,
                                                translationId,
                                                segments,
                                                finalSourceLang,
                                                finalTargetLang
                                        );
                                    }
                                });
                                XposedBridge.log(String.format("Hook custom setText class => [%s] ", className));
                            }
                        }
                    } catch (Throwable e) {
                        XposedBridge.log(String.format("Hook custom setText failed class => [%s]", className));
                    }
                }
            }
        } catch (Exception e) {
            XposedBridge.log("Enumerate Dex error => " + e.getMessage());
        }
    }

    private boolean isTranslationSkippedForClass(String packageName, String className) {
        return switch (packageName) {
            case "org.telegram.messenger" -> Telegram.shouldSkipClass(className);
            default -> false;
        };
    }

    public static void applyTranslatedSegments(XC_MethodHook.MethodHookParam param,
                                               List<Segment> segments) {
        try {
            isTranslating = true;

            // 重建新的 Spannable
            CharSequence newSpanned = buildSpannedFromSegments(segments);

            // 套回原始方法參數
            param.args[0] = newSpanned;
//            param.args[1] = TextView.BufferType.SPANNABLE;

            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
        } catch (Throwable t) {
            XposedBridge.log("Error applying translated segments: " + t.getMessage());
        } finally {
            isTranslating = false;
        }
    }

    private static List<Segment> parseAllSegments(Spanned spanned) {
        List<Segment> segments = new ArrayList<>();
        int textLen = spanned.length();
        if (textLen == 0) {
            return segments;
        }

        // 1) 取出所有 span
        Object[] allSpans = spanned.getSpans(0, textLen, Object.class);

        // 2) 收集所有 "邊界"(start/end), 一併加上 0, textLen
        Set<Integer> boundarySet = new HashSet<>();
        boundarySet.add(0);
        boundarySet.add(textLen);

        for (Object span : allSpans) {
            int st = spanned.getSpanStart(span);
            int en = spanned.getSpanEnd(span);
            boundarySet.add(st);
            boundarySet.add(en);
        }

        // 3) 排序
        List<Integer> boundaries = new ArrayList<>(boundarySet);
        Collections.sort(boundaries);

        // 4) 依序區間 [i, i+1) 建立 segment
        for (int i = 0; i < boundaries.size() - 1; i++) {
            int segStart = boundaries.get(i);
            int segEnd = boundaries.get(i + 1);
            if (segStart >= segEnd) {
                continue;
            }

            CharSequence sub = spanned.subSequence(segStart, segEnd);
            Segment seg = new Segment(segStart, segEnd, sub.toString());

            // 找所有與該區間交集的 span
            for (Object span : allSpans) {
                int spanStart = spanned.getSpanStart(span);
                int spanEnd = spanned.getSpanEnd(span);
                int flags = spanned.getSpanFlags(span);

                int intersectStart = Math.max(spanStart, segStart);
                int intersectEnd = Math.min(spanEnd, segEnd);

                if (intersectStart < intersectEnd) {
                    // 與本 segment 有重疊
                    int relativeStart = intersectStart - segStart;
                    int relativeEnd = intersectEnd - segStart;
                    seg.spans.add(new SpanSpec(span, relativeStart, relativeEnd, flags));
                }
            }

            segments.add(seg);
        }

        return segments;
    }

    private static CharSequence buildSpannedFromSegments(List<Segment> segments) {
        SpannableStringBuilder ssb = new SpannableStringBuilder();

        for (Segment seg : segments) {
            int segStart = ssb.length();

            String piece = (seg.translatedText != null) ? seg.translatedText : seg.text;
            ssb.append(piece);

            int segEnd = ssb.length();
            int segLen = segEnd - segStart;

            // 把原本 segment 裏的 spans 套回
            for (SpanSpec spec : seg.spans) {
                int startInSsb = segStart + spec.start;
                int endInSsb = segStart + spec.end;

                if (endInSsb > segEnd) {
                    endInSsb = segEnd;
                }

                if (startInSsb < segStart) {
                    startInSsb = segStart;
                }
                if (startInSsb >= endInSsb) {
                    continue;
                }

                ssb.setSpan(spec.span, startInSsb, endInSsb, spec.flags);
            }
        }

        return ssb;
    }
}
