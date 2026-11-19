/* ===== TD.components.virtualList - windowed rendering for long lists =====
   Renders only the rows around the viewport plus a few of overscan. Rows are
   recycled: nodes that leave the window go into a pool and are reused for new
   indices (updated via the caller's updateRow), so a scroll frame only touches
   the handful of rows that actually enter/leave instead of rebuilding the
   whole window. Scroll geometry is kept identical to the full list via
   padding-top (rows above) and a single filler <li> (rows below). */
(function () {
  "use strict";
  window.TD = window.TD || {};
  TD.components = TD.components || {};

  function create(options) {
    var root = options.root;
    var renderRow = options.renderRow;      /* (index, animate) -> element */
    var updateRow = options.updateRow || null; /* (node, index) -> node, fills an existing node */
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

    /* recycled node pool + index->node map for the current window */
    var rendered = {};
    var pool = [];

    function readGap() {
      try {
        var cs = window.getComputedStyle(root);
        var value = parseFloat(cs.rowGap || cs.gap || "0");
        gap = isFinite(value) ? value : 0;
      } catch (e) { gap = 0; }
    }

    function stride() { return rowHeight + gap; }

    function measureRow() {
      var first = root.querySelector(".batch-row");
      if (!first) return false;
      var rect = first.getBoundingClientRect();
      var mb = 0;
      try { mb = parseFloat(window.getComputedStyle(first).marginBottom) || 0; } catch (e) { mb = 0; }
      var height = rect.height + mb;
      if (height > 0 && Math.abs(height - rowHeight) > 0.5) {
        rowHeight = height;
        measured = true;
        return true;
      }
      return false;
    }

    function schedule(fn) { window.requestAnimationFrame(fn); }

    function render(force, animate) {
      if (!root) return;
      var total = items.length;
      if (!enabled) return;
      if (!measured) readGap();

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

      /* remember focus */
      var focusRow = -1;
      var active = document.activeElement;
      if (active && root.contains(active)) {
        var holder = active.closest ? active.closest(".batch-row") : null;
        if (holder) {
          var parsed = parseInt(holder.getAttribute("data-index"), 10);
          if (isFinite(parsed)) focusRow = parsed;
        }
      }

      var newSet = {};
      for (var i = start; i < end; i++) newSet[i] = true;

      /* detach leaving nodes into the pool */
      var leaving = [];
      for (var k in rendered) {
        var ki = parseInt(k, 10);
        if (!newSet[ki]) leaving.push(ki);
      }
      for (var l = 0; l < leaving.length; l++) {
        var li = leaving[l];
        var node = rendered[li];
        if (node && node.parentNode === root) root.removeChild(node);
        pool.push(node);
        delete rendered[li];
      }

      /* ensure a node exists for every index in [start,end) */
      for (var i = start; i < end; i++) {
        if (!rendered[i]) {
          var node = pool.length ? pool.pop() : null;
          if (node) {
            if (updateRow) updateRow(node, i);
          } else {
            node = renderRow(i, animate && i < 20);
          }
          rendered[i] = node;
        }
      }

      /* reorder DOM to index order without clearing */
      var cursor = root.firstChild;
      for (var i = start; i < end; i++) {
        var node = rendered[i];
        while (cursor && cursor !== node && cursor.classList && cursor.classList.contains('batch-list-filler')) {
          var nxt = cursor.nextSibling;
          root.removeChild(cursor);
          cursor = nxt;
        }
        if (cursor === node) {
          cursor = cursor.nextSibling;
        } else {
          root.insertBefore(node, cursor);
        }
      }
      /* drop trailing nodes/fillers */
      while (cursor) {
        var nxt = cursor.nextSibling;
        root.removeChild(cursor);
        if (cursor.classList && cursor.classList.contains('batch-row')) pool.push(cursor);
        cursor = nxt;
      }

      /* filler for rows below the window */
      var rest = total - end;
      if (rest > 0) {
        var filler = document.createElement("li");
        filler.className = "batch-list-filler";
        filler.setAttribute("aria-hidden", "true");
        filler.style.height = Math.max(0, rest * stride()) + "px";
        root.appendChild(filler);
      }

      if (start > 0) root.style.paddingTop = (start * h) + "px";
      else root.style.removeProperty("padding-top");

      winStart = start;
      winEnd = end;

      var remeasure = !measured && measureRow();
      root.scrollTop = keep;
      if (remeasure) { render(true, false); return; }
      if (focusRow >= start && focusRow < end) {
        var button = root.querySelector('.batch-row[data-index="' + focusRow + '"] .batch-row-open');
        if (button) { try { button.focus({ preventScroll: true }); } catch (e) { button.focus(); } }
      }
    }

    function onScroll() {
      if (!enabled || scrollQueued) return;
      scrollQueued = true;
      schedule(function () { scrollQueued = false; render(false, false); });
    }

    function setItems(list) {
      items = list || [];
      enabled = items.length > threshold;
      winStart = -1;
      winEnd = -1;
      measured = false;
      /* detach everything into the pool */
      for (var k in rendered) {
        var node = rendered[k];
        if (node && node.parentNode === root) root.removeChild(node);
        pool.push(node);
        delete rendered[k];
      }
      if (!root) return;
      root.scrollTop = 0;
      root.style.removeProperty("padding-top");
      root.textContent = "";
      pool = [];
      if (enabled) { readGap(); render(true, true); }
    }

    function refresh() { if (enabled) render(true, false); }

    if (root) {
      root.addEventListener("scroll", onScroll, { passive: true });
      if (typeof ResizeObserver === "function") {
        new ResizeObserver(function () { if (enabled) render(true, false); }).observe(root);
      }
      window.addEventListener("resize", function () { if (enabled) render(true, false); });
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
