/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Admin Categories page behaviour
   Flash → toast drain, toggle-active POST, delete confirmation modal.
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

  // ── Delete confirmation modal ────────────────────────────────────
  var confirmModal = document.getElementById('confirmModal');
  var confirmModalBody = document.getElementById('confirmModalBody');
  var confirmModalOk = document.getElementById('confirmModalOk');
  var pendingFormId = null;

  function openConfirm(message, formId) {
    if (!confirmModal) return;
    pendingFormId = formId;
    if (confirmModalBody) confirmModalBody.textContent = message;
    confirmModal.hidden = false;
    if (confirmModalOk) confirmModalOk.focus();
  }

  function closeConfirm() {
    if (!confirmModal) return;
    pendingFormId = null;
    confirmModal.hidden = true;
  }

  if (confirmModalOk) {
    confirmModalOk.addEventListener('click', function () {
      if (!pendingFormId) return;
      var form = document.getElementById(pendingFormId);
      if (form) form.submit();
    });
  }

  // ── Expand / collapse a parent's child rows ──────────────────────
  document.addEventListener('click', function (ev) {
    var toggle = ev.target.closest('.cat-toggle');
    if (!toggle) return;
    var parentId = toggle.getAttribute('data-expand-target');
    if (!parentId) return;
    var expanded = toggle.getAttribute('aria-expanded') !== 'false';
    toggle.setAttribute('aria-expanded', expanded ? 'false' : 'true');
    document.querySelectorAll('tr[data-child-of="' + parentId + '"]')
        .forEach(function (row) { row.hidden = expanded; });
  });

  // ── Row action buttons (toggle / delete) ─────────────────────────
  document.addEventListener('click', function (ev) {
    var btn = ev.target.closest('button[data-action]');
    if (!btn) return;
    var action = btn.getAttribute('data-action');
    var catId = btn.getAttribute('data-cat-id');
    if (!catId) return;

    if (action === 'toggle') {
      // Toggle is a low-risk state flip — submit immediately, no modal.
      var toggleForm = document.getElementById('form-toggle-' + catId);
      if (toggleForm) toggleForm.submit();
    } else if (action === 'delete') {
      var name = btn.getAttribute('data-cat-name') || '';
      openConfirm('Xoá danh mục "' + name + '"? Thao tác này không thể hoàn tác.',
              'form-delete-' + catId);
    }
  });

  // ── Cancel / overlay / Esc close ─────────────────────────────────
  document.addEventListener('click', function (ev) {
    if (ev.target.matches('[data-modal-cancel]')) closeConfirm();
    if (ev.target.classList.contains('modal-overlay')) closeConfirm();
  });
  document.addEventListener('keydown', function (ev) {
    if (ev.key === 'Escape') closeConfirm();
  });
})();
