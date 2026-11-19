/* ===== TD.events — pub/sub event channel (Backend agent, Wave 2) ===== */
/* Events used: analysis:item-start, analysis:item-done, analysis:batch-start,
   analysis:batch-done, analysis:result, monitor:snapshot. */
(function () {
  "use strict";
  window.TD = window.TD || {};
  var listeners = Object.create(null);
  /* JavaFX shim: tell Java how many page listeners an event has, so unused
     events (analysis:item-start) are never serialized or delivered. Inert in a
     normal browser: the shim is absent and this is a no-op. */
  function reportDemand(name) {
    var shim = window.__tdShim;
    if (shim && typeof shim.demand === "function") {
      try { shim.demand(name, (listeners[name] || []).length); } catch (e) { /* never throw */ }
    }
  }
  function on(name, cb) {
    if (typeof cb !== "function") return function () {};
    (listeners[name] = listeners[name] || []).push(cb);
    reportDemand(name);
    return function off() {
      var arr = listeners[name];
      if (!arr) return;
      var i = arr.indexOf(cb);
      if (i >= 0) {
        arr.splice(i, 1);
        reportDemand(name);
      }
    };
  }
  function emit(name, payload) {
    var arr = listeners[name];
    if (!arr || !arr.length) return;
    var copy = arr.slice();
    for (var i = 0; i < copy.length; i++) {
      try { copy[i](payload); } catch (e) { /* isolate listener errors */ }
    }
  }
  function off(name, cb) {
    if (!name) {
      var names = [];
      for (var key in listeners) names.push(key);
      listeners = Object.create(null);
      for (var i = 0; i < names.length; i++) reportDemand(names[i]);
      return;
    }
    if (cb) {
      var arr = listeners[name];
      if (arr) {
        var j = arr.indexOf(cb);
        if (j >= 0) arr.splice(j, 1);
      }
    } else {
      delete listeners[name];
    }
    reportDemand(name);
  }
  TD.events = { on: on, emit: emit, off: off };
})();
