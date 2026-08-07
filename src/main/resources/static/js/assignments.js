/**
 * Assignments feature — client-side behaviours.
 *
 * Responsibilities:
 *   1. Confirm dialogs for destructive/irreversible actions (publish, close).
 */
(function () {
  'use strict';

  // NOTE: Do NOT drain the flash payload here. notifications.js (loaded by
  // fragments/app-header.html) is the single owner of the flash→toast drain.
  // A second drain in a page script fires a duplicate toast — see
  // .claude/rules/flash-toast-drain.md

  // ── Confirm dialogs ───────────────────────────────────────────────────

  /**
   * Attaches a confirm dialog to forms with a data-confirm attribute.
   * Keeps confirm logic out of inline onclick handlers.
   *
   * <p>Confirmation runs through the shared UlpModal, which is asynchronous —
   * so the first submit is cancelled and re-issued via requestSubmit() once the
   * user confirms. The `proceeding` latch lets that second submit through
   * instead of re-opening the modal. requestSubmit() rather than submit() so
   * any other submit listener on the form still runs.
   * See .claude/rules/deferred-upload-on-save.md for the same pattern.
   */
  function bindConfirmForms() {
    document.querySelectorAll('form[data-confirm]').forEach(function (form) {
      var proceeding = false;
      form.addEventListener('submit', function (e) {
        if (proceeding) return; // confirmed submit: let the native POST run
        var msg = form.getAttribute('data-confirm');
        if (!msg) return;
        e.preventDefault();

        function proceed() {
          proceeding = true;
          if (typeof form.requestSubmit === 'function') form.requestSubmit();
          else form.submit();
        }
        // Native window.confirm stays a hard fallback in case app.js fails to
        // load — matches library.js / sections.js.
        if (window.UlpModal && typeof window.UlpModal.confirm === 'function') {
          window.UlpModal.confirm({
            title: 'Xác nhận',
            body: msg,
            confirmLabel: 'Tiếp tục',
            onConfirm: proceed
          });
        } else if (window.confirm(msg)) {
          proceed();
        }
      });
    });
  }

  // ── Init ──────────────────────────────────────────────────────────────

  document.addEventListener('DOMContentLoaded', function () {
    bindConfirmForms();
  });
}());
