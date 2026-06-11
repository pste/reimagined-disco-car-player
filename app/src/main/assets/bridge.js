/*
 * Bridge iniettato a document-start in ogni pagina caricata nella WebView.
 *
 * La WebView di Android NON implementa la Media Session Web API
 * (navigator.mediaSession non esiste): questo script la POLYFILLA, così la
 * pagina — che usa l'API dietro il guard 'mediaSession' in navigator — la
 * trova e la usa normalmente. Se un giorno la WebView dovesse esporla
 * davvero, lo script ripiega sul wrapping di quella nativa.
 *
 * Direzione UP (pagina -> nativo): metadata, playbackState, setPositionState
 * e setActionHandler vengono inoltrati a AndroidAutoBridge.postMessage(json).
 *
 * Direzione DOWN (nativo -> pagina): window.__aaInvoke(action, args) richiama
 * gli action handler che la pagina ha registrato con setActionHandler.
 *
 * Nessuna modifica richiesta al player web.
 */
(function () {
  console.log('[aa-bridge] script in esecuzione');
  if (window.__aaBridgeInstalled) { console.log('[aa-bridge] già installato'); return; }
  if (typeof AndroidAutoBridge === 'undefined') {
    console.log('[aa-bridge] ABORT: interfaccia AndroidAutoBridge assente');
    return;
  }
  window.__aaBridgeInstalled = true;

  var handlers = {};

  function post(type, payload) {
    var msg = payload || {};
    msg.type = type;
    try { AndroidAutoBridge.postMessage(JSON.stringify(msg)); } catch (e) {}
  }

  /* ---- azioni registrate dalla pagina ---- */

  function onSetActionHandler(action, handler) {
    if (handler) {
      handlers[action] = handler;
    } else {
      delete handlers[action];
    }
    post('actions', { actions: Object.keys(handlers) });
  }

  /* invocazione dal nativo */
  window.__aaInvoke = function (action, args) {
    var h = handlers[action];
    if (!h) { return false; }
    var details = args || {};
    details.action = action;
    try { h(details); } catch (e) {}
    return true;
  };

  /* ---- inoltro eventi al nativo ---- */

  function sendMetadata(m) {
    if (!m) {
      post('metadata', { title: null, artist: null, album: null });
      sendArtwork(null);
      return;
    }
    post('metadata', { title: m.title, artist: m.artist, album: m.album });
    sendArtwork(m.artwork);
  }

  function sendPlayback(v) {
    post('playback', { state: v });
  }

  function sendPosition(s) {
    if (!s) { return; }
    post('position', {
      duration: s.duration,
      position: s.position,
      rate: (s.playbackRate === undefined) ? 1 : s.playbackRate
    });
  }

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

  /* ---- polyfill: mediaSession finta, implementata dal bridge ---- */

  function installPolyfill() {
    var meta = null;
    var pbState = 'none';

    var fake = {
      setActionHandler: function (action, handler) { onSetActionHandler(action, handler); },
      setPositionState: function (s) { sendPosition(s); }
    };
    Object.defineProperty(fake, 'metadata', {
      get: function () { return meta; },
      set: function (v) { meta = v; sendMetadata(v); }
    });
    Object.defineProperty(fake, 'playbackState', {
      get: function () { return pbState; },
      set: function (v) { pbState = v; sendPlayback(v); }
    });

    Object.defineProperty(navigator, 'mediaSession', {
      value: fake,
      configurable: true
    });

    if (typeof window.MediaMetadata === 'undefined') {
      window.MediaMetadata = function (init) {
        var o = init || {};
        this.title = o.title || '';
        this.artist = o.artist || '';
        this.album = o.album || '';
        this.artwork = o.artwork || [];
      };
    }
  }

  /* ---- wrapping: mediaSession nativa presente, la intercettiamo ---- */

  function wrapNative(ms) {
    var origSetActionHandler = ms.setActionHandler.bind(ms);
    ms.setActionHandler = function (action, handler) {
      onSetActionHandler(action, handler);
      try { origSetActionHandler(action, handler); } catch (e) {}
    };

    var origSetPositionState = ms.setPositionState.bind(ms);
    ms.setPositionState = function (s) {
      try { origSetPositionState(s || {}); } catch (e) {}
      sendPosition(s);
    };

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

    wrapAccessor('metadata', sendMetadata);
    wrapAccessor('playbackState', sendPlayback);
  }

  if (navigator.mediaSession) {
    console.log('[aa-bridge] mediaSession nativa presente: wrapping');
    wrapNative(navigator.mediaSession);
  } else {
    console.log('[aa-bridge] mediaSession assente: installo il polyfill');
    installPolyfill();
  }

  post('ready', {});
})();
