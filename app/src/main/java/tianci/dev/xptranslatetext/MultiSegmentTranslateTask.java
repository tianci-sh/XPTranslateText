package tianci.dev.xptranslatetext;

import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 用來翻譯多個 Segment
 */
class MultiSegmentTranslateTask {

    private static final ExecutorService TRANSLATION_EXECUTOR = Executors.newFixedThreadPool(20);

    // 簡易翻譯快取: (srcLang + tgtLang + text) -> translated
    private static final Map<String, String> translationCache = new ConcurrentHashMap<>();

    private static final String[] GEMINI_API_KEYS = {

    };
    private static final long[] geminiKeyBlockUntil = new long[GEMINI_API_KEYS.length];
    private static int geminiKeyIndex = 0;

    public static void translateSegmentsAsync(
            final XC_MethodHook.MethodHookParam param,
            final int translationId,
            final List<Segment> segments,
            final String srcLang,
            final String tgtLang
    ) {
        TRANSLATION_EXECUTOR.submit(() -> {

            doTranslateSegments(segments, srcLang, tgtLang);

            new Handler(Looper.getMainLooper()).post(() -> {
                // 確認 TextView 的 Tag 是否還是同一個 translationId
                TextView tv = (TextView) param.thisObject;
                Object tagObj = tv.getTag();
                if (!(tagObj instanceof Integer)) {
                    XposedBridge.log("Tag mismatch => skip.");
                    return;
                }
                int currentTag = (Integer) tagObj;
                if (currentTag == translationId) {
                    // 還是同一個 => 套用翻譯後結果
                    HookMain.applyTranslatedSegments(param, segments);
                } else {
                    XposedBridge.log("MultiSegmentTranslateTask => expired. currentTag=" + currentTag
                            + ", myId=" + translationId);
                }
            });
        });
    }


    private static void doTranslateSegments(List<Segment> mSegments, String srcLang, String tgtLang) {
        // 逐段翻譯
        for (Segment seg : mSegments) {
            String txt = seg.text;
            if (txt.trim().isEmpty()) {
                seg.translatedText = txt;
                continue;
            }

            // 查快取
            String cacheKey = srcLang + ":" + tgtLang + ":" + txt;
            if (translationCache.containsKey(cacheKey)) {
                seg.translatedText = translationCache.get(cacheKey);
                continue;
            }

            if (!isTranslationNeeded(txt)) {
                seg.translatedText = txt;
                continue;
            }

            String result = null;
            if (GEMINI_API_KEYS.length > 0) {
                result = translateByGemini(txt, tgtLang);
            }

            // When free Gemini got 429 failed
            if (result == null) {
                result = translateByGoogleFreeApi(txt, srcLang, tgtLang);
            }

            if (result == null) {
                seg.translatedText = txt; // 翻譯失敗 => 用原文
            } else {
                seg.translatedText = result;
                translationCache.put(cacheKey, result);
            }
        }
    }

    private static String translateByGemini(String text, String dst) {
        long now = System.currentTimeMillis();

        int triedCount = 0;

        while (triedCount < GEMINI_API_KEYS.length) {
            int usableIndex = findNextUsableKey(now);
            if (usableIndex < 0) {
                return null;
            }

            String currentKey = GEMINI_API_KEYS[usableIndex];

            try {
                String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-lite:generateContent?key=" + currentKey;

                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                String requestBody = String.format("{\"contents\": [{\"role\": \"user\",\"parts\": [{\"text\": \"%s\"}]}],\"systemInstruction\": {\"role\": \"user\",\"parts\": [{\"text\": \"- Please translate the following content into \\\"%s\\\" only, without any additional explanations or descriptions.\"}]},\"generationConfig\": {\"temperature\": 1,\"topK\": 40,\"topP\": 0.95,\"maxOutputTokens\": 8192,\"responseMimeType\": \"text/plain\"}}", text, dst);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = requestBody.getBytes("UTF-8");
                    os.write(input, 0, input.length);
                    os.flush();
                }

                int status = conn.getResponseCode();
                if (status != 200) {
                    if (status == 429) {
                        // 遇到 Rate Limit => 此 key 冷卻 1 分鐘
                        geminiKeyBlockUntil[usableIndex] = now + 60_000;
                        XposedBridge.log("Gemini2.0 => 429: Key index " + usableIndex
                                + " is blocked until " + geminiKeyBlockUntil[usableIndex]);

                        // 換下一組 key 繼續嘗試
                        triedCount++;
                        continue;
                    }

                    XposedBridge.log("Gemini2.0: Non-200 code => " + status);
                    try (BufferedReader errIn = new BufferedReader(new InputStreamReader(conn.getErrorStream(), "UTF-8"))) {
                        StringBuilder errSb = new StringBuilder();
                        String eLine;
                        while ((eLine = errIn.readLine()) != null) {
                            errSb.append(eLine);
                        }
                        XposedBridge.log("Gemini2.0 error => " + errSb);
                    }
                    return null;
                }

                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        sb.append(line);
                    }
                    return parseGeminiResult(sb.toString());
                }
            } catch (Exception e) {
                XposedBridge.log("Error in translateOnline => " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    private static String translateByGoogleFreeApi(String text, String src, String dst) {
        try {
            String urlStr = "https://translate.googleapis.com/translate_a/single"
                    + "?client=gtx"
                    + "&sl=" + URLEncoder.encode(src, "UTF-8")
                    + "&tl=" + URLEncoder.encode(dst, "UTF-8")
                    + "&dt=t"
                    + "&q=" + URLEncoder.encode(text, "UTF-8");

            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                sb.append(line);
            }
            in.close();

            return parseGoogleFreeApiResult(sb.toString());
        } catch (Exception e) {
            XposedBridge.log("Error in translateOnline => " + e.getMessage());
            return null;
        }
    }

    private static String parseGoogleFreeApiResult(String json) {
        try {
            JSONArray jsonArray = new JSONArray(json);
            JSONArray translations = jsonArray.getJSONArray(0);
            StringBuilder translatedText = new StringBuilder();
            for (int i = 0; i < translations.length(); i++) {
                JSONArray arr = translations.getJSONArray(i);
                translatedText.append(arr.getString(0));
            }
            return translatedText.toString();
        } catch (JSONException e) {
            XposedBridge.log("Error parsing translation result => " + e.getMessage());
            return null;
        }
    }

    private static String parseGeminiResult(String json) {
        try {
            // 使用 org.json
            JSONObject root = new JSONObject(json);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                return null;
            }

            JSONObject firstCandidate = candidates.getJSONObject(0);
            JSONObject content = firstCandidate.optJSONObject("content");
            if (content == null) return null;

            JSONArray parts = content.optJSONArray("parts");
            if (parts == null || parts.length() == 0) {
                return null;
            }

            JSONObject firstPart = parts.getJSONObject(0);
            String text = firstPart.optString("text", null);
            if (text == null) return null;

            XposedBridge.log("Gemini Response " + text.trim());
            return text.trim();
        } catch (JSONException e) {
            XposedBridge.log("Error parsing Gemini2.0 response => " + e.getMessage());
            return null;
        }
    }

    private static int findNextUsableKey(long now) {
        for (int i = 0; i < GEMINI_API_KEYS.length; i++) {
            int idx = (geminiKeyIndex + i) % GEMINI_API_KEYS.length;
            if (now >= geminiKeyBlockUntil[idx]) {
                geminiKeyIndex = (idx + 1) % GEMINI_API_KEYS.length;
                return idx;
            }
        }
        return -1;
    }

    private static boolean isTranslationNeeded(String string) {
        // 純數字
        if (string.matches("^\\d+$")) {
            return false;
        }

        // 座標
        if (string.matches("^\\d{1,3}\\.\\d+$")) {
            return false;
        }

        return true;
    }
}
