package tianci.dev.xptranslatetext;

import android.os.AsyncTask;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public class TranslateTask extends AsyncTask<String, Void, String> {
    private static final String TAG = "TranslateTask";
    private static final String TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single";

    // 這裡多帶入 XC_MethodHook.MethodHookParam，讓我們在 onPostExecute() 取得並回寫參數
    private final XC_MethodHook.MethodHookParam mParam;

    public TranslateTask(XC_MethodHook.MethodHookParam param) {
        this.mParam = param;
    }

    @Override
    protected String doInBackground(String... params) {
        String sourceText = params[0];
        String sourceLang = params[1]; // e.g., "en"
        String targetLang = params[2]; // e.g., "zh-TW"

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
            return parseResult(response.toString());

        } catch (Exception e) {
            Log.e(TAG, "Error during translation", e);
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
            Log.e(TAG, "Error parsing translation result", e);
            return null;
        }
    }

    @Override
    protected void onPostExecute(String result) {
        if (result != null) {
            // 將翻譯結果寫回 param.args[0] 並呼叫原方法
            HookMain.applyTranslatedText(mParam, result);
        } else {
            XposedBridge.log("Translation failed (result is null).");
        }
    }
}
