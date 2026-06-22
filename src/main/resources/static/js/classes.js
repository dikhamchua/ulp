/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Class management page behavior
   Loaded by /classes/manage. Requires app.js (shared dropdown toggle).
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // ── Sort: update label on select ───────────────────────────────────
  var sortDd = document.getElementById('sortDd');
  if (sortDd) {
    sortDd.querySelectorAll('.menu-item[data-sort]').forEach(function (item) {
      item.addEventListener('click', function () {
        var label = document.getElementById('sortLabel');
        if (label) label.textContent = item.dataset.sort;

        // Remove check icon from all items, add to selected
        sortDd.querySelectorAll('.menu-item .check').forEach(function (c) { c.remove(); });
        var svgNS = 'http://www.w3.org/2000/svg';
        var svg = document.createElementNS(svgNS, 'svg');
        svg.setAttribute('class', 'ico ico-sm check');
        svg.setAttribute('viewBox', '0 0 24 24');
        var p = document.createElementNS(svgNS, 'path');
        p.setAttribute('d', 'M20 6L9 17l-5-5');
        svg.appendChild(p);
        item.appendChild(svg);

        sortDd.classList.remove('open');
      });
    });
  }

  // ── Tabs: simple visual toggle (no panel switching yet) ────────────
  document.querySelectorAll('.tab').forEach(function (t) {
    t.addEventListener('click', function () {
      document.querySelectorAll('.tab').forEach(function (x) { x.classList.remove('active'); });
      t.classList.add('active');
    });
  });

  // ── Rank toggle: flip label ────────────────────────────────────────
  var rankToggle = document.getElementById('rankToggle');
  if (rankToggle) {
    rankToggle.addEventListener('click', function () {
      var s = this.querySelector('span');
      s.textContent = s.textContent === 'Hiện xếp hạng' ? 'Ẩn xếp hạng' : 'Hiện xếp hạng';
    });
  }

  // ── Copy class code to clipboard ───────────────────────────────────
  document.querySelectorAll('.copy-code').forEach(function (btn) {
    btn.addEventListener('click', function (e) {
      e.stopPropagation();
      e.preventDefault();
      var code = btn.dataset.code;
      if (!code) return;
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(code).catch(function () {});
      }
    });
  });

  // ── Search: simple client-side filter ──────────────────────────────
  var searchInput = document.getElementById('searchInput');
  if (searchInput) {
    searchInput.addEventListener('input', function () {
      var q = this.value.trim().toLowerCase();
      document.querySelectorAll('.class-row').forEach(function (row) {
        var name = (row.querySelector('.class-info h4')?.textContent || '').toLowerCase();
        var code = (row.querySelector('.class-code b')?.textContent || '').toLowerCase();
        row.style.display = (!q || name.includes(q) || code.includes(q)) ? '' : 'none';
      });
    });
  }

  // ── Row click: navigate to class detail (skip clicks on menu/buttons) ──
  document.querySelectorAll('.class-row').forEach(function (row) {
    row.addEventListener('click', function (e) {
      if (e.target.closest('.row-menu') || e.target.closest('.copy-code')) return;
      var id = row.dataset.classId;
      if (id) window.location.href = '/lecturer/classes/' + id;
    });
  });
})();
