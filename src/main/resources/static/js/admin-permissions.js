/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Admin Permissions pages behaviour
   Flash → toast drain, matrix checkbox auto-submit.
   Requires app.js (UlpToast).
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // ── Flash drain → toast ──────────────────────────────────────────
  var flashData = document.getElementById('flash-data');
  if (flashData && window.UlpToast) {
    if (flashData.dataset.flashSuccess) window.UlpToast.success(flashData.dataset.flashSuccess);
    if (flashData.dataset.flashError)   window.UlpToast.error(flashData.dataset.flashError);
  }

  // ── Matrix cell: ticking a checkbox submits its own one-cell form ──
  // The hidden `granted` field already carries the NEW state, so the box is
  // only a trigger — the browser never posts its own checked value.
  document.addEventListener('change', function (ev) {
    var box = ev.target.closest('.perm-cell-form .perm-check');
    if (!box || box.disabled) return;
    var form = box.closest('form');
    if (form) form.requestSubmit();
  });
})();
