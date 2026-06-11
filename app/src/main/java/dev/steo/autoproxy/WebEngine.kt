package dev.steo.autoproxy

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Singleton che possiede la WebView dove gira il player MSE.
 *
 * Vive a livello di application context: sopravvive ad activity e service,
 * così l'audio continua quando l'app è in background e Android Auto controlla
 * la riproduzione. La MainActivity la "adotta" nella propria gerarchia di view
 * quando è visibile.
 *
 * UP:   bridge.js intercetta navigator.mediaSession -> Bridge.postMessage(json)
 * DOWN: sendAction() -> evaluateJavascript -> window.__aaInvoke(action)
 */
@SuppressLint("SetJavaScriptEnabled")
class WebEngine private constructor(context: Context) {

    interface Listener {
        fun onActions(actions: Set<String>)
        fun onMetadata(title: String?, artist: String?, album: String?)
        fun onArtwork(bytes: ByteArray?)
        fun onPlaying(playing: Boolean)
        fun onPosition(durationMs: Long, positionMs: Long, rate: Float)
    }

    companion object {
        private const val TAG = "WebEngine"

        @Volatile
        private var instance: WebEngine? = null

        fun get(context: Context): WebEngine {
            return instance ?: synchronized(this) {
                instance ?: WebEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    val webView: WebView
    var listener: Listener? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // permette a play() via bridge di partire senza gesto utente
            mediaPlaybackRequiresUserGesture = false
        }

        val bridgeJs = context.assets.open("bridge.js").bufferedReader().use { it.readText() }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, bridgeJs, setOf("*"))
        } else {
            // fallback: iniezione a onPageStarted (meno affidabile ma funziona)
            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    view.evaluateJavascript(bridgeJs, null)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.d(TAG, "[js] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                return true
            }
        }

        webView.addJavascriptInterface(Bridge(), "AndroidAutoBridge")
        webView.loadUrl(BuildConfig.PLAYER_URL)
    }

    /** Invoca un action handler registrato dalla pagina con setActionHandler. */
    fun sendAction(action: String, seekTimeSec: Double? = null) {
        val args = if (seekTimeSec != null) "{seekTime: $seekTimeSec}" else "{}"
        val js = "window.__aaInvoke && window.__aaInvoke('$action', $args);"
        mainHandler.post { webView.evaluateJavascript(js, null) }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(json: String) {
            // i callback JS arrivano su un thread dedicato: rimbalzo sul main
            mainHandler.post { dispatch(json) }
        }
    }

    private fun dispatch(json: String) {
        val l = listener ?: return
        try {
            val msg = JSONObject(json)
            when (msg.optString("type")) {
                "ready" -> {
                    Log.i(TAG, "bridge installato nella pagina")
                }
                "actions" -> {
                    val arr = msg.optJSONArray("actions")
                    val actions = mutableSetOf<String>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            actions.add(arr.getString(i))
                        }
                    }
                    l.onActions(actions)
                }
                "metadata" -> {
                    l.onMetadata(
                        msg.optString("title").ifEmpty { null },
                        msg.optString("artist").ifEmpty { null },
                        msg.optString("album").ifEmpty { null }
                    )
                }
                "artwork" -> {
                    val data = msg.optString("data")
                    val bytes = if (data.isEmpty()) {
                        null
                    } else {
                        try { Base64.decode(data, Base64.DEFAULT) } catch (e: Exception) { null }
                    }
                    l.onArtwork(bytes)
                }
                "playback" -> {
                    l.onPlaying(msg.optString("state") == "playing")
                }
                "position" -> {
                    val durationSec = msg.optDouble("duration", Double.NaN)
                    val positionSec = msg.optDouble("position", 0.0)
                    val rate = msg.optDouble("rate", 1.0)
                    val durationMs = if (durationSec.isNaN()) -1L else (durationSec * 1000).toLong()
                    l.onPosition(durationMs, (positionSec * 1000).toLong(), rate.toFloat())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "messaggio bridge non valido: $json", e)
        }
    }
}
