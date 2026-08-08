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
    },

    /**
     * Opens the shared picker shell — the chrome behind every "choose
     * something from a list inside a modal" flow (attach an asset, clone a
     * lesson, clone an exam).
     *
     * The shell owns the frame only: backdrop, dialog, head, step strip,
     * search box, scrolling body and footer buttons. It deliberately does NOT
     * own what goes in the body. Callers differ too much there — one renders a
     * flat class list, another renders role pickers and a content preview — so
     * forcing that through options would grow this into a config language
     * instead of an API. Callers get the body element and render into it.
     *
     * Returns a handle:
     *   body()            → the body element to render into
     *   setTitle(t, sub)  → update head text
     *   setStep(n)        → highlight step n in the strip (1-based)
     *   setButtons({...}) → back/next/finish visibility, label, disabled
     *   setSearch(fn)     → show the search box; fn(query) on input
     *   loading(text) / empty(title, hint) / error(msg) → stock body states
     *   close()           → dismiss
     *
     * opts: { title, subtitle, steps: [labels], confirmLabel, onCancel }
     */
    picker: function (opts) {
      opts = opts || {};
      var root = buildPicker();
      var q = function (sel) { return root.querySelector(sel); };

      var head = q('[data-role="title"]');
      var sub = q('[data-role="subtitle"]');
      var stepStrip = q('[data-role="steps"]');
      var tools = q('[data-role="tools"]');
      var search = q('[data-role="search"]');
      var searchBtn = q('[data-role="search-btn"]');
      var body = q('[data-role="body"]');
      var backBtn = q('[data-role="back"]');
      var nextBtn = q('[data-role="next"]');
      var finishBtn = q('[data-role="finish"]');

      var settled = false;
      function close() {
        if (settled) return;
        settled = true;
        root.hidden = true;
        document.removeEventListener('keydown', onKeydown);
        if (typeof opts.onCancel === 'function') opts.onCancel();
      }
      // Escape closes, matching the confirm dialog's native <dialog> behaviour.
      function onKeydown(e) { if (e.key === 'Escape') close(); }

      head.textContent = opts.title || '';
      sub.textContent = opts.subtitle || '';
      sub.hidden = !opts.subtitle;

      stepStrip.innerHTML = '';
      var steps = opts.steps || [];
      stepStrip.hidden = steps.length === 0;
      steps.forEach(function (label, i) {
        var li = document.createElement('li');
        li.setAttribute('data-step', String(i + 1));
        li.textContent = label;
        if (i === 0) li.className = 'is-active';
        stepStrip.appendChild(li);
      });

      tools.hidden = true;
      search.value = '';
      searchBtn.hidden = true;
      body.innerHTML = '';
      // The shell is reused across opens, so clear the previous caller's
      // handlers — otherwise a stale onClick would fire for the new flow.
      backBtn.hidden = true;
      backBtn.onclick = null;
      finishBtn.hidden = true;
      finishBtn.onclick = null;
      nextBtn.hidden = false;
      nextBtn.disabled = true;
      nextBtn.onclick = null;
      nextBtn.textContent = opts.confirmLabel || 'Tiếp';

      root.querySelectorAll('[data-picker-close]').forEach(function (elm) {
        elm.onclick = close;
      });
      document.addEventListener('keydown', onKeydown);
      root.hidden = false;

      function stockState(cls, html) {
        body.innerHTML = '<div class="' + cls + '">' + html + '</div>';
      }

      return {
        root: root,
        body: function () { return body; },
        close: close,
        setTitle: function (title, subtitle) {
          head.textContent = title || '';
          sub.textContent = subtitle || '';
          sub.hidden = !subtitle;
        },
        setStep: function (n) {
          Array.prototype.forEach.call(stepStrip.children, function (li, i) {
            li.className = i + 1 === n ? 'is-active' : (i + 1 < n ? 'is-done' : '');
          });
        },

        /** Relabels one step; the attach wizard's last step is role or preview. */
        setStepLabel: function (n, label) {
          var li = stepStrip.children[n - 1];
          if (li) li.textContent = label;
        },
        /**
         * Shows the search box. `mode` decides when onQuery fires:
         *   'live'   → on every keystroke (client-side filtering of data
         *              already in memory)
         *   'submit' → only on Enter or the Tìm button (server-side search;
         *              firing per keystroke would hit the API on every letter)
         * Defaults to 'live'. Enter never bubbles out to submit a page form.
         */
        setSearch: function (onQuery, placeholder, mode) {
          tools.hidden = typeof onQuery !== 'function';
          if (tools.hidden) return;
          search.placeholder = placeholder || 'Tìm…';
          var submitMode = mode === 'submit';
          searchBtn.hidden = !submitMode;
          var fire = function () { onQuery(search.value || ''); };
          search.oninput = submitMode ? null : fire;
          searchBtn.onclick = submitMode ? fire : null;
          search.onkeydown = function (e) {
            if (e.key !== 'Enter') return;
            e.preventDefault();
            if (submitMode) fire();
          };
        },
        /**
         * Updates footer buttons. Each key is optional and only the properties
         * present are applied — callers routinely call this with just
         * {next:{disabled:…}} to re-sync state, and that must not wipe the
         * onClick handler registered when the picker was opened.
         */
        setButtons: function (cfg) {
          applyButton(backBtn, (cfg || {}).back);
          applyButton(nextBtn, (cfg || {}).next);
          applyButton(finishBtn, (cfg || {}).finish);
        },

        /** Toggles the search box between steps without re-registering onQuery. */
        showSearch: function (visible) {
          tools.hidden = !visible;
        },
        loading: function (text) {
          stockState('ulp-picker-loading', escapeHtml(text || 'Đang tải…'));
        },
        empty: function (title, hint) {
          stockState('ulp-picker-empty',
            '<strong>' + escapeHtml(title || 'Không có dữ liệu') + '</strong>' +
            (hint ? '<p>' + escapeHtml(hint) + '</p>' : ''));
        },
        error: function (message) {
          stockState('ulp-picker-error', escapeHtml(message || 'Có lỗi xảy ra'));
        }
      };
    }
  };

  /** Applies only the button properties the caller actually supplied. */
  function applyButton(btn, cfg) {
    if (!cfg) return;
    if (typeof cfg.hidden === 'boolean') btn.hidden = cfg.hidden;
    if (typeof cfg.disabled === 'boolean') btn.disabled = cfg.disabled;
    if (cfg.label) btn.textContent = cfg.label;
    if (typeof cfg.onClick === 'function') btn.onclick = cfg.onClick;
  }

  /** Escapes text bound into the picker's stock states. */
  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  /** Builds the picker shell once and reuses it across opens. */
  function buildPicker() {
    var root = document.getElementById('ulpPickerModal');
    if (root) return root;
    root = document.createElement('div');
    root.id = 'ulpPickerModal';
    root.className = 'ulp-picker-modal';
    root.hidden = true;
    root.innerHTML =
      '<div class="ulp-picker-backdrop" data-picker-close></div>' +
      '<div class="ulp-picker-dialog" role="dialog" aria-modal="true" aria-labelledby="ulpPickerTitle">' +
      '  <div class="ulp-picker-head">' +
      '    <div>' +
      '      <h3 id="ulpPickerTitle" data-role="title"></h3>' +
      '      <p class="ulp-picker-asset" data-role="subtitle"></p>' +
      '    </div>' +
      '    <button type="button" class="btn-ghost btn-sm" data-picker-close aria-label="Đóng">Đóng</button>' +
      '  </div>' +
      '  <ol class="ulp-picker-steps" data-role="steps" aria-label="Các bước"></ol>' +
      '  <div class="ulp-picker-tools" data-role="tools" hidden>' +
      '    <input type="search" data-role="search" aria-label="Tìm" autocomplete="off"/>' +
      '    <button type="button" class="btn-ghost btn-sm" data-role="search-btn" hidden>Tìm</button>' +
      '  </div>' +
      '  <div class="ulp-picker-body" data-role="body"></div>' +
      '  <div class="ulp-picker-foot">' +
      '    <button type="button" class="btn-ghost" data-role="back" hidden>Quay lại</button>' +
      '    <div class="ulp-picker-foot-spacer"></div>' +
      '    <button type="button" class="btn-ghost" data-picker-close>Huỷ</button>' +
      '    <button type="button" class="btn-primary" data-role="next" disabled>Tiếp</button>' +
      '    <button type="button" class="btn-primary" data-role="finish" hidden disabled>Xong</button>' +
      '  </div>' +
      '</div>';
    document.body.appendChild(root);
    return root;
  }

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

  // Toast carrying one inline button, e.g. "Hoàn tác" after an unshare.
  //
  // SECURITY: iziToast injects both `message` and the button markup as HTML, it
  // does not escape either. `opts.label` is interpolated straight into a
  // <button>, so pass ONLY constant strings from IConstant or literals — never a
  // class name or anything else the user controls. Callers must escape `msg`
  // themselves before passing it here.
  function showActionToast(msg, opts) {
    if (!msg) return;
    var o = opts || {};
    if (typeof window.iziToast === 'undefined') {
      console.log('[Toast action]', msg);
      return;
    }
    window.iziToast.show({
      message: msg,
      position: 'topRight',
      timeout: o.timeout || 6000,
      progressBar: true,
      close: true,
      transitionIn: 'fadeInLeft',
      transitionOut: 'fadeOutRight',
      buttons: [[
        '<button type="button">' + (o.label || 'OK') + '</button>',
        function (instance, toast) {
          instance.hide({ transitionOut: 'fadeOutRight' }, toast);
          if (typeof o.onAction === 'function') o.onAction();
        }
      ]]
    });
  }

  window.UlpToast = {
    success: function (msg, title) { showToast('success', msg, title); },
    error:   function (msg, title) { showToast('error', msg, title); },
    warning: function (msg, title) { showToast('warning', msg, title); },
    info:    function (msg, title) { showToast('info', msg, title); },
    action:  function (msg, opts) { showActionToast(msg, opts); }
  };

})();
