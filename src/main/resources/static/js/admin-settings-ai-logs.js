/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Admin AI Request Logs

   The log screen is read-only: no AJAX, no mutation endpoints. This file only
   makes the filter feel immediate — changing a dropdown submits the GET form
   instead of forcing a second click on "Áp dụng", and the page always returns
   to the first page so a filter change cannot land on an out-of-range offset.

   The button stays in the markup as the no-JS fallback.

   No flash/toast wiring here: the screen is a read-only GET with no mutation
   endpoint, so it never has a flash message to drain.
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  document.addEventListener('DOMContentLoaded', function () {
    var form = document.querySelector('.users-filter-form');
    if (!form) return;

    form.querySelectorAll('select').forEach(function (select) {
      select.addEventListener('change', function () {
        // Drop the page parameter: page 3 of the old filter is meaningless now.
        var page = form.querySelector('input[name="page"]');
        if (page) page.remove();
        form.submit();
      });
    });
  });
})();
