/* ===== TD.components.virtualList - windowed rendering for long lists =====
   Renders only the rows around the viewport plus a few of overscan. The caller
   supplies the row element and keeps the markup unchanged, so all existing CSS
   applies exactly as before. Two things keep the scroll geometry identical to
   the full list:

     - padding-top on the scroll container stands in for the rows above the
       window;
     - a single filler <li> stands in for the rows below it.

   Both are derived from the measured row stride (height + grid gap), so the
   scrollbar range, the visible scroll position and row order are unchanged.
   Rows entering the DOM after the first render are marked to skip the staggered
   entrance animation (the caller decides that via the `animate` argument). */
(function () {
  "use strict";
  window.TD = window.TD || {};
  TD.components = TD.components || {};

  function create(options) {
    var root = options.root;
    var renderRow = options.renderRow;      /* (index, animate) -> element */
    var threshold = options.threshold || 60;
    var overscan = options.overscan || 4;
    var items = [];
    var rowHeight = options.rowHeight || 56;
    var gap = 8;
    var winStart = -1;
    var winEnd = -1;
    var enabled = false;
    var measured = false;
    var scrollQueued = false;

    function readGap() {
      try {
        var cs = window.getComputedStyle(root);
        var value = parseFloat(cs.rowGap || cs.gap || "0");
        gap = isFinite(value) ? value : 0;
      } catch (e) {
        gap = 0;
      }
    }

    function stride() {
      return rowHeight + gap;
    }

    function measureRow() {
      var first = root.querySelector(".batch-row");
      if (!first) return false;
      var height = first.getBoundingClientRect().height;
      if (height > 0 && Math.abs(height - rowHeight) > 0.5) {
        rowHeight = height;
        measured = true;
        return true;
      }
      return false;
    }

    function schedule(fn) {
      window.requestAnimationFrame(fn);
    }

    function render(force, animate) {
      if (!root) return;
      var total = items.length;
      if (!enabled) return;
      if (!measured) {
        readGap();
      }

      var keep = root.scrollTop;
      var view = root.clientHeight || 0;
      var h = stride();
      var firstVisible = Math.floor(keep / h);
      var visibleCount = Math.max(1, Math.ceil(view / h) + 1);
      var vStart = Math.max(0, Math.min(Math.max(0, total - 1), firstVisible));
      var vEnd = Math.min(total, vStart + visibleCount);
      var start = Math.max(0, vStart - overscan);
      var end = Math.min(total, vEnd + overscan);

      if (!force && start === winStart && end === winEnd) return;

      /* Keep keyboard focus on the same row across the re-render. */
      var focusRow = -1;
      var active = document.activeElement;
      if (active && root.contains(active)) {
        var holder = active.closest ? active.closest(".batch-row") : null;
        if (holder) {
          var parsed = parseInt(holder.getAttribute("data-index"), 10);
          if (isFinite(parsed)) focusRow = parsed;
        }
      }

      var frag = document.createDocumentFragment();
      for (var i = start; i < end; i++) {
        frag.appendChild(renderRow(i, animate && i < 20));
      }

      root.textContent = "";
      root.appendChild(frag);

      var rest = total - end;
      if (rest > 0) {
        var filler = document.createElement("li");
        filler.className = "batch-list-filler";
        filler.setAttribute("aria-hidden", "true");
        filler.style.height = Math.max(0, rest * stride()) + "px";
        root.appendChild(filler);
      }
      /* padding-top must be set before restoring scrollTop: it represents the
         rows above the window and keeps the total height constant. */
      if (start > 0) {
        root.style.paddingTop = (start * h) + "px";
      } else {
        root.style.removeProperty("padding-top");
      }

      winStart = start;
      winEnd = end;

      var remeasure = !measured && measureRow();
      root.scrollTop = keep;
      if (remeasure) {
        /* the measured stride changed the placeholder sizes; redraw once */
        render(true, false);
        return;
      }
      if (focusRow >= start && focusRow < end) {
        var button = root.querySelector('.batch-row[data-index="' + focusRow + '"] .batch-row-open');
        if (button) {
          try { button.focus({ preventScroll: true }); } catch (e) { button.focus(); }
        }
      }
    }

    function onScroll() {
      if (!enabled || scrollQueued) return;
      scrollQueued = true;
      schedule(function () {
        scrollQueued = false;
        render(false, false);
      });
    }

    function setItems(list) {
      items = list || [];
      enabled = items.length > threshold;
      winStart = -1;
      winEnd = -1;
      measured = false;
      if (!root) return;
      root.scrollTop = 0;
      root.style.removeProperty("padding-top");
      root.textContent = "";
      if (enabled) {
        readGap();
        render(true, true);
      }
    }

    function refresh() {
      if (enabled) render(true, false);
    }

    if (root) {
      root.addEventListener("scroll", onScroll, { passive: true });
      if (typeof ResizeObserver === "function") {
        new ResizeObserver(function () {
          if (enabled) render(true, false);
        }).observe(root);
      }
      window.addEventListener("resize", function () {
        if (enabled) render(true, false);
      });
    }

    return {
      setItems: setItems,
      refresh: refresh,
      isEnabled: function () { return enabled; },
      rowCount: function () { return items.length; },
      windowRange: function () { return { start: winStart, end: winEnd }; }
    };
  }

  TD.components.virtualList = { create: create };
})();
