package tianci.dev.xptranslatetext;

import android.os.AsyncTask;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public class TranslateTask extends AsyncTask<String, Void, String> {
    private static final String TAG = "TranslateTask";
    private static final String TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single";
    private static final Map<String, String> translationCache = new ConcurrentHashMap<>();
    private final XC_MethodHook.MethodHookParam mParam;
    private final int mTranslationId;

    public TranslateTask(XC_MethodHook.MethodHookParam param, int translationId) {
        this.mParam = param;
        this.mTranslationId = translationId;
    }

    @Override
    protected String doInBackground(String... params) {
        String sourceText = params[0];
        String sourceLang = params[1]; // e.g., "en"
        String targetLang = params[2]; // e.g., "zh-TW"

        String cacheKey = sourceLang + ":" + targetLang + ":" + sourceText;
        String cachedResult = translationCache.get(cacheKey);
        if (cachedResult != null) {
            XposedBridge.log("Cache hit! text=" + sourceText);
            return cachedResult;
        }

        try {
            String urlStr = TRANSLATE_URL
                    + "?client=gtx"
                    + "&sl=" + URLEncoder.encode(sourceLang, "UTF-8")
                    + "&tl=" + URLEncoder.encode(targetLang, "UTF-8")
                    + "&dt=t"
                    + "&q=" + URLEncoder.encode(sourceText, "UTF-8");

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder response = new StringBuilder();
            String inputLine;

            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            // 解析返回的 JSON 結果
            // 返回的 JSON 結構為：[[["翻譯結果","原文",null,null,...]],null,"源語言",...]
            String translated = parseResult(response.toString());
            if (translated != null) {
                // 4) 放入快取
                translationCache.put(cacheKey, translated);
            }
            return translated;
        } catch (Exception e) {
            XposedBridge.log("Error during translation => " + e.getMessage());
            return null;
        }
    }

    private String parseResult(String json) {
        try {
            JSONArray jsonArray = new JSONArray(json);
            JSONArray translations = jsonArray.getJSONArray(0);
            StringBuilder translatedText = new StringBuilder();

            for (int i = 0; i < translations.length(); i++) {
                JSONArray translation = translations.getJSONArray(i);
                translatedText.append(translation.getString(0));
            }

            return translatedText.toString();
        } catch (JSONException e) {
            XposedBridge.log("Error parsing translation result " + e.getMessage());
            return null;
        }
    }

    @Override
    protected void onPostExecute(String result) {
        if (result == null) {
            XposedBridge.log("Translation failed (result is null).");
            return;
        }

        // 檢查 TextView 是否仍然對應同一個 translationId
        TextView textView = (TextView) mParam.thisObject;
        Object tagObj = textView.getTag();
        if (tagObj instanceof Integer) {
            int currentTag = (Integer) tagObj;
            if (currentTag == mTranslationId) {
                // Tag 沒被改變，代表這個 TextView 仍然是對應當初要翻譯的那個
                HookMain.applyTranslatedText(mParam, result);
            } else {
                // Tag 已被新的翻譯任務改掉了 => 這筆結果過期了，不應該再寫入
                XposedBridge.log("Translation result expired. Current tag=" + currentTag + ", myId=" + mTranslationId);
            }
        } else {
            XposedBridge.log("Translation result expired or tag mismatch. Tag=" + tagObj);
        }
    }
}
