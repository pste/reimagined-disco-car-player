/*
 * Bridge iniettato a document-start in ogni pagina caricata nella WebView.
 *
 * Direzione UP (pagina -> nativo): intercetta navigator.mediaSession
 * (metadata, playbackState, setPositionState, setActionHandler) e inoltra
 * tutto a AndroidAutoBridge.postMessage(json).
 *
 * Direzione DOWN (nativo -> pagina): window.__aaInvoke(action, args) richiama
 * gli action handler che la pagina ha registrato con setActionHandler.
 *
 * Nessuna modifica richiesta al player web: basta che usi mediaSession.
 */
(function () {
  if (window.__aaBridgeInstalled) { return; }
  window.__aaBridgeInstalled = true;

  var ms = navigator.mediaSession;
  if (!ms || typeof AndroidAutoBridge === 'undefined') { return; }

  var handlers = {};

  function post(type, payload) {
    var msg = payload || {};
    msg.type = type;
    try { AndroidAutoBridge.postMessage(JSON.stringify(msg)); } catch (e) {}
  }

  /* ---- azioni registrate dalla pagina ---- */

  var origSetActionHandler = ms.setActionHandler.bind(ms);
  ms.setActionHandler = function (action, handler) {
    if (handler) {
      handlers[action] = handler;
    } else {
      delete handlers[action];
    }
    post('actions', { actions: Object.keys(handlers) });
    try { origSetActionHandler(action, handler); } catch (e) {}
  };

  /* invocazione dal nativo */
  window.__aaInvoke = function (action, args) {
    var h = handlers[action];
    if (!h) { return false; }
    var details = args || {};
    details.action = action;
    try { h(details); } catch (e) {}
    return true;
  };

  /* ---- intercettazione proprietà (metadata, playbackState) ---- */

  function wrapAccessor(name, onSet) {
    var proto = Object.getPrototypeOf(ms);
    var d = Object.getOwnPropertyDescriptor(proto, name);
    if (!d || !d.set) { return; }
    Object.defineProperty(ms, name, {
      configurable: true,
      get: function () { return d.get.call(ms); },
      set: function (v) {
        d.set.call(ms, v);
        try { onSet(v); } catch (e) {}
      }
    });
  }

  wrapAccessor('metadata', function (m) {
    if (!m) {
      post('metadata', { title: null, artist: null, album: null });
      return;
    }
    post('metadata', { title: m.title, artist: m.artist, album: m.album });
    sendArtwork(m.artwork);
  });

  wrapAccessor('playbackState', function (v) {
    post('playback', { state: v });
  });

  /* ---- posizione/durata ---- */

  var origSetPositionState = ms.setPositionState.bind(ms);
  ms.setPositionState = function (s) {
    try { origSetPositionState(s || {}); } catch (e) {}
    if (s) {
      post('position', {
        duration: s.duration,
        position: s.position,
        rate: (s.playbackRate === undefined) ? 1 : s.playbackRate
      });
    }
  };

  /* ---- copertina: fetch (gestisce anche blob:/data:/relative) -> base64 ---- */

  var lastArtworkSrc = null;

  function sendArtwork(artwork) {
    var list = artwork || [];
    if (list.length === 0) {
      lastArtworkSrc = null;
      post('artwork', { data: null });
      return;
    }
    /* sceglie l'immagine più grande dichiarata in sizes */
    var best = list[0];
    var bestW = -1;
    for (var i = 0; i < list.length; i++) {
      var w = parseInt(String(list[i].sizes || '0x0').split('x')[0], 10) || 0;
      if (w >= bestW) { bestW = w; best = list[i]; }
    }
    if (best.src === lastArtworkSrc) { return; }
    lastArtworkSrc = best.src;

    fetch(best.src)
      .then(function (r) { return r.blob(); })
      .then(function (blob) {
        return new Promise(function (resolve, reject) {
          var fr = new FileReader();
          fr.onload = function () { resolve(fr.result); };
          fr.onerror = reject;
          fr.readAsDataURL(blob);
        });
      })
      .then(function (dataUrl) {
        var s = String(dataUrl);
        var comma = s.indexOf(',');
        post('artwork', { data: (comma >= 0) ? s.substring(comma + 1) : null });
      })
      .catch(function () {
        post('artwork', { data: null });
      });
  }

  post('ready', {});
})();
