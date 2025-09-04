package tianci.dev.xptranslatetext.translate;

import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

/**
 * JavaScript bridge injected into WebView to request translations
 * and post results back to the page.
 */
public class WebViewTranslationBridge {
    private final WebView webView;

    public WebViewTranslationBridge(WebView webView) {
        this.webView = webView;
    }

    @JavascriptInterface
    public void translateFromJs(String requestId, String text, String srcLang, String tgtLang) {
        // Delegate to the shared translation task; result is posted via evaluateJavascript.
        Log.i("LSPosed-Bridge", String.format("[ translate ] WebViewTranslationBridge string => %s", text));

        MultiSegmentTranslateTask.translateFromJs(webView, requestId, text, srcLang, tgtLang);
    }
}
