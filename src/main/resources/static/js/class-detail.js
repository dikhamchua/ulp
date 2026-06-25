/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Class detail page behavior
   Loaded by /lecturer/classes/{id}/*. Requires app.js (UlpToast).
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // ── Flash → toast on page load ─────────────────────────────────────
  var flashData = document.getElementById('flash-data');
  if (flashData && window.UlpToast) {
    var ok = flashData.dataset.flashSuccess;
    var err = flashData.dataset.flashError;
    if (ok) window.UlpToast.success(ok);
    if (err) window.UlpToast.error(err);
  }

  // ── Copy buttons in sidebar share-box ──────────────────────────────
  // Wired here (not in invite-code.js) because invite-code.js scopes its
  // copy handler to .invite-panel, and the sidebar share-box lives outside
  // that panel. Both rows expose data-copy + data-copy-label so this single
  // handler covers the CODE row and the LINK row uniformly.
  document.querySelectorAll('.share-copy-btn').forEach(function (btn) {
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      var value = btn.dataset.copy;
      var label = btn.dataset.copyLabel || 'giá trị';
      if (!value || !navigator.clipboard) return;
      navigator.clipboard.writeText(value).then(function () {
        if (window.UlpToast) window.UlpToast.success('Đã sao chép ' + label);
      }).catch(function () {
        if (window.UlpToast) window.UlpToast.error('Không thể sao chép');
      });
    });
  });

  // ── Member search (client-side) ────────────────────────────────────
  var memberSearch = document.getElementById('memberSearch');
  if (memberSearch) {
    memberSearch.addEventListener('input', function () {
      var q = this.value.trim().toLowerCase();
      document.querySelectorAll('.m-table tbody tr').forEach(function (row) {
        var name = (row.dataset.memberName || '').toLowerCase();
        var email = (row.dataset.memberEmail || '').toLowerCase();
        row.style.display = (!q || name.includes(q) || email.includes(q)) ? '' : 'none';
      });
    });
  }

  // ── Toolbar "Xóa lớp" — confirm modal → submit hidden delete form ──
  // Used by the Settings tab toolbar (detail-page pattern). The hidden form
  // posts to /lecturer/classes/{id}/delete; class id + name are read from
  // the toolbar dataset so this handler stays decoupled from any specific
  // class id. Falls back silently if UlpModal is not yet available (e.g.
  // app.js failed to load).
  document.querySelectorAll('[data-action="delete-class"]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var toolbar = btn.closest('.detail-toolbar');
      var classId = toolbar && toolbar.dataset ? toolbar.dataset.classId : null;
      var className = (toolbar && toolbar.dataset && toolbar.dataset.className) || 'lớp này';
      if (!classId || !window.UlpModal) return;
      window.UlpModal.confirm({
        title: 'Xác nhận xoá lớp',
        body: 'Bạn có chắc chắn muốn xoá ' + className + '? Hành động này không thể hoàn tác.',
        confirmLabel: 'Xoá',
        onConfirm: function () {
          var form = document.getElementById('form-delete-' + classId);
          if (form) form.submit();
        }
      });
    });
  });

})();
