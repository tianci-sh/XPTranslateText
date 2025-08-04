package tianci.dev.xptranslatetext;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自訂翻譯來源管理器
 * 負責載入和查詢靜態 JSON 翻譯資料
 */
public class CustomTranslationManager {
    private static final String TAG = "CustomTranslationManager";

    // 快取自訂翻譯資料
    private static final Map<String, Map<String, String>> customTranslations = new ConcurrentHashMap<>();
    private static final Map<String, String> packageToTarget = new ConcurrentHashMap<>();
    private static volatile boolean isLoaded = false;
    private static volatile boolean isLoading = false;

    // 儲存 API URL
    private static String customApiUrl = null;

    /**
     * 初始化自訂翻譯資料（開機後立即獲取）- 舊版本兼容
     */
    public static void initialize(Context context) {
        initialize(context, null);
    }

    /**
     * 初始化自訂翻譯資料（開機後立即獲取）- 接收 API URL
     */
    public static void initialize(Context context, String apiUrl) {
        customApiUrl = apiUrl;

        log("Custom API URL received: " + (apiUrl != null ? apiUrl : "null"));

        // 開機後立即同步載入自訂翻譯
        loadFromUrlSync();
    }

    /**
     * 同步載入翻譯資料（僅在初始化時使用）
     */
    private static void loadFromUrlSync() {
        if (isLoading) {
            log("Translation loading already in progress, skipping");
            return;
        }

        if (isLoaded) {
            log("Translations already loaded, skipping reload");
            return;
        }

        isLoading = true;

        // 在背景線程中執行網路請求
        new Thread(() -> {
            try {
                loadFromUrl();
            } finally {
                isLoading = false;
            }
        }).start();
    }

    /**
     * 從線上 URL 載入翻譯資料
     */
    private static void loadFromUrl() {
        if (customApiUrl == null) {
            log("No custom API URL configured, skipping custom translations loading");
            return;
        }

        try {
            log("Attempting to load from URL: " + customApiUrl);
            URL url = new URL(customApiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)");

            log("Connecting to server...");
            int responseCode = connection.getResponseCode();
            log("Server response code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                String jsonString = readInputStream(connection.getInputStream());
                log("Received JSON data, length: " + jsonString.length());
                parseTranslations(jsonString);
                log("Loaded custom translations from online API");
            } else {
                log("Failed to load custom translations from online API: HTTP " + responseCode);
                // 嘗試讀取錯誤訊息
                try {
                    InputStream errorStream = connection.getErrorStream();
                    if (errorStream != null) {
                        String errorMessage = readInputStream(errorStream);
                        log("Server error message: " + errorMessage);
                    }
                } catch (Exception errorEx) {
                    log("Could not read error stream: " + errorEx.getClass().getSimpleName());
                }
            }
            connection.disconnect();
        } catch (java.net.UnknownHostException e) {
            log("Error loading custom translations: Unknown host - " + e.getMessage());
        } catch (java.net.SocketTimeoutException e) {
            log("Error loading custom translations: Connection timeout - " + e.getMessage());
        } catch (javax.net.ssl.SSLException e) {
            log("Error loading custom translations: SSL error - " + e.getMessage());
        } catch (java.net.ConnectException e) {
            log("Error loading custom translations: Connection refused - " + e.getMessage());
        } catch (Exception e) {
            String errorMessage = e.getMessage();
            if (errorMessage == null) {
                errorMessage = e.getClass().getSimpleName();
            }
            log("Error loading custom translations from online API: " + errorMessage);
            log("Exception type: " + e.getClass().getName());
        }
    }

    /**
     * 解析翻譯 JSON 資料
     */
    private static void parseTranslations(String jsonString) {
        try {
            log("Parsing JSON: " + jsonString.substring(0, Math.min(200, jsonString.length())) + "...");

            JSONObject root = new JSONObject(jsonString);
            Iterator<String> appKeys = root.keys();

            while (appKeys.hasNext()) {
                String appKey = appKeys.next();
                JSONObject appConfig = root.getJSONObject(appKey);

                String packageName = appConfig.getString("packagename");
                String targetLang = appConfig.getString("target");

                packageToTarget.put(packageName, targetLang);

                // 檢查是否有 translations 字段
                if (!appConfig.has("translations")) {
                    log("Warning: No 'translations' field found for " + packageName + ", checking direct translation keys");

                    // 如果沒有 translations 字段，直接從 appConfig 中提取翻譯
                    Map<String, String> translationMap = new HashMap<>();
                    Iterator<String> directKeys = appConfig.keys();
                    while (directKeys.hasNext()) {
                        String key = directKeys.next();
                        if (!key.equals("packagename") && !key.equals("target")) {
                            String value = appConfig.getString(key);
                            translationMap.put(key.trim(), value);
                        }
                    }

                    if (!translationMap.isEmpty()) {
                        customTranslations.put(packageName, translationMap);
                        log("Loaded " + translationMap.size() + " direct translations for " + packageName);
                    }
                } else {
                    // 正常的 translations 字段處理
                    JSONObject translations = appConfig.getJSONObject("translations");
                    Map<String, String> translationMap = new HashMap<>();

                    Iterator<String> translationKeys = translations.keys();
                    while (translationKeys.hasNext()) {
                        String key = translationKeys.next();
                        String value = translations.getString(key);
                        translationMap.put(key.trim(), value);
                    }

                    customTranslations.put(packageName, translationMap);
                    log("Loaded " + translationMap.size() + " translations for " + packageName);
                }
            }

            isLoaded = true;
            log("Custom translations parsing completed successfully");
        } catch (JSONException e) {
            log("Error parsing custom translations JSON: " + e.getMessage());
        }
    }

    /**
     * 查詢自訂翻譯
     * @param packageName 應用程式包名
     * @param originalText 原始文字
     * @param targetLang 目標語言
     * @return 翻譯結果，如果沒有找到則返回 null
     */
    public static String getCustomTranslation(String packageName, String originalText, String targetLang) {
        // 如果正在載入中，等待最多 3 秒
        if (isLoading && !isLoaded) {
            log("Custom translations still loading, waiting...");
            int waitCount = 0;
            while (isLoading && waitCount < 30) { // 等待最多 3 秒 (30 * 100ms)
                try {
                    Thread.sleep(100);
                    waitCount++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log("Wait completed. isLoaded: " + isLoaded + ", waitCount: " + waitCount);
        }
        if (!isLoaded || packageName == null || originalText == null) {
            return null;
        }

        // 檢查是否有該應用的自訂翻譯
        Map<String, String> appTranslations = customTranslations.get(packageName);
        if (appTranslations == null) {
            return null;
        }

        // 檢查目標語言是否匹配
        String configuredTarget = packageToTarget.get(packageName);
        if (configuredTarget != null && !configuredTarget.equals(targetLang)) {
            return null;
        }

        // 查找精確匹配
        String trimmedText = originalText.trim();
        String result = appTranslations.get(trimmedText);

        if (result != null) {
            log("Found custom translation for " + packageName + ": " + trimmedText + " -> " + result);
            return result;
        }

        // 查找不區分大小寫的匹配
        for (Map.Entry<String, String> entry : appTranslations.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(trimmedText)) {
                log("Found case-insensitive custom translation for " + packageName + ": " + trimmedText + " -> " + entry.getValue());
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * 檢查是否有該應用的自訂翻譯配置
     */
    public static boolean hasCustomTranslations(String packageName) {
        return isLoaded && customTranslations.containsKey(packageName);
    }

    /**
     * 讀取 InputStream 內容
     */
    private static String readInputStream(InputStream is) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }

    /**
     * 日誌輸出
     */
    private static void log(String message) {
        Log.d(TAG, message);
        //XposedBridge.log("[" + TAG + "] " + message);
    }

    /**
     * 清除快取，強制重新載入
     */
    public static void clearCache() {
        customTranslations.clear();
        packageToTarget.clear();
        isLoaded = false;
        log("Custom translations cache cleared");
    }
}
