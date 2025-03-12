package tianci.dev.xptranslatetext;

import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 用來翻譯多個 Segment
 */
class MultiSegmentTranslateTask extends android.os.AsyncTask<String, Void, Boolean> {

    private static final String TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single";
    // 簡易翻譯快取: (srcLang + tgtLang + text) -> translated
    private static final Map<String, String> translationCache = new ConcurrentHashMap<>();

    private final XC_MethodHook.MethodHookParam mParam;
    private final int mTranslationId;
    private final List<Segment> mSegments;

    public MultiSegmentTranslateTask(XC_MethodHook.MethodHookParam param,
                                     int translationId,
                                     List<Segment> segments) {
        this.mParam = param;
        this.mTranslationId = translationId;
        this.mSegments = segments;
    }

    @Override
    protected Boolean doInBackground(String... params) {
        if (params.length < 2) return false;
        String srcLang = params[0]; // e.g. "en"
        String tgtLang = params[1]; // e.g. "zh-TW"

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

            // 向 Google Translate API 發請求
            String result = translateOnline(txt, srcLang, tgtLang);
            if (result == null) {
                seg.translatedText = txt; // 翻譯失敗 => 用原文
            } else {
                seg.translatedText = result;
                translationCache.put(cacheKey, result);
            }
        }

        return true;
    }

    private String translateOnline(String text, String src, String dst) {
        try {
            String urlStr = TRANSLATE_URL
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

            return parseResult(sb.toString());
        } catch (Exception e) {
            XposedBridge.log("Error in translateOnline => " + e.getMessage());
            return null;
        }
    }

    private String parseResult(String json) {
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

    @Override
    protected void onPostExecute(Boolean success) {
        if (!success) {
            XposedBridge.log("MultiSegmentTranslateTask => failed.");
            return;
        }
        // 檢查 TextView 的 Tag, 確認尚未被新的翻譯取代
        TextView tv = (TextView) mParam.thisObject;
        Object tagObj = tv.getTag();
        if (!(tagObj instanceof Integer)) {
            XposedBridge.log("Tag mismatch => skip.");
            return;
        }
        int currentTag = (Integer) tagObj;
        if (currentTag == mTranslationId) {
            // 還是同一個 => 套用翻譯後結果
            HookMain.applyTranslatedSegments(mParam, mSegments);
        } else {
            XposedBridge.log("MultiSegmentTranslateTask => expired. currentTag=" + currentTag
                    + ", myId=" + mTranslationId);
        }
    }
}
