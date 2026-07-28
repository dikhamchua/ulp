/* HEAD department screens — page behaviour.
 *
 * NOTE: Do NOT drain the flash payload here. notifications.js (loaded by
 * fragments/app-header.html) is the single owner of the flash→toast drain.
 * A second drain in a page script fires a duplicate toast — see
 * .claude/rules/flash-toast-drain.md
 *
 * This file intentionally has no behaviour left; it is kept so the templates'
 * script tags stay valid and future HEAD-only behaviour has a home. */
(function () {
  'use strict';
})();
