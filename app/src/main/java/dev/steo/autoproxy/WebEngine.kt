package dev.steo.autoproxy

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
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

        // NB: passa il context RAW (non applicationContext). Se il primo a chiamare è la
        // MainActivity, la WebView nasce con un contesto di Activity e l'AutofillProvider di
        // Chromium si abilita (il check avviene alla costruzione della WebView). Se invece il
        // primo è il service (avvio headless da Android Auto) nasce con app context: niente
        // autofill in quella sessione, ma la riproduzione funziona.
        fun get(context: Context): WebEngine {
            return instance ?: synchronized(this) {
                instance ?: WebEngine(context).also { instance = it }
            }
        }
    }

    val webView: WebView

    // eventi del bridge possono arrivare prima che il service agganci il
    // listener (es. 'actions' viene mandato una sola volta all'avvio pagina):
    // li cachiamo per tipo e li rigiochiamo all'aggancio
    private val lastEventByType = LinkedHashMap<String, String>()

    var listener: Listener? = null
        set(value) {
            field = value
            if (value != null) {
                mainHandler.post {
                    lastEventByType.values.toList().forEach { dispatch(it, replay = true) }
                }
            }
        }

    private val mainHandler = Handler(Looper.getMainLooper())

    // La WebView è di application-scope (l'audio sopravvive all'Activity), ma l'Autofill
    // Framework (Bitwarden & co.) si aggancia SOLO se la WebView è COSTRUITA con un contesto di
    // Activity: il check di supporto autofill di Chromium avviene alla costruzione, non basta lo
    // swap successivo. Quindi la base del wrapper parte dal context che costruisce la WebView
    // (Activity all'avvio normale, vedi WebEngine.get + MainActivity.onCreate). Mentre la
    // MainActivity la mostra reimpostiamo comunque la base sull'Activity (setActivityContext); in
    // background torniamo all'app context (resetToApplicationContext) per non leakare l'Activity.
    private val appContext = context.applicationContext
    private val contextWrapper = MutableContextWrapper(context)

    init {
        webView = WebView(contextWrapper)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // permette a play() via bridge di partire senza gesto utente
            mediaPlaybackRequiresUserGesture = false
        }

        val bridgeJs = context.assets.open("bridge.js").bufferedReader().use { it.readText() }

        val docStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        Log.i(TAG, "iniezione bridge: ${if (docStartSupported) "document-start" else "fallback onPageStarted"}")
        if (docStartSupported) {
            WebViewCompat.addDocumentStartJavaScript(webView, bridgeJs, setOf("*"))
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (!docStartSupported) {
                    // fallback: iniezione a onPageStarted (meno affidabile)
                    view.evaluateJavascript(bridgeJs, null)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                // probe diagnostico: verifica i prerequisiti del bridge
                view.evaluateJavascript(
                    "JSON.stringify({installed: !!window.__aaBridgeInstalled," +
                        " bridge: typeof AndroidAutoBridge," +
                        " mediaSession: !!navigator.mediaSession})"
                ) { result ->
                    Log.i(TAG, "probe pagina $url -> $result")
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

    /**
     * La MainActivity lo chiama quando mostra la WebView. L'Autofill Framework richiede un
     * contesto di Activity: va impostato PRIMA di attaccare la WebView alla finestra, così al
     * bind dell'autofill (onAttachedToWindow) il contesto risolve all'Activity e Bitwarden &co.
     * possono proporre le credenziali.
     */
    fun setActivityContext(activity: Context) {
        contextWrapper.baseContext = activity
    }

    /** Ritorno all'application context quando l'Activity non è più visibile. */
    fun resetToApplicationContext() {
        contextWrapper.baseContext = appContext
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

    private fun dispatch(json: String, replay: Boolean = false) {
        try {
            val msg = JSONObject(json)
            val type = msg.optString("type")
            if (!replay) {
                lastEventByType[type] = json
                Log.i(TAG, "evento '$type' (listener=${listener != null}): ${json.take(120)}")
            }
            val l = listener ?: return
            when (type) {
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
