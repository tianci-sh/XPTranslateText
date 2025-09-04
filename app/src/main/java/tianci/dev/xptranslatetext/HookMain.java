package tianci.dev.xptranslatetext;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.webkit.WebView;
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
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import de.robv.android.xposed.XSharedPreferences;
import tianci.dev.xptranslatetext.rules.Telegram;
import tianci.dev.xptranslatetext.translate.MultiSegmentTranslateTask;
import tianci.dev.xptranslatetext.translate.Segment;
import tianci.dev.xptranslatetext.translate.SpanSpec;
import tianci.dev.xptranslatetext.translate.WebViewTranslationBridge;

public class HookMain implements IXposedHookLoadPackage {

    private static boolean isTranslating = false;

    private static final AtomicInteger atomicIdGenerator = new AtomicInteger(1);
    public static final String TRANSLATION_ID_KEY = "xp_translate_text:translationId";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals("tianci.dev.xptranslatetext")) return;

        XposedBridge.log("package => " + lpparam.packageName);

        XSharedPreferences prefs = new XSharedPreferences("tianci.dev.xptranslatetext", "xp_translate_text_configs");

        String sourceLang = "auto";
        String targetLang = "zh-TW";

        if (prefs.getFile().canRead()) {
            prefs.reload();
            sourceLang = prefs.getString("source_lang", sourceLang);
            targetLang = prefs.getString("target_lang", targetLang);

            XposedBridge.log("sourceLang=" + sourceLang + ", targetLang=" + targetLang);
        } else {
            XposedBridge.log("Cannot read XSharedPreferences => " + prefs.getFile().getAbsolutePath()
                    + ". Fallback to default: auto->zh-TW");
        }

        final String finalSourceLang = sourceLang;
        final String finalTargetLang = targetLang;

        hookTextView(lpparam, finalSourceLang, finalTargetLang);
        hookStaticLayout(lpparam, finalSourceLang, finalTargetLang);
        hookAllCustomSetTextClasss(lpparam, finalSourceLang, finalTargetLang);
        hookWebView(lpparam, finalSourceLang, finalTargetLang);

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

    /**
     * Replace StaticLayout.Builder.build():
     * - Try synchronous replacement from memory/DB (no network, no blocking beyond local DB)
     * - If unresolved, try quick local-service translation (background I/O + short await on UI)
     * - If still unresolved, prefetch async and return original layout
     */
    private void hookStaticLayout(XC_LoadPackage.LoadPackageParam lpparam, String finalSourceLang, String finalTargetLang) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.text.StaticLayout$Builder",
                    lpparam.classLoader,
                    "build",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable { // <-- IMPORTANT: declare throws Throwable
                            Object builder = param.thisObject;
                            if (builder == null) {
                                // Call through if something is off
                                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                            }

                            try {
                                // Read text from builder
                                CharSequence text = null;
                                try {
                                    text = (CharSequence) XposedHelpers.getObjectField(builder, "mText");
                                } catch (Throwable ignore) {
                                    try {
                                        text = (CharSequence) XposedHelpers.getObjectField(builder, "mSource");
                                    } catch (Throwable ignore2) {
                                        text = null;
                                    }
                                }
                                if (text == null || text.length() == 0) {
                                    return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                                }

                                // Read start/end
                                int start;
                                int end;
                                try {
                                    start = XposedHelpers.getIntField(builder, "mStart");
                                    end = XposedHelpers.getIntField(builder, "mEnd");
                                } catch (Throwable ignore) {
                                    start = 0;
                                    end = text.length();
                                }
                                if (start < 0) start = 0;
                                if (end > text.length()) end = text.length();
                                if (start >= end) {
                                    return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                                }

                                // Skip rule
                                if (isTranslationSkippedForClass(lpparam.packageName, builder.getClass().getName())) {
                                    return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                                }

                                CharSequence piece = text.subSequence(start, end);

                                // Build segments (preserve spans)
                                List<Segment> segments;
                                if (piece instanceof Spanned) {
                                    segments = parseAllSegments((Spanned) piece);
                                } else {
                                    segments = new ArrayList<>();
                                    segments.add(new Segment(0, piece.length(), piece.toString()));
                                }

                                // 1) memory/DB sync fast-path
                                boolean allResolved = MultiSegmentTranslateTask.fillSegmentsFromCacheOrDbOrNoNeed(
                                        segments, finalSourceLang, finalTargetLang);

                                // 2) quick local-service sync (short wait) if not all resolved
                                if (!allResolved) {
                                    boolean nowResolved = MultiSegmentTranslateTask.quickTranslateUnresolvedSegmentsViaLocal(
                                            segments, finalSourceLang, finalTargetLang, 1000 /*ms*/);
                                    if (nowResolved) {
                                        allResolved = true;
                                    }
                                }

                                if (allResolved) {
                                    // Replace builder text with translated spanned and build now
                                    CharSequence newSpanned = buildSpannedFromSegments(segments);

                                    try {
                                        XposedHelpers.setObjectField(builder, "mText", newSpanned);
                                    } catch (Throwable ignore) {
                                        try {
                                            XposedHelpers.setObjectField(builder, "mSource", newSpanned);
                                        } catch (Throwable ignore2) {
                                            // Cannot write back; call through
                                            return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                                        }
                                    }
                                    try {
                                        XposedHelpers.setIntField(builder, "mStart", 0);
                                        XposedHelpers.setIntField(builder, "mEnd", newSpanned.length());
                                    } catch (Throwable ignore) {}

                                    XposedBridge.log("[StaticLayout.Builder] applied translated text synchronously.");
                                    return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                                } else {
                                    // 3) prefetch async for next time
                                    MultiSegmentTranslateTask.prefetchSegmentsAsync(segments, finalSourceLang, finalTargetLang);
                                    return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[StaticLayout.Builder.build] replacement error => " + t.getMessage());
                                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("hook StaticLayout.Builder.build failed => " + t.getMessage());
        }
    }

    private void hookWebView(XC_LoadPackage.LoadPackageParam lpparam, String finalSourceLang, String finalTargetLang) {
        XposedHelpers.findAndHookConstructor(
                "android.webkit.WebView",
                lpparam.classLoader,
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        WebView webView = (WebView) param.thisObject;
                        Context ctx = (Context) param.args[0];

                        XposedBridge.log("[WebView Constructor] => Adding JS Bridge for translation...");

                        WebView.setWebContentsDebuggingEnabled(true);
                        XposedBridge.log("[WebView Constructor] => WebContentsDebuggingEnabled set to true.");

                        webView.addJavascriptInterface(
                                new WebViewTranslationBridge(webView),
                                "XPTranslateTextBridge"
                        );
                    }
                }
        );

        XposedHelpers.findAndHookMethod(
                "android.webkit.WebViewClient",
                lpparam.classLoader,
                "onPageFinished",
                WebView.class,
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        WebView webView = (WebView) param.args[0];
                        String url = (String) param.args[1];

                        if (webView == null) return;

                        XposedBridge.log("onPageFinished => " + url);

                        String jsCode = buildExtractTextJS(finalSourceLang, finalTargetLang);

                        webView.post(() -> {
                            webView.evaluateJavascript(jsCode, null);
                        });
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

                        if (isTranslationSkippedForClass(lpparam.packageName, param.thisObject.getClass().getName())) {
                            return;
                        }

                        int translationId = atomicIdGenerator.getAndIncrement();
                        Object target = param.thisObject;
                        markTranslationId(target, translationId);

                        List<Segment> segments;
                        if (originalText instanceof Spanned) {
                            segments = parseAllSegments((Spanned) originalText);
                        } else {
                            segments = new ArrayList<>();
                            segments.add(new Segment(0, originalText.length(), originalText.toString()));
                        }

                        // async translate + second call to original setText later
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
                                        markTranslationId(param.thisObject, translationId);

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

            // Rebuild new spanned from segments
            CharSequence newSpanned = buildSpannedFromSegments(segments);
            XposedBridge.log("( result ) => " + newSpanned);

            // Only apply for setText-like methods that take arguments and update View state
            if (param.args != null && param.args.length >= 1) {
                // e.g. TextView.setText(...) or custom setText(CharSequence)
                param.args[0] = newSpanned; // apply translated text
                XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
            } else {
                // For StaticLayout.Builder.build(): do NOTHING here.
                // Reason:
                // - The original build() already returned. Calling it again here cannot affect the caller.
                // - We handle StaticLayout replacement in hookStaticLayout() with XC_MethodReplacement.
            }
        } catch (Throwable t) {
            XposedBridge.log("Error applying translated segments: " + t.getMessage());
        } finally {
            isTranslating = false;
        }
    }

    private static void markTranslationId(Object target, int translationId) {
        try {
            XposedHelpers.setAdditionalInstanceField(target, TRANSLATION_ID_KEY, translationId);
        } catch (Throwable ignored) {
        }
        try {
            Method setTag = XposedHelpers.findMethodExactIfExists(target.getClass(), "setTag", Object.class);
            if (setTag != null) {
                XposedHelpers.callMethod(target, "setTag", translationId);
            }
        } catch (Throwable ignored) {
        }
    }

    // ---- Segments utils ----

    private static List<Segment> parseAllSegments(Spanned spanned) {
        List<Segment> segments = new ArrayList<>();
        int textLen = spanned.length();
        if (textLen == 0) {
            return segments;
        }

        // 1) collect all spans
        Object[] allSpans = spanned.getSpans(0, textLen, Object.class);

        // 2) gather boundaries and add 0, textLen
        Set<Integer> boundarySet = new HashSet<>();
        boundarySet.add(0);
        boundarySet.add(textLen);

        for (Object span : allSpans) {
            int st = spanned.getSpanStart(span);
            int en = spanned.getSpanEnd(span);
            boundarySet.add(st);
            boundarySet.add(en);
        }

        // 3) sort boundaries
        List<Integer> boundaries = new ArrayList<>(boundarySet);
        Collections.sort(boundaries);

        // 4) build segments by [i, i+1)
        for (int i = 0; i < boundaries.size() - 1; i++) {
            int segStart = boundaries.get(i);
            int segEnd = boundaries.get(i + 1);
            if (segStart >= segEnd) {
                continue;
            }

            CharSequence sub = spanned.subSequence(segStart, segEnd);
            Segment seg = new Segment(segStart, segEnd, sub.toString());

            // find all spans intersect with [segStart, segEnd)
            for (Object span : allSpans) {
                int spanStart = spanned.getSpanStart(span);
                int spanEnd = spanned.getSpanEnd(span);
                int flags = spanned.getSpanFlags(span);

                int intersectStart = Math.max(spanStart, segStart);
                int intersectEnd = Math.min(spanEnd, segEnd);

                if (intersectStart < intersectEnd) {
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

            // re-apply spans
            for (SpanSpec spec : seg.spans) {
                int startInSsb = segStart + spec.start;
                int endInSsb = segStart + spec.end;
                if (endInSsb > segEnd) endInSsb = segEnd;
                if (startInSsb < segStart) startInSsb = segStart;
                if (startInSsb >= endInSsb) continue;

                ssb.setSpan(spec.span, startInSsb, endInSsb, spec.flags);
            }
        }

        return ssb;
    }

    private String buildExtractTextJS(String finalSourceLang, String finalTargetLang) {
        return ""
                + "const EXCLUDE_TAGS = ['SCRIPT', 'STYLE', 'NOSCRIPT', 'IFRAME', 'SVG', 'CANVAS', 'HEAD', 'META', 'LINK'];\n"
                + "function extractAllTextNodes(rootElement, minLength = 20) {\n"
                + "    const textNodes = [];\n"
                + "    function traverse(node) {\n"
                + "        if (!node || EXCLUDE_TAGS.includes(node.nodeName)) return;\n"
                + "        if (node.nodeType === Node.TEXT_NODE) {\n"
                + "            const text = node.textContent.trim();\n"
                + "            if (text.length >= minLength) {\n"
                + "                textNodes.push(node);\n"
                + "            }\n"
                + "        } else {\n"
                + "            for (const child of node.childNodes) {\n"
                + "                traverse(child);\n"
                + "            }\n"
                + "        }\n"
                + "    }\n"
                + "    traverse(rootElement);\n"
                + "    return textNodes;\n"
                + "}\n"
                + "const pendingTranslations = {};\n"
                + "let runningTranslate = false;\n"
                + "function mainTranslateFlow() {\n"
                + "    if (runningTranslate) return;\n"
                + "    runningTranslate = true;\n"
                + "    let textNodes = extractAllTextNodes(document.body);\n"
                + "    textNodes = textNodes.map(node => {\n"
                + "        let top = Number.MAX_SAFE_INTEGER;\n"
                + "        if (node.parentElement) {\n"
                + "            try {\n"
                + "                const rect = node.parentElement.getBoundingClientRect();\n"
                + "                top = rect.top;\n"
                + "            } catch(e){}\n"
                + "        }\n"
                + "        return { node, top };\n"
                + "    }).sort((a, b) => a.top - b.top)\n"
                + "      .map(item => item.node);\n"
                + "    let currentIndex = 0;\n"
                + "    function processNextNode() {\n"
                + "        if (currentIndex >= textNodes.length) {\n"
                + "            console.log('[XPTranslate] Finished processing.');\n"
                + "            runningTranslate = false;\n"
                + "            return;\n"
                + "        }\n"
                + "        const node = textNodes[currentIndex++];\n"
                + "        const originalText = node.textContent.trim();\n"
                + "        const requestId = 'req_' + Math.random().toString(36).substr(2);\n"
                + "        pendingTranslations[requestId] = { node, timeoutId: null };\n"
                + "        const TIMEOUT_MS = 500;\n"
                + "        const timeoutId = setTimeout(() => {\n"
                + "            processNextNode();\n"
                + "        }, TIMEOUT_MS);\n"
                + "        pendingTranslations[requestId].timeoutId = timeoutId;\n"
                + "        try {\n"
                + "            window.XPTranslateTextBridge.translateFromJs(requestId, originalText, '" + finalSourceLang + "', '" + finalTargetLang + "');\n"
                + "        } catch(err) {\n"
                + "            console.error('[XPTranslate] translateFromJs error =>', err);\n"
                + "            clearTimeout(timeoutId);\n"
                + "            delete pendingTranslations[requestId];\n"
                + "            processNextNode();\n"
                + "        }\n"
                + "    }\n"
                + "    window.__xpTranslateProcessNextNode = processNextNode;\n"
                + "    processNextNode();\n"
                + "}\n"
                + "window.onXPTranslateCompleted = function(requestId, translatedText) {\n"
                + "    const record = pendingTranslations[requestId];\n"
                + "    if (!record) {\n"
                + "        console.warn('[XPTranslate] onXPTranslateCompleted => No record:', requestId);\n"
                + "        return;\n"
                + "    }\n"
                + "    if (record.timeoutId) {\n"
                + "        clearTimeout(record.timeoutId);\n"
                + "    }\n"
                + "    record.node.textContent = translatedText;\n"
                + "    delete pendingTranslations[requestId];\n"
                + "    console.log(`[XPTranslate] replaced => ${requestId}`, translatedText);\n"
                + "    if (typeof window.__xpTranslateProcessNextNode === 'function') {\n"
                + "        window.__xpTranslateProcessNextNode();\n"
                + "    }\n"
                + "};\n"
                + "function watchScrollAndTranslate() {\n"
                + "    let timer = null;\n"
                + "    window.addEventListener('scroll', () => {\n"
                + "        if (timer) clearTimeout(timer);\n"
                + "        timer = setTimeout(() => {\n"
                + "            const distToBottom = document.documentElement.scrollHeight - window.scrollY - window.innerHeight;\n"
                + "            if (distToBottom < 300) {\n"
                + "                mainTranslateFlow();\n"
                + "            }\n"
                + "        }, 400);\n"
                + "    });\n"
                + "}\n"
                + "mainTranslateFlow();\n"
                + "watchScrollAndTranslate();\n";
    }
}
