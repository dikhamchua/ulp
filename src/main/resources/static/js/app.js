/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Shared client-side behavior (vanilla JS, no framework)
   - Dropdown toggle (click trigger → open/close menu, close-on-outside-click)
   - Tab switching
   - Confirm modal helper (window.UlpModal.confirm)
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // ── Dropdown toggle ────────────────────────────────────────────────
  document.addEventListener('click', function (e) {
    // Close any open dropdown when clicking outside
    document.querySelectorAll('.open').forEach(function (el) {
      if (!el.contains(e.target)) {
        el.classList.remove('open');
      }
    });
  });

  document.querySelectorAll('[data-toggle="dropdown"]').forEach(function (trigger) {
    trigger.addEventListener('click', function (e) {
      e.stopPropagation();
      var parent = this.closest('.dropdown') || this.parentElement;
      if (parent) {
        // Close all other dropdowns
        document.querySelectorAll('.dropdown.open').forEach(function (d) {
          if (d !== parent) d.classList.remove('open');
        });
        parent.classList.toggle('open');
      }
    });
  });

  // ── Tab switching ──────────────────────────────────────────────────
  document.querySelectorAll('[data-tab]').forEach(function (tab) {
    tab.addEventListener('click', function () {
      var group = this.closest('[data-tab-group]');
      if (!group) return;

      var tabName = this.getAttribute('data-tab');

      // Deactivate all tabs in group
      group.querySelectorAll('[data-tab].active').forEach(function (t) {
        t.classList.remove('active');
      });

      // Activate clicked tab
      this.classList.add('active');

      // Show matching panel, hide others
      group.querySelectorAll('[data-tab-panel]').forEach(function (panel) {
        if (panel.getAttribute('data-tab-panel') === tabName) {
          panel.style.display = '';
        } else {
          panel.style.display = 'none';
        }
      });
    });
  });

  // ── Confirm modal helper (window.UlpModal.confirm) ─────────────────
  // Reusable across all pages. Uses native <dialog> when available.
  // Usage:
  //   UlpModal.confirm({
  //     title: 'Xác nhận xoá',
  //     body: 'Bạn có chắc muốn xoá lớp này?',
  //     confirmLabel: 'Xoá',
  //     onConfirm: function () { ... }
  //   });
  function buildDialog() {
    var dlg = document.getElementById('ulpConfirmDialog');
    if (dlg) return dlg;
    dlg = document.createElement('dialog');
    dlg.id = 'ulpConfirmDialog';
    dlg.className = 'ulp-modal';
    dlg.innerHTML =
      '<form method="dialog" class="ulp-modal-form">' +
      '  <h3 class="ulp-modal-title" data-role="title"></h3>' +
      '  <p class="ulp-modal-body" data-role="body"></p>' +
      '  <div class="ulp-modal-actions">' +
      '    <button type="button" class="btn-ghost" data-role="cancel">Huỷ</button>' +
      '    <button type="button" class="btn-danger" data-role="confirm">OK</button>' +
      '  </div>' +
      '</form>';
    document.body.appendChild(dlg);
    return dlg;
  }

  window.UlpModal = {
    confirm: function (opts) {
      opts = opts || {};
      var dlg = buildDialog();
      dlg.querySelector('[data-role="title"]').textContent = opts.title || 'Xác nhận';
      dlg.querySelector('[data-role="body"]').textContent = opts.body || '';
      var confirmBtn = dlg.querySelector('[data-role="confirm"]');
      var cancelBtn = dlg.querySelector('[data-role="cancel"]');
      confirmBtn.textContent = opts.confirmLabel || 'Xác nhận';

      var settled = false;
      var onConfirm = function () {
        if (settled) return;
        settled = true;
        cleanup();
        if (typeof opts.onConfirm === 'function') opts.onConfirm();
      };
      var onCancel = function () {
        if (settled) return;
        settled = true;
        cleanup();
        if (typeof opts.onCancel === 'function') opts.onCancel();
      };
      // ESC key triggers `cancel` event on native <dialog> — handle it.
      var onDialogCancel = function () { onCancel(); };

      function cleanup() {
        confirmBtn.removeEventListener('click', onConfirm);
        cancelBtn.removeEventListener('click', onCancel);
        dlg.removeEventListener('cancel', onDialogCancel);
        if (typeof dlg.close === 'function' && dlg.open) dlg.close();
        else dlg.removeAttribute('open');
      }

      confirmBtn.addEventListener('click', onConfirm);
      cancelBtn.addEventListener('click', onCancel);
      dlg.addEventListener('cancel', onDialogCancel);

      if (typeof dlg.showModal === 'function') {
        dlg.showModal();
      } else {
        // Fallback for browsers without <dialog> support
        dlg.setAttribute('open', '');
      }
    }
  };

  // ── Toast helper (wraps iziToast loaded in head.html) ──────────────
  // Usage:
  //   UlpToast.success('Đã tạo lớp NILXM');
  //   UlpToast.error('Có lỗi xảy ra');
  //   UlpToast.info('Đang xử lý...');
  // Falls back to console.log if iziToast script failed to load.
  function showToast(type, message, title) {
    if (!message) return;
    if (typeof window.iziToast === 'undefined') {
      console.log('[Toast ' + type + ']', message);
      return;
    }
    var common = {
      message: message,
      position: 'topRight',
      timeout: 3500,
      progressBar: true,
      close: true,
      transitionIn: 'fadeInLeft',
      transitionOut: 'fadeOutRight'
    };
    if (title) common.title = title;
    if (type === 'success') window.iziToast.success(common);
    else if (type === 'error') { common.timeout = 5000; window.iziToast.error(common); }
    else if (type === 'warning') window.iziToast.warning(common);
    else window.iziToast.info(common);
  }

  window.UlpToast = {
    success: function (msg, title) { showToast('success', msg, title); },
    error:   function (msg, title) { showToast('error', msg, title); },
    warning: function (msg, title) { showToast('warning', msg, title); },
    info:    function (msg, title) { showToast('info', msg, title); }
  };

})();
