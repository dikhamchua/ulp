/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Lecturer teaching dashboard behavior
   Loaded by /lecturer/dashboard. Requires app.js (UlpToast).
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // NOTE: Do NOT drain the flash payload here. notifications.js (loaded by
  // fragments/app-header.html) is the single owner of the flash→toast drain.
  // A second drain in a page script fires a duplicate toast — see
  // .claude/rules/flash-toast-drain.md
  //
  // This file intentionally has no behaviour left; it is kept so the template's
  // script tag stays valid and future dashboard-only behaviour has a home.
})();
