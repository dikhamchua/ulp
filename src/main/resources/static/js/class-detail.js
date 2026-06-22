/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Class detail page behavior
   Loaded by /lecturer/classes/{id}/*. Requires app.js (UlpToast).
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // ── Flash → toast on page load ─────────────────────────────────────
  var flashData = document.getElementById('flash-data');
  if (flashData && window.UlpToast) {
    var ok = flashData.dataset.flashSuccess;
    var err = flashData.dataset.flashError;
    if (ok) window.UlpToast.success(ok);
    if (err) window.UlpToast.error(err);
  }

  // ── Copy code in sidebar share link ────────────────────────────────
  document.querySelectorAll('.copy-code').forEach(function (btn) {
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      var code = btn.dataset.code;
      if (!code || !navigator.clipboard) return;
      navigator.clipboard.writeText(code).then(function () {
        if (window.UlpToast) window.UlpToast.success('Đã sao chép mã ' + code);
      }).catch(function () {});
    });
  });

  // ── Member search (client-side) ────────────────────────────────────
  var memberSearch = document.getElementById('memberSearch');
  if (memberSearch) {
    memberSearch.addEventListener('input', function () {
      var q = this.value.trim().toLowerCase();
      document.querySelectorAll('.m-table tbody tr').forEach(function (row) {
        var name = (row.dataset.memberName || '').toLowerCase();
        var email = (row.dataset.memberEmail || '').toLowerCase();
        row.style.display = (!q || name.includes(q) || email.includes(q)) ? '' : 'none';
      });
    });
  }

})();
