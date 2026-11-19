/* ===== JavaFX shim: presents the small part of window.__TAURI__ the app uses =====
   This file is inert in a normal browser (the mock backend stays selected).
   It is active only when App.java sets a user agent containing the marker
   "ThreatDetectorJavaFX". It must load before js/core/td-config.js.
   Exposes: __TAURI__.core.invoke(cmd,args) -> Promise
            __TAURI__.event.listen(name,cb) -> Promise<unlisten>
   Java calls window.__tdShim.resolve / .reject / .emit on the FX thread. */
(function () {
  "use strict";
  if (!window.navigator || String(navigator.userAgent || "").indexOf("ThreatDetectorJavaFX") < 0) {
    return;
  }

  var seq = 0;
  var pending = Object.create(null);
  var listeners = Object.create(null);

  /* ===== console/error diagnostics for the shell's --theme-debug dump ===== */
  window.__tdDiag = {
    errors: [],
    capture: function (kind, text) {
      try {
        this.errors.push(kind + ": " + String(text));
        if (this.errors.length > 30) this.errors.splice(0, this.errors.length - 30);
      } catch (e) { /* diagnostics must never throw */ }
    }
  };
  try {
    var nativeConsoleError = console.error ? console.error.bind(console) : null;
    console.error = function () {
      var parts = [];
      for (var i = 0; i < arguments.length; i++) parts.push(String(arguments[i]));
      window.__tdDiag.capture("console.error", parts.join(" "));
      if (nativeConsoleError) nativeConsoleError.apply(null, arguments);
    };
    window.addEventListener("error", function (event) {
      window.__tdDiag.capture("error", (event && event.message) + " @" +
        (event && event.filename) + ":" + (event && event.lineno));
    });
    if (typeof window.addEventListener === "function") {
      window.addEventListener("unhandledrejection", function (event) {
        window.__tdDiag.capture("unhandledrejection", String(event && event.reason || event));
      });
    }
  } catch (e) { /* keep loading without diagnostics */ }

  /* ===== matchMedia('(prefers-color-scheme: dark)') backed by Windows =====
     JavaFX WebKit always reports light here, so "system" in the front-end
     would be stuck on light. Java reads the registry and exposes the initial
     value through the user agent marker (theme-init.js resolves BEFORE any
     bridge call exists), then this patch keeps it live via get_system_theme.
     Every other media query is passed through untouched. */
  var originalMatchMedia = window.matchMedia ? window.matchMedia.bind(window) : null;
  var darkMedia = "(prefers-color-scheme: dark)";
  var ua = String(navigator.userAgent || "");
  var initialDark = ua.indexOf("TDSystemDark") >= 0 ? true
    : ua.indexOf("TDSystemLight") >= 0 ? false : null;
  var systemDark = initialDark;

  function DarkMedia() {
    this.matches = systemDark === true;
    this.media = darkMedia;
    this.onchange = null;
    this._listeners = [];
  }
  DarkMedia.prototype.addEventListener = function (type, fn) {
    if (type === "change" && typeof fn === "function") this._listeners.push(fn);
  };
  DarkMedia.prototype.removeEventListener = function (type, fn) {
    var i = this._listeners.indexOf(fn);
    if (i >= 0) this._listeners.splice(i, 1);
  };
  DarkMedia.prototype.addListener = DarkMedia.prototype.addEventListener;
  DarkMedia.prototype.removeListener = DarkMedia.prototype.removeEventListener;
  DarkMedia.prototype.dispatchEvent = function (event) {
    var copy = this._listeners.slice();
    for (var i = 0; i < copy.length; i++) {
      try { copy[i](event); } catch (e) { /* isolate listener */ }
    }
    if (typeof this.onchange === "function") {
      try { this.onchange(event); } catch (e2) { /* isolate */ }
    }
    return true;
  };
  var darkMql = new DarkMedia();

  function setSystemDark(value) {
    if (typeof value !== "boolean" || value === systemDark) return;
    systemDark = value;
    darkMql.matches = value;
    darkMql.dispatchEvent({ matches: value, media: darkMedia, type: "change" });
  }

  window.matchMedia = function (query) {
    var q = String(query == null ? "" : query).replace(/\s+/g, "").toLowerCase();
    if (q === "(prefers-color-scheme:dark)") return darkMql;
    if (originalMatchMedia) return originalMatchMedia(query);
    return { matches: false, media: String(query), onchange: null,
      addEventListener: function () {}, removeEventListener: function () {},
      addListener: function () {}, removeListener: function () {},
      dispatchEvent: function () { return false; } };
  };

  function parseJson(text) {
    if (text === null || text === undefined || text === "") return null;
    try { return JSON.parse(text); } catch (e) { return null; }
  }

  window.__tdShim = {
    resolve: function (callId, payloadJson) {
      var p = pending[callId];
      if (!p) return;
      delete pending[callId];
      p.resolve(parseJson(payloadJson));
    },
    reject: function (callId, message) {
      var p = pending[callId];
      if (!p) return;
      delete pending[callId];
      p.reject(new Error(String(message === null || message === undefined ? "Java bridge error" : message)));
    },
    emit: function (name, payloadJson) {
      var arr = listeners[name];
      if (!arr || !arr.length) return;
      var payload = parseJson(payloadJson);
      var copy = arr.slice();
      for (var i = 0; i < copy.length; i++) {
        try { copy[i]({ event: name, payload: payload }); } catch (e) { /* isolate listener */ }
      }
    }
  };

  var tauri = window.__TAURI__ = window.__TAURI__ || {};
  tauri.core = tauri.core || {};
  tauri.core.invoke = function (command, args) {
    return new Promise(function (resolve, reject) {
      var callId = "c" + (++seq);
      pending[callId] = { resolve: resolve, reject: reject };
      try {
        window.__tdJavaBridge.call(callId, String(command), JSON.stringify(args || {}));
      } catch (e) {
        delete pending[callId];
        reject(e);
      }
    });
  };

  tauri.event = tauri.event || {};
  tauri.event.listen = function (name, cb) {
    if (typeof cb !== "function") return Promise.resolve(function () {});
    (listeners[name] = listeners[name] || []).push(cb);
    return Promise.resolve(function () {
      var arr = listeners[name];
      if (!arr) return;
      var i = arr.indexOf(cb);
      if (i >= 0) arr.splice(i, 1);
    });
  };

  /* WebKit 617.1 (JavaFX 22) has no aspect-ratio support. The shade picker
     square is the only element that depends on it, so size it explicitly.
     Everything stays inside this shim; the copied front-end CSS is untouched. */
  function fixAspectRatio() {
    if (window.CSS && CSS.supports && CSS.supports("aspect-ratio", "1 / 1")) return;
    var nodes = document.querySelectorAll(".shade-picker__square");
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.__tdSquare) continue;
      el.__tdSquare = true;
      (function (node) {
        function resize() {
          var size = node.offsetWidth;
          if (size > 0) node.style.height = size + "px";
        }
        resize();
        if (window.ResizeObserver) new ResizeObserver(resize).observe(node);
      })(el);
    }
  }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", fixAspectRatio);
  } else {
    fixAspectRatio();
  }
  window.addEventListener("load", fixAspectRatio);
  window.addEventListener("resize", fixAspectRatio);
  if (window.MutationObserver) {
    var aspectScheduled = false;
    new MutationObserver(function () {
      if (aspectScheduled) return;
      aspectScheduled = true;
      requestAnimationFrame(function () {
        aspectScheduled = false;
        fixAspectRatio();
      });
    }).observe(document.documentElement, { childList: true, subtree: true });
  }

  /* Live system-theme follow-up: the initial value came from the UA marker,
     this keeps matchMedia change events firing when Windows switches themes.
     Before the bridge attaches the call rejects and is simply retried. */
  function pollSystemTheme() {
    try {
      tauri.core.invoke("get_system_theme").then(function (reply) {
        if (reply && typeof reply.dark === "boolean") setSystemDark(reply.dark);
      }).catch(function () { /* retried on the next tick */ });
    } catch (e) { /* retried on the next tick */ }
  }
  pollSystemTheme();
  setInterval(pollSystemTheme, 3000);

  window.__TD_JAVA__ = true;
})();
