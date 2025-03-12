package tianci.dev.xptranslatetext;

import android.os.AsyncTask;
import android.os.Environment;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
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
    private final boolean useCloudTranslation;
    private final String packageName;

    public TranslateTask(XC_MethodHook.MethodHookParam param, int translationId, String packageName, boolean useCloudTranslation) {
        this.mParam = param;
        this.mTranslationId = translationId;
        this.packageName = packageName;
        this.useCloudTranslation = useCloudTranslation;
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

        if (useCloudTranslation) {
            return translateUsingCloud(sourceText, sourceLang, targetLang, cacheKey);
        } else {
            return translateUsingLocal(sourceText, packageName);
        }
    }

    private String translateUsingCloud(String sourceText, String sourceLang, String targetLang, String cacheKey) {
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

            String translated = parseResult(response.toString());
            if (translated != null) {
                translationCache.put(cacheKey, translated);
            }
            return translated;
        } catch (Exception e) {
            XposedBridge.log("Error during translation => " + e.getMessage());
            return null;
        }
    }

    private String translateUsingLocal(String sourceText, String packageName) {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File translationFile = new File(downloadDir, packageName + "_translations.json");

        try {
            JSONObject translations = new JSONObject();
            if (translationFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(translationFile));
                StringBuilder jsonBuilder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonBuilder.append(line);
                }
                reader.close();
                translations = new JSONObject(jsonBuilder.toString());
            }

            // 檢查是否有翻譯過
            if (translations.has(sourceText)) {
                return translations.getString(sourceText);
            } else {
                // 沒翻譯過，儲存至json
                translations.put(sourceText, sourceText);

                FileWriter writer = new FileWriter(translationFile);
                writer.write(translations.toString(4)); // Indent with 4 spaces
                writer.close();

                return sourceText;
            }
        } catch (Exception e) {
            XposedBridge.log("Error during local translation => " + e.getMessage());
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

        TextView textView = (TextView) mParam.thisObject;
        Object tagObj = textView.getTag();
        if (tagObj instanceof Integer) {
            int currentTag = (Integer) tagObj;
            if (currentTag == mTranslationId) {
                HookMain.applyTranslatedText(mParam, result);
            } else {
                XposedBridge.log("Translation result expired. Current tag=" + currentTag + ", myId=" + mTranslationId);
            }
        } else {
            XposedBridge.log("Translation result expired or tag mismatch. Tag=" + tagObj);
        }
    }
}