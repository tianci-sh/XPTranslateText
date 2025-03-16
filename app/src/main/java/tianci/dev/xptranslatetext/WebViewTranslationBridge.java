package tianci.dev.xptranslatetext;

import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

public class WebViewTranslationBridge {
    private final WebView webView;

    public WebViewTranslationBridge(WebView webView) {
        this.webView = webView;
    }

    @JavascriptInterface
    public void translateFromJs(String requestId, String text, String srcLang, String tgtLang) {
        Log.i("LSPosed-Bridge", String.format("[ translate ] WebViewTranslationBridge string => %s", text));

        MultiSegmentTranslateTask.translateFromJs(webView, requestId, text, srcLang, tgtLang);
    }
}
