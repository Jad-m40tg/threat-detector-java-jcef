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

  window.__TD_JAVA__ = true;
})();
