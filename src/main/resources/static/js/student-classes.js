/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Student classes behavior
   - Flash → toast on page load (success / error / info)
   - Leave-class form gated by confirm modal
   - Auto-uppercase the join-code input as the user types
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // ── Flash → toast on page load ─────────────────────────────────────
  var flashData = document.getElementById('flash-data');
  if (flashData && window.UlpToast) {
    var ok = flashData.dataset.flashSuccess;
    var err = flashData.dataset.flashError;
    var info = flashData.dataset.flashInfo;
    if (ok) window.UlpToast.success(ok);
    if (err) window.UlpToast.error(err);
    if (info) window.UlpToast.info(info);
  }

  // ── Auto-uppercase the join code input ────────────────────────────
  var codeInput = document.getElementById('code');
  if (codeInput) {
    codeInput.addEventListener('input', function () {
      var pos = codeInput.selectionStart;
      codeInput.value = codeInput.value.toUpperCase();
      try { codeInput.setSelectionRange(pos, pos); } catch (e) { /* ignored */ }
    });
  }

  // ── Leave-class forms: confirm modal before submitting ─────────────
  document.querySelectorAll('.leave-form').forEach(function (form) {
    form.addEventListener('submit', function (e) {
      if (form.dataset.confirmed === '1') return;
      e.preventDefault();
      var btn = form.querySelector('.leave-btn');
      var className = (btn && btn.dataset.className) || 'này';
      if (!window.UlpModal || !window.UlpModal.confirm) {
        form.dataset.confirmed = '1';
        form.submit();
        return;
      }
      window.UlpModal.confirm({
        title: 'Rời lớp học',
        body: 'Bạn có chắc muốn rời lớp ' + className + '?',
        confirmLabel: 'Rời lớp',
        onConfirm: function () {
          form.dataset.confirmed = '1';
          form.submit();
        }
      });
    });
  });

})();
