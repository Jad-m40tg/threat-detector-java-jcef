/* ===== Desktop shim: presents the small part of window.__TAURI__ the app uses =====
   Inert in a normal browser (the mock backend stays selected). Active when the
   host user agent contains "ThreatDetectorJavaFX" (JavaFX WebView) or
   "ThreatDetectorJCEF" (Chromium/JCEF). It must load before js/core/td-config.js.
   Exposes: __TAURI__.core.invoke(cmd,args) -> Promise
            __TAURI__.event.listen(name,cb) -> Promise<unlisten>
   JavaFX injects window.__tdJavaBridge as a JSObject member; JCEF defines the
   same call/subscribe/sync surface over CefMessageRouter. Both hosts deliver
   replies and events through window.__tdShim. */
(function () {
  "use strict";
  var ua = String((window.navigator && navigator.userAgent) || "");
  var isJavaFx = ua.indexOf("ThreatDetectorJavaFX") >= 0;
  var isJcef = ua.indexOf("ThreatDetectorJCEF") >= 0;
  if (!isJavaFx && !isJcef) {
    return;
  }

  if (isJavaFx) {
    /* [perf] JavaFX-only scoping hook: the java-perf.css layer is scoped under
       this class, so a normal browser never sees it. Chromium does not need
       those WebKit workarounds. */
    try { document.documentElement.classList.add("is-javafx"); } catch (e) { /* keep loading */ }
  }

  /* JCEF has no JSObject member injection. Expose the identical bridge surface
     over CEF's message router; replies still arrive through __tdShim. */
  function jcefPost(message) {
    if (typeof window.cefQuery !== "function") {
      throw new Error("JCEF query bridge is unavailable");
    }
    window.cefQuery({
      request: JSON.stringify(message),
      persistent: false,
      onSuccess: function () {},
      onFailure: function () {}
    });
  }

  if (isJcef && !window.__tdJavaBridge) {
    window.__tdJavaBridge = {
      call: function (callId, command, argsJson) {
        jcefPost({ r: String(callId), c: String(command), a: String(argsJson || "{}") });
      },
      subscribe: function (name, on) {
        jcefPost({ s: String(name), on: !!on });
      },
      sync: function (jsonArray) {
        var names = parseJson(jsonArray);
        if (names && typeof names.length === "number") jcefPost({ subs: names });
      }
    };
  }

  var seq = 0;
  var pending = Object.create(null);
  var listeners = Object.create(null);

  /* ===== event demand =====
     Java emits only events that something on this page listens to. Demand is
     tracked at the TD.events level (that is what pages use) and pushed to Java
     by name as it changes, so unused events such as analysis:item-start are
     never serialized or delivered. The adapter's own listen() calls are not
     demand: they exist for every analysis event on every page. */
  var demandCounts = Object.create(null);
  var directSubs = Object.create(null);
  var subsSent = Object.create(null);
  var ANALYSIS_PREFIX = "analysis:";

  function computeSub(name) {
    if (name.indexOf(ANALYSIS_PREFIX) === 0) return (demandCounts[name] || 0) > 0;
    return (directSubs[name] || 0) > 0;
  }

  function pushSub(name) {
    var bridge = window.__tdJavaBridge;
    if (!bridge || typeof bridge.subscribe !== "function") return; /* __subs at page ready */
    var on = computeSub(name);
    if (subsSent[name] === on) return;
    subsSent[name] = on;
    try { bridge.subscribe(name, on); } catch (e) { /* page is going away */ }
  }

  function demand(name, count) {
    if (!name) return;
    demandCounts[name] = count | 0;
    pushSub(name);
  }

  /* [perf] idle motion governor: pause CSS animations only while the page is
     hidden, the window is minimized, or the window lost focus. Focused, visible
     windows keep running every animation exactly as before. Class lives on
     documentElement so java-perf.css (html.is-javafx.td-motion-paused *) picks it
     up without touching the product stylesheets. */
  function syncIdleMotion() {
    var root = document.documentElement;
    var hidden = document.hidden === true || document.visibilityState === "hidden";
    var focused = typeof document.hasFocus === "function" ? document.hasFocus() !== false : true;
    var minimized = typeof window !== "undefined" && window.isMinimized === true;
    var paused = !!(hidden || minimized || !focused);
    try {
      root.classList.toggle("td-motion-paused", paused);
    } catch (e) { /* classList unavailable: keep the last known state */ }
  }
  try {
    document.addEventListener("visibilitychange", syncIdleMotion, false);
    window.addEventListener("focus", syncIdleMotion, false);
    window.addEventListener("blur", syncIdleMotion, false);
    window.addEventListener("pageshow", syncIdleMotion, false);
    syncIdleMotion();
  } catch (e) { /* keep loading without the pause hook */ }

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
    /* Java delivers one JSON array of pre-serialized messages per JSObject.call.
       It lands here and is dispatched inside requestAnimationFrame with a small
       per-frame budget, so a burst of 500 results cannot block one frame. */
    __deliver: function (jsonText) {
      var messages = parseJson(jsonText);
      if (!messages || !messages.length) return;
      for (var i = 0; i < messages.length; i++) deliveryQueue.push(messages[i]);
      scheduleDrain();
    },
    /* Full subscription set, read by Java right after the page loads. */
    __subs: function () {
      var names = Object.create(null), out = [];
      var key;
      for (key in demandCounts) names[key] = true;
      for (key in directSubs) names[key] = true;
      for (key in listeners) names[key] = true;
      for (key in names) {
        if (computeSub(key)) out.push(key);
      }
      return JSON.stringify(out);
    },
    demand: demand,
    resolve: function (callId, payloadJson) {
      resolveCall(callId, parseJson(payloadJson));
    },
    reject: function (callId, message) {
      rejectCall(callId, message);
    },
    emit: function (name, payloadJson) {
      deliverEvent(name, parseJson(payloadJson));
    }
  };

  /* ===== ordered, time-sliced delivery =====
     Order is preserved exactly as Java emitted it. Events may not be dropped or
     reordered; only the Java-side progress coalescing reduces the stream.
     When batch-done arrives the rest of the queue is drained without a budget
     so the final table can never be left half-applied. */
  var deliveryQueue = [];
  var deliveryHead = 0;
  var drainScheduled = false;
  var DRAIN_BUDGET_MS = 6;

  function nowMs() {
    return (window.performance && performance.now) ? performance.now() : Date.now();
  }

  function resolveCall(callId, payload) {
    var p = pending[callId];
    if (!p) return;
    delete pending[callId];
    p.resolve(payload);
  }

  function rejectCall(callId, message) {
    var p = pending[callId];
    if (!p) return;
    delete pending[callId];
    p.reject(new Error(String(message === null || message === undefined ? "Java bridge error" : message)));
  }

  function deliverEvent(name, payload) {
    var arr = listeners[name];
    if (!arr || !arr.length) return;
    var copy = arr.slice();
    for (var i = 0; i < copy.length; i++) {
      try { copy[i]({ event: name, payload: payload }); } catch (e) { /* isolate listener */ }
    }
  }

  function dispatchMessage(message) {
    if (!message) return;
    if (message.r !== undefined) {
      if (message.e !== undefined) rejectCall(message.r, message.e);
      else resolveCall(message.r, message.v);
      return;
    }
    if (message.n) deliverEvent(message.n, message.p);
  }

  function scheduleDrain() {
    if (drainScheduled) return;
    drainScheduled = true;
    requestAnimationFrame(drain);
  }

  function drain() {
    drainScheduled = false;
    var deadline = nowMs() + DRAIN_BUDGET_MS;
    var urgent = false;
    var processed = 0;
    /* Soft budget: one expensive listener must not stretch a 500-item batch to
       500 frames, so at least two messages are handled per frame. */
    while (deliveryHead < deliveryQueue.length) {
      var message = deliveryQueue[deliveryHead++];
      dispatchMessage(message);
      processed++;
      if (message && message.n === "analysis:batch-done") urgent = true;
      if (!urgent && processed >= 2 && nowMs() >= deadline) break;
    }
    if (deliveryHead >= deliveryQueue.length) {
      deliveryQueue.length = 0;
      deliveryHead = 0;
    } else if (deliveryHead > 256) {
      deliveryQueue.splice(0, deliveryHead);
      deliveryHead = 0;
    }
    if (deliveryHead < deliveryQueue.length) scheduleDrain();
  }

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
    directSubs[name] = (directSubs[name] || 0) + 1;
    pushSub(name);
    return Promise.resolve(function () {
      var arr = listeners[name];
      if (!arr) return;
      var i = arr.indexOf(cb);
      if (i >= 0) {
        arr.splice(i, 1);
        directSubs[name] = Math.max(0, (directSubs[name] || 0) - 1);
        pushSub(name);
      }
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

  /* JCEF: seed Java's subscription set once the page's listeners are wired.
     Later demand changes are pushed by subscribe() above. */
  if (isJcef) {
    function syncSubscriptions() {
      try {
        if (window.__tdShim && window.__tdJavaBridge && window.__tdJavaBridge.sync) {
          window.__tdJavaBridge.sync(window.__tdShim.__subs());
        }
      } catch (e) { /* subscribe() calls keep the set current */ }
    }
    syncSubscriptions();
    window.addEventListener("load", syncSubscriptions);
  }

  window.__TD_JAVA__ = true;
})();
