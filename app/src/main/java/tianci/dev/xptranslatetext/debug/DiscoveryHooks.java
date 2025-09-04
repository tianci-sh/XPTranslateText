package tianci.dev.xptranslatetext.debug;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.BoringLayout;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Discovery mode hooks to locate the real text rendering pipeline.
 * Covers: Layout/StaticLayout/BoringLayout, PrecomputedText, Canvas text APIs,
 * Compose presence, Flutter semantics bridge, React Native hints.
 */
public final class DiscoveryHooks {

    // Simple LRU to dedupe logs and avoid flooding
    private static final class SeenLimiter {
        private final int capacity;
        private final LinkedHashMap<String, Boolean> map;

        SeenLimiter(int capacity) {
            this.capacity = capacity;
            this.map = new LinkedHashMap<String, Boolean>(capacity, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > SeenLimiter.this.capacity;
                }
            };
        }
        boolean shouldLog(String key) {
            synchronized (map) {
                if (map.containsKey(key)) return false;
                map.put(key, Boolean.TRUE);
                return true;
            }
        }
    }

    private static final SeenLimiter SEEN = new SeenLimiter(4096);

    public static void install(final XC_LoadPackage.LoadPackageParam lpparam,
                               final int minTextLen,
                               final boolean enableHeavy) {
        try {
            hookFrameworkTextPipeline(lpparam, minTextLen);
        } catch (Throwable t) {
            XposedBridge.log("[Discovery] framework pipeline hook error: " + t);
        }

        try {
            hookCanvasDeep(lpparam, minTextLen);
        } catch (Throwable t) {
            XposedBridge.log("[Discovery] canvas hook error: " + t);
        }

        try {
            detectCompose(lpparam);
        } catch (Throwable t) {
            XposedBridge.log("[Discovery] compose detection error: " + t);
        }

        try {
            detectFlutterAndHookSemantics(lpparam, minTextLen);
        } catch (Throwable t) {
            XposedBridge.log("[Discovery] flutter detection/semantics hook error: " + t);
        }

        try {
            detectReactNativeHints(lpparam, minTextLen);
        } catch (Throwable t) {
            XposedBridge.log("[Discovery] react-native hints hook error: " + t);
        }

        if (enableHeavy) {
            // Optional heavy hooks for precomputed text
            try {
                hookPrecomputedText(lpparam, minTextLen);
            } catch (Throwable t) {
                XposedBridge.log("[Discovery] precomputed text hook error: " + t);
            }
        }

        XposedBridge.log("[Discovery] installed for: " + lpparam.packageName);
    }

    // -------- Framework-level text pipeline --------

    private static void hookFrameworkTextPipeline(final XC_LoadPackage.LoadPackageParam lpparam,
                                                  final int minTextLen) {
        // Hook Layout.draw(Canvas)
        XposedHelpers.findAndHookMethod(
                "android.text.Layout",
                lpparam.classLoader,
                "draw",
                Canvas.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        tryLogLayoutText("Layout.draw(Canvas)", param.thisObject, minTextLen);
                    }
                }
        );

        // Hook Layout.draw(Canvas, Path, Paint, int)
        XposedHelpers.findAndHookMethod(
                "android.text.Layout",
                lpparam.classLoader,
                "draw",
                Canvas.class, Path.class, Paint.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        tryLogLayoutText("Layout.draw(Canvas,Path,Paint,int)", param.thisObject, minTextLen);
                    }
                }
        );

        // Hook StaticLayout.Builder.obtain(...) & build()
        try {
            Class<?> builder = XposedHelpers.findClass("android.text.StaticLayout$Builder", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    builder,
                    "obtain",
                    CharSequence.class, int.class, int.class, TextPaint.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            CharSequence cs = (CharSequence) param.args[0];
                            logText("[StaticLayout.Builder.obtain]", cs, minTextLen);
                        }
                    }
            );
            XposedHelpers.findAndHookMethod(
                    builder,
                    "build",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object layout = param.getResult();
                            tryLogLayoutText("[StaticLayout.Builder.build -> Layout]", layout, minTextLen);
                        }
                    }
            );
        } catch (Throwable ignored) { /* not all devices */ }

        // Common StaticLayout ctor (old signature)
        tryHookStaticLayoutCtor(lpparam, minTextLen,
                CharSequence.class, int.class, int.class, TextPaint.class, int.class,
                Layout.Alignment.class, android.text.TextDirectionHeuristic.class,
                float.class, float.class, boolean.class, TextUtils.TruncateAt.class, int.class, int.class);

        // Another common ctor (shorter signature)
        tryHookStaticLayoutCtor(lpparam, minTextLen,
                CharSequence.class, TextPaint.class, int.class, Layout.Alignment.class,
                float.class, float.class, boolean.class);

        // BoringLayout draw
        XposedHelpers.findAndHookMethod(
                "android.text.BoringLayout",
                lpparam.classLoader,
                "draw",
                Canvas.class, int.class, int.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        tryLogLayoutText("BoringLayout.draw", param.thisObject, minTextLen);
                    }
                }
        );
    }

    private static void tryHookStaticLayoutCtor(XC_LoadPackage.LoadPackageParam lpparam,
                                                int minTextLen, Object... sig) {
        try {
            XposedHelpers.findAndHookConstructor(
                    "android.text.StaticLayout",
                    lpparam.classLoader,
                    sig,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            CharSequence cs = safeGetCharSequenceArg(param.args);
                            logText("[StaticLayout.<init>]", cs, minTextLen);
                        }
                    }
            );
        } catch (Throwable ignored) { }
    }

    private static CharSequence safeGetCharSequenceArg(Object[] args) {
        if (args == null || args.length == 0) return null;
        if (args[0] instanceof CharSequence) return (CharSequence) args[0];
        return null;
    }

    private static void tryLogLayoutText(String tag, Object layoutObj, int minTextLen) {
        try {
            if (layoutObj == null) return;
            // Layout has protected CharSequence mText
            CharSequence cs = (CharSequence) XposedHelpers.getObjectField(layoutObj, "mText");
            logText(tag, cs, minTextLen);
        } catch (Throwable ignored) { }
    }

    // -------- Canvas deep text APIs --------

    private static void hookCanvasDeep(final XC_LoadPackage.LoadPackageParam lpparam,
                                       final int minTextLen) {
        // drawText(String, ...)
        XposedHelpers.findAndHookMethod("android.graphics.Canvas", lpparam.classLoader, "drawText",
                String.class, float.class, float.class, Paint.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        logText("Canvas.drawText(str)", (String) param.args[0], minTextLen);
                    }
                });

        // drawText(String, start, end, ...)
        XposedHelpers.findAndHookMethod("android.graphics.Canvas", lpparam.classLoader, "drawText",
                String.class, int.class, int.class, float.class, float.class, Paint.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            String s = (String) param.args[0];
                            int st = (int) param.args[1], en = (int) param.args[2];
                            if (s != null && st >= 0 && en <= s.length() && st < en) {
                                logText("Canvas.drawText(str,range)", s.substring(st, en), minTextLen);
                            }
                        } catch (Throwable ignored) { }
                    }
                });

        // drawTextRun(CharSequence, ...)
        try {
            XposedHelpers.findAndHookMethod("android.graphics.Canvas", lpparam.classLoader, "drawTextRun",
                    CharSequence.class, int.class, int.class, int.class, int.class,
                    float.class, float.class, boolean.class, Paint.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                CharSequence cs = (CharSequence) param.args[0];
                                int st = (int) param.args[1], en = (int) param.args[2];
                                if (cs != null && st >= 0 && en <= cs.length() && st < en) {
                                    logText("Canvas.drawTextRun(cs)", cs.subSequence(st, en), minTextLen);
                                }
                            } catch (Throwable ignored) { }
                        }
                    });
        } catch (Throwable ignored) { }

        // drawTextOnPath(String, ...)
        try {
            XposedHelpers.findAndHookMethod("android.graphics.Canvas", lpparam.classLoader, "drawTextOnPath",
                    String.class, Path.class, float.class, float.class, Paint.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            logText("Canvas.drawTextOnPath(str)", (String) param.args[0], minTextLen);
                        }
                    });
        } catch (Throwable ignored) { }
    }

    // -------- PrecomputedText (AppCompatTextView async text) --------

    private static void hookPrecomputedText(final XC_LoadPackage.LoadPackageParam lpparam,
                                            final int minTextLen) {
        // android.text.PrecomputedText.create(CharSequence, Params)
        try {
            XposedHelpers.findAndHookMethod(
                    "android.text.PrecomputedText",
                    lpparam.classLoader,
                    "create",
                    CharSequence.class,
                    Class.forName("android.text.PrecomputedText$Params"),
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            CharSequence cs = (CharSequence) param.args[0];
                            logText("PrecomputedText.create", cs, minTextLen);
                        }
                    }
            );
        } catch (Throwable ignored) { }

        // androidx.core.text.PrecomputedTextCompat.create(CharSequence, Params)
        try {
            Class<?> params = XposedHelpers.findClass("androidx.core.text.PrecomputedTextCompat$Params", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "androidx.core.text.PrecomputedTextCompat",
                    lpparam.classLoader,
                    "create",
                    CharSequence.class, params,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            CharSequence cs = (CharSequence) param.args[0];
                            logText("PrecomputedTextCompat.create", cs, minTextLen);
                        }
                    }
            );
        } catch (Throwable ignored) { }
    }

    // -------- Compose detection --------

    private static void detectCompose(final XC_LoadPackage.LoadPackageParam lpparam) {
        // ComposeView constructors
        tryHookCtorLog(lpparam, "androidx.compose.ui.platform.ComposeView", "[Compose] ComposeView ctor");
        tryHookCtorLog(lpparam, "androidx.compose.ui.platform.AndroidComposeView", "[Compose] AndroidComposeView ctor");

        // (Optional) TextPainter.paint – not guaranteed to exist across versions
        try {
            Class<?> tp = XposedHelpers.findClass("androidx.compose.ui.text.TextPainter", lpparam.classLoader);
            XposedBridge.hookAllMethods(tp, "paint", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    XposedBridge.log("[Compose] TextPainter.paint invoked");
                }
            });
        } catch (Throwable ignored) { }
    }

    private static void tryHookCtorLog(final XC_LoadPackage.LoadPackageParam lpparam,
                                       final String fqcn, final String tag) {
        try {
            XposedHelpers.findAndHookConstructor(fqcn, lpparam.classLoader, Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    XposedBridge.log(tag + " (Context)");
                }
            });
        } catch (Throwable ignored) { }
        try {
            XposedHelpers.findAndHookConstructor(fqcn, lpparam.classLoader, Context.class, android.util.AttributeSet.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    XposedBridge.log(tag + " (Context, AttributeSet)");
                }
            });
        } catch (Throwable ignored) { }
    }

    // -------- Flutter detection + semantics hook --------

    private static void detectFlutterAndHookSemantics(final XC_LoadPackage.LoadPackageParam lpparam,
                                                      final int minTextLen) {
        // Detect FlutterView
        tryHookCtorLog(lpparam, "io.flutter.embedding.android.FlutterView", "[Flutter] FlutterView ctor");
        tryHookCtorLog(lpparam, "io.flutter.view.FlutterView", "[Flutter] (legacy) FlutterView ctor");

        // AccessibilityBridge.updateSemantics - grab labels/value/hint
        // Old and new Flutter both keep an AccessibilityBridge which receives semantics updates.
        try {
            Class<?> bridge = XposedHelpers.findClass("io.flutter.view.AccessibilityBridge", lpparam.classLoader);
            XposedBridge.hookAllMethods(bridge, "updateSemantics", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    // Best-effort reflection: the first arg is usually a list/map of semantics nodes.
                    Object arg0 = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                    if (arg0 == null) return;
                    String s = String.valueOf(arg0);
                    // This string is large; we only log once per hash to avoid flooding.
                    logText("[Flutter] AccessibilityBridge.updateSemantics", s, minTextLen);
                }
            });
        } catch (Throwable ignored) { }
    }

    // -------- RN hints --------

    private static void detectReactNativeHints(final XC_LoadPackage.LoadPackageParam lpparam,
                                               final int minTextLen) {
        // ReactTextView#setText (often still TextView path, but log anyway)
        try {
            XposedHelpers.findAndHookMethod(
                    "com.facebook.react.views.text.ReactTextView",
                    lpparam.classLoader,
                    "setText",
                    CharSequence.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            CharSequence cs = (CharSequence) param.args[0];
                            logText("[RN] ReactTextView.setText", cs, minTextLen);
                        }
                    }
            );
        } catch (Throwable ignored) { }

        // ShadowNode text
        try {
            XposedHelpers.findAndHookMethod(
                    "com.facebook.react.views.text.ReactTextShadowNode",
                    lpparam.classLoader,
                    "setText",
                    String.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            logText("[RN] ReactTextShadowNode.setText", (String) param.args[0], minTextLen);
                        }
                    }
            );
        } catch (Throwable ignored) { }
    }

    // -------- Common logging helpers --------

    private static void logText(String tag, CharSequence cs, int minTextLen) {
        if (cs == null) return;
        String s = cs.toString().trim();
        if (s.length() < minTextLen) return;
        String key = tag + ":" + hashTrim(s);
        if (SEEN.shouldLog(key)) {
            XposedBridge.log(tag + " => " + shorten(s, 400));
        }
    }

    private static void logText(String tag, String s, int minTextLen) {
        if (s == null) return;
        String t = s.trim();
        if (t.length() < minTextLen) return;
        String key = tag + ":" + hashTrim(t);
        if (SEEN.shouldLog(key)) {
            XposedBridge.log(tag + " => " + shorten(t, 400));
        }
    }

    private static String shorten(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + " ...(" + s.length() + " chars)";
    }

    private static String hashTrim(String s) {
        // quick non-cryptographic hash for dedupe key
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = (31 * h) + s.charAt(i);
        }
        return Integer.toHexString(h);
    }
}
