/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Lecturer teaching dashboard behavior
   Loaded by /lecturer/dashboard. Requires app.js (UlpToast).
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // Flash → toast on page load (shared pattern with admin.js / classes.js).
  var flashData = document.getElementById('flash-data');
  if (flashData && window.UlpToast) {
    var ok = flashData.dataset.flashSuccess;
    var err = flashData.dataset.flashError;
    if (ok) window.UlpToast.success(ok);
    if (err) window.UlpToast.error(err);
  }
})();
