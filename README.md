# reimagined-disco-car-player

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
        │  __aaInvoke('play'|...)   ▲ polyfilla navigator.mediaSession
        ▼                           │
player web (MSE)  ──────────────────┘
```

- **Su (pagina → nativo)**: la WebView di Android **non implementa** la Media
  Session Web API, quindi `bridge.js` la **polyfilla**: installa un
  `navigator.mediaSession` finto (più il costruttore `MediaMetadata`) la cui
  implementazione inoltra `metadata`, `playbackState`, `setPositionState` e
  `setActionHandler` in JSON al bridge nativo. Se una futura WebView esponesse
  l'API vera, lo script ripiega sul wrapping di quella. La copertina viene
  fetchata in pagina (gestisce anche `blob:`/`data:`) e passata in base64.
- **Giù (nativo → pagina)**: i comandi della MediaSession arrivano a
  `WebPlayer` (Media3 `SimpleBasePlayer`) che chiama `window.__aaInvoke(action)`,
  cioè gli stessi handler che il player ha registrato con `setActionHandler`.
- La WebView appartiene a `WebEngine` (singleton a livello di applicazione):
  sopravvive all'activity, l'audio continua in background. `MainActivity` la
  attacca/stacca dalla propria gerarchia quando è visibile.
- Il player web **non richiede modifiche**: basta che usi l'API
  `mediaSession` dietro il feature-check `'mediaSession' in navigator`
  (già fatto in reimagined-disco-ui) — il polyfill la fa trovare presente.

## Configurazione

L'URL del player si imposta in `app/build.gradle.kts`:

```kotlin
buildConfigField("String", "PLAYER_URL", "\"https://music.nestix.dev/\"")
```

`usesCleartextTraffic="true"` è attivo nel manifest per permettere anche URL
http in LAN (es. dev server Vite); con l'URL https di produzione non serve.
I cookie (sessione di login) sono persistenti di default nella WebView: il
login fatto nella UI sopravvive ai riavvii.

## Build

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Oppure aprire la cartella in Android Studio. Per il debug del bridge:

```bash
adb logcat -s WebEngine -d
```

(mostra iniezione dello script, probe a fine caricamento pagina, ogni evento
ricevuto dalla pagina e i console.log della pagina stessa).

## Test con DHU (Desktop Head Unit)

1. Installare il DHU una tantum: da Android Studio → Settings → Android SDK →
   SDK Tools → "Android Auto Desktop Head Unit" (oppure
   `sdkmanager "extras;google;auto"` se i cmdline-tools sono installati)
2. Sul telefono: Android Auto → impostazioni sviluppatore → *Avvia modalità head unit server*
3. `adb forward tcp:5277 tcp:5277`
4. `~/Android/Sdk/extras/google/auto/desktop-head-unit`
5. Aprire l'app, far partire un brano dalla UI web, verificare che il media
   center di DHU mostri metadata/cover e che i comandi funzionino.

Per vedere l'app in Auto senza pubblicarla sul Play Store serve abilitare
*Sorgenti sconosciute* nelle impostazioni sviluppatore di Android Auto.

## Limiti noti / TODO

- **Autofill password manager**: risolto (verificato su device). La WebView era
  creata con l'application context (`WebEngine` è app-scoped per tenere l'audio
  in background), ma l'AutofillProvider di Chromium si abilita **alla costruzione**
  della WebView in base al contesto, che dev'essere un'Activity. Fix: la WebView
  usa un `MutableContextWrapper` e la sua base è il context che la costruisce —
  perché sia un'Activity, `WebEngine.get` riceve il context RAW e `MainActivity.onCreate`
  chiama `WebEngine.get(this)` **prima** di `startService`, così è l'Activity (non il
  service) a costruire la WebView. La base torna all'app context in `onStop` per non
  leakare l'Activity. Caso limite residuo: avvio "headless" da Android Auto senza mai
  aprire il telefono → la WebView nasce dal service con app context, niente autofill in
  quella sessione (la riproduzione funziona); resta il workaround accessibilità/incolla,
  col cookie di sessione persistente.
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
  Oggi la UI registra play/pause/nexttrack/previoustrack → niente seek
  dalla schermata Auto finché la UI non registra `seekto`.
- **Throttling posizione**: la pagina manda `position` ~4 volte/secondo e ogni
  evento invalida lo stato del player nativo. Media3 regge, ma per risparmiare
  batteria si può throttlare in `bridge.js` (es. 1 evento/secondo, la
  posizione intermedia è comunque estrapolata da `WebPlayer`).
- **applicationId**: resta `dev.steo.autoproxy` anche se il progetto si chiama
  reimagined-disco-car-player — cambiarlo = app nuova per Android, si perdono
  login e dati dell'app installata.
