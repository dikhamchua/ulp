/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Student classes behavior
   - Flash → toast on page load (success / error / info)
   - Leave-class menu action gated by confirm modal
   - Auto-uppercase the join-code input as the user types
   - Copy class code to clipboard
   - Client-side search + sort over student class cards
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // ── Flash → toast on page load ─────────────────────────────────────
  var flashData = document.getElementById('flash-data');
  if (flashData && window.UlpToast) {
    var ok = flashData.dataset.flashSuccess;
    var err = flashData.dataset.flashError;
    var info = flashData.dataset.flashInfo;
    if (ok) window.UlpToast.success(ok);
    if (err) window.UlpToast.error(err);
    if (info) window.UlpToast.info(info);
  }

  // ── Auto-uppercase the join code input ────────────────────────────
  var codeInput = document.getElementById('code');
  if (codeInput) {
    codeInput.addEventListener('input', function () {
      var pos = codeInput.selectionStart;
      codeInput.value = codeInput.value.toUpperCase();
      try { codeInput.setSelectionRange(pos, pos); } catch (e) { /* ignored */ }
    });
  }

  // ── "Rời khỏi lớp" menu action → confirm → submit hidden form ──────
  document.addEventListener('click', function (e) {
    var trigger = e.target.closest('[data-action="leave-class"]');
    if (!trigger) return;
    e.preventDefault();
    var classId = trigger.dataset.classId;
    var className = trigger.dataset.className || 'này';
    var form = document.getElementById('leave-form-' + classId);
    if (!form) return;
    if (!window.UlpModal || !window.UlpModal.confirm) {
      form.submit();
      return;
    }
    window.UlpModal.confirm({
      title: 'Rời lớp học',
      body: 'Bạn có chắc muốn rời lớp ' + className + '?',
      confirmLabel: 'Rời lớp',
      onConfirm: function () { form.submit(); }
    });
  });

  // ── Copy class code to clipboard ──────────────────────────────────
  document.addEventListener('click', function (e) {
    var btn = e.target.closest('.copy-code');
    if (!btn) return;
    e.preventDefault();
    e.stopPropagation();
    var code = btn.dataset.code || '';
    if (!code) return;
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(code).then(function () {
        if (window.UlpToast) window.UlpToast.success('Đã sao chép mã: ' + code);
      });
    }
  });

  // ── Client-side search + sort over student class rows ─────────────
  var searchInput = document.getElementById('searchInput');
  var rows = Array.prototype.slice.call(document.querySelectorAll('.student-class-row'));
  var listContainer = document.querySelector('.class-list');

  function applyFilter() {
    if (!searchInput) return;
    var q = (searchInput.value || '').toLowerCase().trim();
    rows.forEach(function (r) {
      var name = (r.dataset.className || '').toLowerCase();
      var code = (r.dataset.classCode || '').toLowerCase();
      r.style.display = (!q || name.includes(q) || code.includes(q)) ? '' : 'none';
    });
  }
  if (searchInput) searchInput.addEventListener('input', applyFilter);

  // Sort menu items
  document.querySelectorAll('.sort .menu-item[data-sort-key]').forEach(function (item) {
    item.addEventListener('click', function () {
      var key = item.dataset.sortKey;
      var lbl = document.getElementById('sortLabel');
      if (lbl) lbl.textContent = item.dataset.sort || 'Sắp xếp...';
      sortRows(key);
    });
  });

  function sortRows(key) {
    if (!listContainer) return;
    var sorted = rows.slice();
    sorted.sort(function (a, b) {
      if (key === 'name-asc') return (a.dataset.className || '').localeCompare(b.dataset.className || '');
      if (key === 'name-desc') return (b.dataset.className || '').localeCompare(a.dataset.className || '');
      if (key === 'joined-asc') return (a.dataset.joinedAt || '').localeCompare(b.dataset.joinedAt || '');
      // default joined-desc
      return (b.dataset.joinedAt || '').localeCompare(a.dataset.joinedAt || '');
    });
    sorted.forEach(function (r) { listContainer.appendChild(r); });
  }

})();
