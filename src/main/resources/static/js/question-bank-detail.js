/* Question bank manage screen — bulk selection UX. Wires the select-all
   header, per-row checkboxes, live count and the show/hide of the sticky bulk
   toolbar. No inline handlers. */
(function () {
  'use strict';
  var table = document.querySelector('[data-bulk-table]');
  if (!table) {
    return;
  }
  var selectAll = table.querySelector('[data-bulk-select-all]');
  var toolbar = document.querySelector('.qb-bulk-toolbar');
  var countEl = document.querySelector('[data-bulk-count]');

  function rowChecks() {
    return Array.prototype.slice.call(table.querySelectorAll('.qb-row-check'));
  }

  function refresh() {
    var checks = rowChecks();
    var checked = checks.filter(function (c) { return c.checked; }).length;
    if (countEl) {
      countEl.textContent = String(checked);
    }
    if (toolbar) {
      toolbar.hidden = checked === 0;
    }
    if (selectAll) {
      // Header reflects all/none/partial selection state.
      selectAll.checked = checked > 0 && checked === checks.length;
      selectAll.indeterminate = checked > 0 && checked < checks.length;
    }
  }

  if (selectAll) {
    selectAll.addEventListener('change', function () {
      rowChecks().forEach(function (c) { c.checked = selectAll.checked; });
      refresh();
    });
  }

  table.addEventListener('change', function (event) {
    if (event.target && event.target.classList.contains('qb-row-check')) {
      refresh();
    }
  });

  refresh();
})();
