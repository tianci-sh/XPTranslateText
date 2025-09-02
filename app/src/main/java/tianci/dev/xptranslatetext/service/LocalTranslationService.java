package tianci.dev.xptranslatetext.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import tianci.dev.xptranslatetext.R;
import tianci.dev.xptranslatetext.util.ModelInfoUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 前景服務：在 127.0.0.1:18181 啟動極簡 HTTP 服務
 * /translate?src=xx&dst=yy&q=...
 * - src=auto 時使用 ML Kit Language ID 自動判斷
 */
public class LocalTranslationService extends Service {

    public static final String ACTION_START = "tianci.dev.xptranslatetext.action.START";
    public static final String ACTION_STOP = "tianci.dev.xptranslatetext.action.STOP";

    public static final int PORT = 18181;
    private static final String CHANNEL_ID = "local_translation_channel";

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private ExecutorService clientExecutor;
    private Thread serverThread;

    public static boolean isRunning() {
        return RUNNING.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        clientExecutor = Executors.newCachedThreadPool();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!RUNNING.get()) {
            startForeground(1, buildNotification());
            startServer();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServer();
        if (clientExecutor != null) clientExecutor.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Local Translation",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("XPTranslateText 本地翻譯服務");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.server_running, PORT))
                .build();
    }

    private void startServer() {
        if (RUNNING.get()) return;
        RUNNING.set(true);
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT, 0);
                while (RUNNING.get()) {
                    final Socket socket = serverSocket.accept();
                    clientExecutor.execute(() -> handleClient(socket));
                }
            } catch (IOException e) {
                Log.e("LocalTranslation", "Server error: " + e.getMessage());
            } finally {
                RUNNING.set(false);
                if (serverSocket != null) {
                    try { serverSocket.close(); } catch (IOException ignored) {}
                }
            }
        }, "LocalTranslationServer");
        serverThread.start();
    }

    private void stopServer() {
        RUNNING.set(false);
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
    }

    private void handleClient(Socket socket) {
        try (InputStream is = socket.getInputStream(); OutputStream os = socket.getOutputStream()) {
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));

            String requestLine = br.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                respond(os, 400, json("error", "bad request"));
                return;
            }

            // 只處理 GET 路由
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                respond(os, 400, json("error", "bad request"));
                return;
            }
            String path = parts[1];

            // 讀掉 header，直到空行
            String line;
            while ((line = br.readLine()) != null && !line.isEmpty()) { /* skip */ }

            if (path.startsWith("/health")) {
                respond(os, 200, json("status", "ok"));
                return;
            }

            if (!path.startsWith("/translate")) {
                respond(os, 404, json("error", "not found"));
                return;
            }

            Map<String, String> query = parseQuery(path);
            String text = query.get("q");
            String src = query.get("src");
            String dst = query.get("dst");

            if (text == null || text.isEmpty()) {
                respond(os, 400, json("error", "q required"));
                return;
            }
            if (dst == null || dst.isEmpty()) {
                // 從偏好取目標語言
                SharedPreferences sp = getSharedPreferences("xp_translate_text_configs", MODE_PRIVATE);
                dst = sp.getString("target_lang", "zh-TW");
            }
            if (src == null || src.isEmpty()) {
                SharedPreferences sp = getSharedPreferences("xp_translate_text_configs", MODE_PRIVATE);
                src = sp.getString("source_lang", "auto");
            }

            // 自動語言識別
            if ("auto".equalsIgnoreCase(src)) {
                LanguageIdentifier idClient = LanguageIdentification.getClient(
                        new LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build()
                );
                try {
                    String tag = Tasks.await(idClient.identifyLanguage(text));
                    if (tag == null || "und".equalsIgnoreCase(tag)) {
                        src = "en"; // 無法識別時 fallback
                    } else {
                        src = tag;
                    }
                } catch (Exception e) {
                    src = "en";
                }
            }

            String mlSrc = normalizeToMlkitCode(src);
            String mlDst = normalizeToMlkitCode(dst);
            if (mlSrc == null || mlDst == null) {
                respond(os, 400, json("error", "unsupported language"));
                return;
            }

            try {
                TranslatorOptions options = new TranslatorOptions.Builder()
                        .setSourceLanguage(mlSrc)
                        .setTargetLanguage(mlDst)
                        .build();
                Translator translator = Translation.getClient(options);

                // 下載必要的模型（若未下載）
                DownloadConditions cond = new DownloadConditions.Builder().build();
                Tasks.await(translator.downloadModelIfNeeded(cond));

                // 記錄最近使用時間（以語言代碼為鍵）
                try {
                    ModelInfoUtil.markModelUsed(this, mlSrc);
                    ModelInfoUtil.markModelUsed(this, mlDst);
                } catch (Throwable ignored) { }

                String translated = Tasks.await(translator.translate(text));
                String payload = "{\"code\":0,\"text\":" + jsonString(translated) + "}";
                respond(os, 200, payload);
            } catch (Exception e) {
                respond(os, 500, json("error", e.getMessage() == null ? "translate failed" : e.getMessage()));
            }

        } catch (IOException e) {
            // ignore per-connection errors
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private static String normalizeToMlkitCode(String lang) {
        if (lang == null) return null;
        // 直接以 ML Kit 提供的 fromLanguageTag 取得對應代碼（例如 zh-TW -> zh）
        return TranslateLanguage.fromLanguageTag(lang);
    }

    private static Map<String, String> parseQuery(String pathWithQuery) {
        Map<String, String> map = new HashMap<>();
        int qIdx = pathWithQuery.indexOf('?');
        if (qIdx < 0) return map;
        String qs = pathWithQuery.substring(qIdx + 1);
        for (String pair : qs.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String k = urlDecode(pair.substring(0, eq));
            String v = urlDecode(pair.substring(eq + 1));
            map.put(k, v);
        }
        return map;
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static void respond(OutputStream os, int code, String body) throws IOException {
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
        String status = switch (code) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            default -> "Internal Server Error";
        };
        String headers = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n";
        String content = body == null ? "{}" : body;
        headers += "Content-Length: " + content.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n";
        bw.write(headers);
        bw.write(content);
        bw.flush();
    }

    private static String json(String k, String v) {
        return "{\"" + k + "\":" + jsonString(v) + "}";
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        String esc = s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return "\"" + esc + "\"";
    }
}
