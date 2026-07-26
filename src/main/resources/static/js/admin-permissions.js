/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Admin Permissions pages behaviour
   Flash → toast drain, group master/detail selection, matrix checkbox submit.
   Requires app.js (UlpToast).
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // Flash toasts are drained once by notifications.js (app-header).
  // Do not drain here — a second pass causes duplicate toasts.

  // ── Group master/detail ──────────────────────────────────────────
  // The selected group lives in the URL hash, not in a JS variable: toggling a
  // cell POSTs and redirects, and the browser carries the hash across that
  // redirect. Keeping it in the URL is what puts the admin back on the group
  // they were editing instead of resetting to the first one.
  var items = Array.prototype.slice.call(document.querySelectorAll('.perm-master-item'));
  var panes = Array.prototype.slice.call(document.querySelectorAll('.perm-detail-pane'));

  if (items.length) {
    var showGroup = function (name) {
      var matched = false;
      panes.forEach(function (pane) {
        var on = pane.dataset.group === name;
        pane.hidden = !on;
        if (on) matched = true;
      });
      items.forEach(function (item) {
        item.classList.toggle('is-active', item.dataset.group === name);
        // Only the active item is a tab stop, matching native tablist behaviour.
        item.setAttribute('aria-current', item.dataset.group === name ? 'true' : 'false');
      });
      return matched;
    };

    var currentGroup = function () {
      // decodeURIComponent: group names are ASCII today but the hash is escaped
      // by the browser, so decode rather than assume it round-trips unchanged.
      var raw = window.location.hash.replace(/^#/, '');
      try { return decodeURIComponent(raw); } catch (e) { return raw; }
    };

    // Fall back to the first group when the hash is absent or names a group
    // that no longer exists (e.g. a stale bookmark after a catalogue change).
    var sync = function () {
      if (!showGroup(currentGroup())) showGroup(items[0].dataset.group);
    };

    window.addEventListener('hashchange', sync);
    sync();
  }

  // ── Matrix cell: ticking a checkbox submits its own one-cell form ──
  // The hidden `granted` field already carries the NEW state, so the box is
  // only a trigger — the browser never posts its own checked value.
  document.addEventListener('change', function (ev) {
    var box = ev.target.closest('.perm-cell-form .perm-check');
    if (!box || box.disabled) return;
    var form = box.closest('form');
    if (!form) return;

    // Append the open group to the POST target so the redirect lands back on
    // it. The server redirects to a fragment-less URL, and a redirect Location
    // without its own fragment is what the browser navigates to — so relying
    // on the current hash surviving would drop the admin back on group one.
    var pane = form.closest('.perm-detail-pane');
    if (pane) {
      var base = form.action.split('#')[0];
      form.action = base + '#' + encodeURIComponent(pane.dataset.group);
    }
    form.requestSubmit();
  });
})();
