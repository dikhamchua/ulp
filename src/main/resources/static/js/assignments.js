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
   * Attaches a confirm dialog to forms with data-confirm attribute.
   * Keeps confirm logic out of inline onclick handlers.
   */
  function bindConfirmForms() {
    document.querySelectorAll('form[data-confirm]').forEach(function (form) {
      form.addEventListener('submit', function (e) {
        var msg = form.getAttribute('data-confirm');
        if (msg && !window.confirm(msg)) {
          e.preventDefault();
        }
      });
    });
  }

  // ── Init ──────────────────────────────────────────────────────────────

  document.addEventListener('DOMContentLoaded', function () {
    bindConfirmForms();
  });
}());
