package dev.steo.autoproxy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Activity-contenitore: mostra la WebView condivisa (di proprietà di WebEngine)
 * quando l'app è in primo piano. Alla chiusura la WebView viene solo staccata
 * dalla gerarchia: continua a girare per il servizio media.
 */
class MainActivity : ComponentActivity() {

    private lateinit var container: FrameLayout

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = FrameLayout(this)
        container.setBackgroundColor(android.graphics.Color.BLACK)
        setContentView(container)

        // con targetSdk 35+ la finestra è edge-to-edge: senza questo padding la
        // WebView finisce sotto status bar e barra gesti; ime() tiene il campo
        // di login sopra la tastiera
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                    or WindowInsetsCompat.Type.displayCutout()
                    or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // crea il WebEngine SUBITO col contesto dell'Activity, PRIMA di startService: l'autofill
        // di Chromium si abilita alla costruzione della WebView in base al contesto, che dev'essere
        // un'Activity e non l'application context con cui il service costruirebbe la WebView
        WebEngine.get(this)

        // il servizio deve esistere perché Android Auto trovi la sessione
        startService(Intent(this, PlaybackService::class.java))

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        val engine = WebEngine.get(this)
        // l'autofill (Bitwarden) si aggancia solo con un contesto di Activity: lo impostiamo
        // PRIMA di attaccare la WebView alla finestra (il bind avviene a onAttachedToWindow)
        engine.setActivityContext(this)
        val webView = engine.webView
        (webView.parent as? ViewGroup)?.removeView(webView)
        container.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )
    }

    override fun onStop() {
        // stacca ma non distrugge: la pagina continua a suonare in background
        val engine = WebEngine.get(this)
        val webView = engine.webView
        if (webView.parent === container) {
            container.removeView(webView)
        }
        // torna all'application context: non trattenere l'Activity distrutta (memory leak)
        engine.resetToApplicationContext()
        super.onStop()
    }
}
