/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Admin Users page behaviour
   Kebab menu actions, confirmation modals, lock-reason and reset-password
   modals, flash → toast drain, modal re-open on flash validation error.

   Accessibility notes:
   - Modals are marked aria-modal="true" in the template.
   - Focus is moved into the modal on open and trapped while it is visible
     (Tab cycles within the modal's focusable elements).
   - Focus is returned to the trigger element on close so keyboard users keep
     their position in the kebab menu.
   - Esc and overlay click both close.

   Requires app.js (UlpToast + dropdown bootstrap).
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // ── Flash drain → toast + modal re-open ──────────────────────────
  var flashData = document.getElementById('flash-data');
  if (flashData && window.UlpToast) {
    if (flashData.dataset.flashSuccess) window.UlpToast.success(flashData.dataset.flashSuccess);
    if (flashData.dataset.flashError)   window.UlpToast.error(flashData.dataset.flashError);
    if (flashData.dataset.flashWarning) window.UlpToast.warning(flashData.dataset.flashWarning);
  }

  // ── Confirmation prompts (simple POST submissions) ──────────────
  // Each action maps to: confirmation message, target hidden-form prefix,
  // and whether the confirm button should style as "danger" (red).
  // Deactivate, lock, delete are flagged danger because they all remove
  // the user's ability to use the platform — single-color (red) signal.
  var confirmActions = {
    activate:   { confirm: 'Kích hoạt tài khoản này?',     form: 'form-activate-',   danger: false },
    deactivate: { confirm: 'Vô hiệu hoá tài khoản này? Người dùng sẽ không đăng nhập được.', form: 'form-deactivate-', danger: true },
    unlock:     { confirm: 'Mở khoá tài khoản này?',       form: 'form-unlock-',     danger: false },
    delete:     { confirm: 'Xoá tài khoản này? Có thể khôi phục từ bộ lọc Đã xoá.', form: 'form-delete-',  danger: true  },
    restore:    { confirm: 'Khôi phục tài khoản đã xoá?',  form: 'form-restore-',    danger: false }
  };

  // ── Focus management helpers ────────────────────────────────────
  var FOCUSABLE_SELECTOR = [
    'a[href]', 'button:not([disabled])', 'input:not([disabled])',
    'select:not([disabled])', 'textarea:not([disabled])', '[tabindex]:not([tabindex="-1"])'
  ].join(',');

  // Remember the element that was focused before each modal opens so we
  // can restore focus when the modal closes. Each modal stores its own
  // "previous focus" to support stacked modals (not currently used but safe).
  var modalPreviousFocus = { lock: null, reset: null, confirm: null };

  function focusableChildren(modalEl) {
    return Array.prototype.slice.call(modalEl.querySelectorAll(FOCUSABLE_SELECTOR))
            .filter(function (el) { return el.offsetParent !== null; });
  }

  function trapFocus(modalEl, event) {
    if (event.key !== 'Tab') return;
    var focusable = focusableChildren(modalEl);
    if (focusable.length === 0) return;
    var first = focusable[0];
    var last  = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  function openModal(modalEl, key, initialFocusEl) {
    if (!modalEl) return;
    modalPreviousFocus[key] = document.activeElement;
    modalEl.hidden = false;
    if (initialFocusEl) initialFocusEl.focus();
    modalEl.__focusHandler = function (ev) { trapFocus(modalEl, ev); };
    modalEl.addEventListener('keydown', modalEl.__focusHandler);
  }

  function closeModal(modalEl, key) {
    if (!modalEl) return;
    modalEl.hidden = true;
    if (modalEl.__focusHandler) {
      modalEl.removeEventListener('keydown', modalEl.__focusHandler);
      modalEl.__focusHandler = null;
    }
    var prev = modalPreviousFocus[key];
    if (prev && typeof prev.focus === 'function') {
      prev.focus();
    }
    modalPreviousFocus[key] = null;
  }

  // ── Generic confirm modal ───────────────────────────────────────
  var confirmModal = document.getElementById('confirmModal');
  var confirmModalBody = document.getElementById('confirmModalBody');
  var confirmModalOk = document.getElementById('confirmModalOk');
  var confirmPendingFormId = null;

  function openConfirmModal(message, formId, danger) {
    if (!confirmModal) return;
    confirmPendingFormId = formId;
    confirmModalBody.textContent = message;
    confirmModalOk.classList.toggle('btn-danger', !!danger);
    confirmModalOk.classList.toggle('btn-primary', !danger);
    openModal(confirmModal, 'confirm', confirmModalOk);
  }

  function closeConfirmModal() {
    if (!confirmModal) return;
    confirmPendingFormId = null;
    closeModal(confirmModal, 'confirm');
  }

  if (confirmModalOk) {
    confirmModalOk.addEventListener('click', function () {
      if (!confirmPendingFormId) return;
      var form = document.getElementById(confirmPendingFormId);
      if (form) form.submit();
    });
  }

  document.addEventListener('click', function (ev) {
    var btn = ev.target.closest('button[data-action]');
    if (!btn) return;
    var action = btn.getAttribute('data-action');
    var userId = btn.getAttribute('data-user-id');

    // Detail-page toolbar Xóa button — userId is not on the button itself;
    // read it from the toolbar's data attribute so the same modal flow as
    // the list-page row menu can be reused without new markup.
    if (action === 'delete-user') {
      var toolbar = btn.closest('.detail-toolbar');
      var detailUserId = toolbar ? toolbar.getAttribute('data-user-id') : null;
      if (!detailUserId) return;
      openConfirmModal(
        'Xoá tài khoản này? Có thể khôi phục từ bộ lọc Đã xoá.',
        'form-delete-' + detailUserId,
        true
      );
      return;
    }

    if (confirmActions[action]) {
      var cfg = confirmActions[action];
      openConfirmModal(cfg.confirm, cfg.form + userId, cfg.danger);
      return;
    }

    if (action === 'lock') {
      openLockModal(userId, '');
    } else if (action === 'reset-password') {
      openResetModal(userId);
    }
  });

  // ── Lock modal ──────────────────────────────────────────────────
  var lockModal = document.getElementById('lockModal');
  var lockReasonInput = document.getElementById('lockReasonInput');
  var lockConfirmBtn = document.getElementById('lockConfirmBtn');
  var lockCurrentUserId = null;

  function openLockModal(userId, prefilledReason) {
    if (!lockModal) return;
    lockCurrentUserId = userId;
    lockReasonInput.value = prefilledReason || '';
    openModal(lockModal, 'lock', lockReasonInput);
  }

  function closeLockModal() {
    if (!lockModal) return;
    lockCurrentUserId = null;
    lockReasonInput.value = '';
    closeModal(lockModal, 'lock');
  }

  if (lockConfirmBtn) {
    lockConfirmBtn.addEventListener('click', function () {
      // Reason is trimmed only for the emptiness check. The original (with
      // surrounding whitespace, if any) is what posts to the server, but the
      // server-side validator also normalises and a meaningful reason should
      // not depend on trailing spaces.
      var reasonRaw = lockReasonInput.value || '';
      if (!reasonRaw.trim()) {
        if (window.UlpToast) window.UlpToast.error('Vui lòng nhập lý do khoá.');
        lockReasonInput.focus();
        return;
      }
      var form = document.getElementById('form-lock-' + lockCurrentUserId);
      if (!form) return;
      form.querySelector('input[name=lockedReason]').value = reasonRaw;
      form.submit();
    });
  }

  // ── Reset password modal ────────────────────────────────────────
  var resetModal = document.getElementById('resetPasswordModal');
  var resetPasswordInput = document.getElementById('resetPasswordInput');
  var resetConfirmBtn = document.getElementById('resetConfirmBtn');
  var resetCurrentUserId = null;

  function openResetModal(userId) {
    if (!resetModal) return;
    resetCurrentUserId = userId;
    resetPasswordInput.value = '';
    openModal(resetModal, 'reset', resetPasswordInput);
  }

  function closeResetModal() {
    if (!resetModal) return;
    resetCurrentUserId = null;
    resetPasswordInput.value = '';
    closeModal(resetModal, 'reset');
  }

  if (resetConfirmBtn) {
    resetConfirmBtn.addEventListener('click', function () {
      // Password is checked for emptiness only — do NOT trim because leading
      // or trailing whitespace might be intentional and we must POST the
      // exact value the admin typed.
      var pwd = resetPasswordInput.value || '';
      if (pwd.length === 0) {
        if (window.UlpToast) window.UlpToast.error('Vui lòng nhập mật khẩu mới.');
        resetPasswordInput.focus();
        return;
      }
      var form = document.getElementById('form-reset-' + resetCurrentUserId);
      if (!form) return;
      form.querySelector('input[name=newPassword]').value = pwd;
      form.submit();
    });
  }

  // ── Shared cancel handlers ──────────────────────────────────────
  document.addEventListener('click', function (ev) {
    if (ev.target.matches('[data-modal-cancel]')) {
      closeLockModal();
      closeResetModal();
      closeConfirmModal();
    }
    // Click on overlay (but not on inner box) closes the modal.
    if (ev.target.classList.contains('modal-overlay')) {
      closeLockModal();
      closeResetModal();
      closeConfirmModal();
    }
  });

  document.addEventListener('keydown', function (ev) {
    if (ev.key === 'Escape') {
      closeLockModal();
      closeResetModal();
      closeConfirmModal();
    }
  });

  // ── Re-open lock / reset modals when the server flashes validation errors
  //    with previously-entered values preserved. The flash-data span carries
  //    the payload keys; the lock modal also restores the typed reason.
  if (flashData) {
    var lockReopenId     = flashData.dataset.lockReopenUserId;
    var lockReopenReason = flashData.dataset.lockReopenReason;
    var resetReopenId    = flashData.dataset.resetReopenUserId;

    if (lockReopenId) {
      openLockModal(lockReopenId, lockReopenReason || '');
    } else if (resetReopenId) {
      openResetModal(resetReopenId);
    }
  }
})();
