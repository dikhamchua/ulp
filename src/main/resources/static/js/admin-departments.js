/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Admin Departments page behaviour
   Flash → toast drain and row toggle (Hiện/Ẩn). Dropdown menus come from app.js.
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // Flash toasts are drained once by notifications.js (app-header).
  // Do not drain here — a second pass causes duplicate toasts.

  document.addEventListener('click', function (ev) {
    var btn = ev.target.closest('button[data-action="toggle"]');
    if (!btn) return;
    var id = btn.getAttribute('data-dept-id');
    if (!id) return;
    var form = document.getElementById('form-toggle-' + id);
    if (form) form.submit();
  });
})();
