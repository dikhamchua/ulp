/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Class management page behavior
   Loaded by /lecturer/classes (manage). Requires app.js (UlpModal + dropdowns).
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // ── Flash → toast on page load ─────────────────────────────────────
  // Backend writes flash via RedirectAttributes. Template renders them
  // into hidden #flash-data span; we read and fire iziToast here.
  var flashData = document.getElementById('flash-data');
  if (flashData && window.UlpToast) {
    var ok = flashData.dataset.flashSuccess;
    var err = flashData.dataset.flashError;
    if (ok) window.UlpToast.success(ok);
    if (err) window.UlpToast.error(err);
  }

  // ── Sort ───────────────────────────────────────────────────────────
  // Supported keys: name-asc, name-desc, created-desc (default), created-asc, student-desc.
  // Each row carries data-class-name, data-created-at (ISO), data-student-count.
  var sortDd = document.getElementById('sortDd');
  if (sortDd) {
    sortDd.querySelectorAll('.menu-item[data-sort]').forEach(function (item) {
      item.addEventListener('click', function () {
        var label = document.getElementById('sortLabel');
        if (label) label.textContent = item.dataset.sort;

        // Check icon
        sortDd.querySelectorAll('.menu-item .check').forEach(function (c) { c.remove(); });
        var svgNS = 'http://www.w3.org/2000/svg';
        var svg = document.createElementNS(svgNS, 'svg');
        svg.setAttribute('class', 'ico ico-sm check');
        svg.setAttribute('viewBox', '0 0 24 24');
        var p = document.createElementNS(svgNS, 'path');
        p.setAttribute('d', 'M20 6L9 17l-5-5');
        svg.appendChild(p);
        item.appendChild(svg);

        sortRows(item.dataset.sortKey || labelToKey(item.dataset.sort));
        sortDd.classList.remove('open');
      });
    });
  }

  function labelToKey(label) {
    switch (label) {
      case 'Mới nhất': return 'created-desc';
      case 'Cũ nhất': return 'created-asc';
      case 'Tên A-Z': return 'name-asc';
      case 'Tên Z-A': return 'name-desc';
      case 'Sĩ số': return 'student-desc';
      default: return 'created-desc';
    }
  }

  function sortRows(key) {
    var list = document.querySelector('.class-list');
    if (!list) return;
    var rows = Array.prototype.slice.call(list.querySelectorAll('.class-row'));
    rows.sort(function (a, b) {
      switch (key) {
        case 'name-asc':
          return (a.dataset.className || '').localeCompare(b.dataset.className || '', 'vi');
        case 'name-desc':
          return (b.dataset.className || '').localeCompare(a.dataset.className || '', 'vi');
        case 'created-asc':
          return (a.dataset.createdAt || '').localeCompare(b.dataset.createdAt || '');
        case 'student-desc':
          return (parseInt(b.dataset.studentCount, 10) || 0) - (parseInt(a.dataset.studentCount, 10) || 0);
        case 'created-desc':
        default:
          return (b.dataset.createdAt || '').localeCompare(a.dataset.createdAt || '');
      }
    });
    rows.forEach(function (r) { list.appendChild(r); });
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
        navigator.clipboard.writeText(code).then(function () {
          if (window.UlpToast) window.UlpToast.success('Đã sao chép mã ' + code);
        }).catch(function () {
          if (window.UlpToast) window.UlpToast.error('Không sao chép được, vui lòng thử lại');
        });
      }
    });
  });

  // ── Search: filter rows by data-class-name + data-class-code ───────
  var searchInput = document.getElementById('searchInput');
  if (searchInput) {
    searchInput.addEventListener('input', function () {
      var q = this.value.trim().toLowerCase();
      document.querySelectorAll('.class-row').forEach(function (row) {
        var name = (row.dataset.className || '').toLowerCase();
        var code = (row.dataset.classCode || '').toLowerCase();
        row.style.display = (!q || name.includes(q) || code.includes(q)) ? '' : 'none';
      });
    });
  }

  // ── Delete: confirm modal + submit hidden form ─────────────────────
  // Hidden form per row preserves CSRF token (must NOT use fetch/XHR).
  document.querySelectorAll('[data-action="delete-class"]').forEach(function (btn) {
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      var classId = btn.dataset.classId;
      var className = btn.dataset.className || 'lớp này';
      if (!classId || !window.UlpModal) return;

      window.UlpModal.confirm({
        title: 'Xác nhận xoá lớp',
        body: 'Bạn có chắc chắn muốn xoá ' + className + '? Hành động này không thể hoàn tác.',
        confirmLabel: 'Xoá',
        onConfirm: function () {
          var form = document.getElementById('delete-form-' + classId);
          if (form) form.submit(); // form.submit keeps CSRF token intact
        }
      });
    });
  });

})();
