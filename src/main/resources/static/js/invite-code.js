/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Invite code panel behavior (Members / Settings invite tab)
   - Copy button: writes data-copy to clipboard, success toast via UlpToast
   - Regenerate button: gates submit behind UlpModal.confirm modal

   Uses document-level delegation so the handlers keep working after an AJAX
   tab swap (detail-tabs.js replaces #tabPanel innerHTML).
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // ── Copy buttons ───────────────────────────────────────────────────
  document.addEventListener('click', function (e) {
    var btn = e.target.closest ? e.target.closest('.invite-panel .copy-btn') : null;
    if (!btn) return;

    var value = btn.dataset.copy;
    var label = btn.dataset.copyLabel || 'giá trị';
    if (!value || !navigator.clipboard) return;
    navigator.clipboard.writeText(value).then(function () {
      if (window.UlpToast) window.UlpToast.success('Đã sao chép ' + label);
    }).catch(function () {
      if (window.UlpToast) window.UlpToast.error('Không thể sao chép');
    });
  });

  // ── Regenerate buttons: confirm modal before submitting form ──────
  document.addEventListener('submit', function (e) {
    var form = e.target;
    if (!form || !form.classList || !form.classList.contains('invite-regen-form')) return;
    // If already confirmed, allow native submit through.
    if (form.dataset.confirmed === '1') return;
    e.preventDefault();
    var btn = form.querySelector('.regen-btn');
    var title = (btn && btn.dataset.confirmTitle) || 'Tạo mã mới';
    var body = (btn && btn.dataset.confirmBody)
        || 'Tạo mã mới sẽ vô hiệu mã hiện tại. Tiếp tục?';
    if (!window.UlpModal || !window.UlpModal.confirm) {
      // Fallback if app.js failed to load: skip confirmation.
      form.dataset.confirmed = '1';
      form.submit();
      return;
    }
    window.UlpModal.confirm({
      title: title,
      body: body,
      confirmLabel: 'Tạo mới',
      onConfirm: function () {
        form.dataset.confirmed = '1';
        form.submit();
      }
    });
  });

})();
