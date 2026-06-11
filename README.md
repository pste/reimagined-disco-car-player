# android-auto-mse-proxy

App Android nativa che fa da ponte tra il player web di **reimagined-disco**
(MSE + `navigator.mediaSession`) e **Android Auto**: play/pause/next/prev,
metadata (titolo/artista/album/durata/posizione) e copertina.

## Architettura

```
Android Auto / notifica media
        │  (comandi e stato via MediaSession)
        ▼
PlaybackService (MediaLibraryService, Media3)
        │
WebPlayer (SimpleBasePlayer)        ← player "specchio", nessun audio proprio
        │  sendAction()  ▲ eventi
        ▼                │
WebEngine (singleton, possiede la WebView)
        │  evaluateJavascript()     ▲ AndroidAutoBridge.postMessage()
        ▼                           │
bridge.js (iniettato a document-start)
        │  __aaInvoke('play'|...)   ▲ intercetta navigator.mediaSession
        ▼                           │
player web (MSE)  ──────────────────┘
```

- **Su (pagina → nativo)**: `bridge.js` avvolge `mediaSession.metadata`,
  `playbackState`, `setPositionState` e `setActionHandler`; ogni cambiamento
  viene serializzato in JSON verso il bridge nativo. La copertina viene
  fetchata in pagina (gestisce anche `blob:`/`data:`) e passata in base64.
- **Giù (nativo → pagina)**: i comandi della MediaSession arrivano a
  `WebPlayer` (Media3 `SimpleBasePlayer`) che chiama `window.__aaInvoke(action)`,
  cioè gli stessi handler che il player ha registrato con `setActionHandler`.
- La WebView appartiene a `WebEngine` (singleton a livello di applicazione):
  sopravvive all'activity, l'audio continua in background. `MainActivity` la
  attacca/stacca dalla propria gerarchia quando è visibile.
- Il player web **non richiede modifiche**: basta che valorizzi
  `navigator.mediaSession` (già fatto in reimagined-disco-ui).

## Configurazione

L'URL del player si imposta in `app/build.gradle.kts`:

```kotlin
buildConfigField("String", "PLAYER_URL", "\"http://192.168.1.100:5173/\"")
```

`usesCleartextTraffic="true"` è attivo nel manifest perché il server gira in
LAN su http. I cookie (sessione di login) sono persistenti di default nella
WebView: il login fatto nella UI sopravvive ai riavvii.

## Build

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Oppure aprire la cartella in Android Studio.

## Test con DHU (Desktop Head Unit)

1. `sdkmanager "extras;google;auto"` (una tantum)
2. Sul telefono: Android Auto → impostazioni sviluppatore → *Avvia modalità head unit server*
3. `adb forward tcp:5277 tcp:5277`
4. `~/Android/Sdk/extras/google/auto/desktop-head-unit`
5. Aprire l'app, far partire un brano dalla UI web, verificare che il media
   center di DHU mostri metadata/cover e che i comandi funzionino.

Per vedere l'app in Auto senza pubblicarla sul Play Store serve abilitare
*Sorgenti sconosciute* nelle impostazioni sviluppatore di Android Auto.

## Limiti noti / TODO

- **Primo gesto utente**: i browser richiedono un gesto per avviare l'audio.
  `mediaPlaybackRequiresUserGesture = false` è impostato, ma se l'AudioContext
  della pagina parte sospeso può comunque servire un primo tap nella UI web.
- **Audio focus**: lo gestisce Chromium (WebView) quando suona; il player
  Media3 non lo richiede a sua volta. Da verificare che non ci siano conflitti
  con altre app media.
- **Background**: la WebView non è pausata, ma Doze/ottimizzazioni batteria
  possono uccidere il processo. Il foreground service media mitiga; in caso,
  esentare l'app dalle ottimizzazioni batteria. Su OnePlus (e in genere sulle
  ROM aggressive col background) se l'audio si interrompe a schermo spento:
  Impostazioni → App → Disco Auto → Batteria → "Non ottimizzare" / attività in
  background senza restrizioni; eventualmente bloccare anche la card dell'app
  nella schermata multitasking (lucchetto).
- **Browsing**: l'albero esposto ad Auto è minimale (root + "In riproduzione").
  Esporre artisti/album dall'API di reimagined-disco è un passo successivo.
- **`seekto`**: inoltrato solo se la pagina registra l'handler; idem
  next/prev (i bottoni in Auto compaiono solo per le azioni registrate).
