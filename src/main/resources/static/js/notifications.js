/**
 * notifications.js — bell badge polling + Facebook-style dropdown panel.
 *
 * Responsibilities:
 *  1. Drain #flash-data into UlpToast when present.
 *  2. Poll /my/notifications/unread-count every 60s for the header badge.
 *  3. Load recent items into the bell dropdown on open.
 *  4. Open a notification via AJAX mark-read, then navigate to its target.
 *
 * Dependencies: app.js (dropdown toggle + UlpToast) loaded before this file.
 */
(function () {
  'use strict';

  var POLL_INTERVAL_MS = 60000;
  var RECENT_URL = '/my/notifications/recent';
  var OPEN_URL = '/my/notifications/';

  /* ── Flash toast drain ──────────────────────────────────────────── */

  function drainFlash() {
    var el = document.getElementById('flash-data');
    if (!el) return;
    var ok = el.getAttribute('data-flash-success');
    var err = el.getAttribute('data-flash-error');
    var info = el.getAttribute('data-flash-info');
    if (ok && ok !== 'null' && ok.trim()) {
      window.UlpToast && window.UlpToast.success(ok.trim());
    }
    if (err && err !== 'null' && err.trim()) {
      window.UlpToast && window.UlpToast.error(err.trim());
    }
    if (info && info !== 'null' && info.trim()) {
      window.UlpToast && window.UlpToast.info
        ? window.UlpToast.info(info.trim())
        : (window.UlpToast && window.UlpToast.success(info.trim()));
    }
  }

  /* ── Badge ──────────────────────────────────────────────────────── */

  function applyBadge(count) {
    var badges = document.querySelectorAll('[data-notif-badge]');
    badges.forEach(function (el) {
      if (count > 0) {
        el.textContent = count > 99 ? '99+' : String(count);
        el.classList.remove('is-hidden');
      } else {
        el.textContent = '0';
        el.classList.add('is-hidden');
      }
    });
  }

  function pollUnreadCount() {
    fetch('/my/notifications/unread-count', {
      credentials: 'same-origin',
      headers: { 'X-Requested-With': 'XMLHttpRequest' }
    })
      .then(function (res) {
        if (res.status === 401 || res.status === 403) return null;
        return res.json();
      })
      .then(function (data) {
        if (data && typeof data.count === 'number') {
          applyBadge(data.count);
        }
      })
      .catch(function () { /* keep last SSR value */ });
  }

  function startPolling() {
    if (!document.querySelector('[data-notif-link]')) return;
    setTimeout(function tick() {
      pollUnreadCount();
      setTimeout(tick, POLL_INTERVAL_MS);
    }, POLL_INTERVAL_MS);
  }

  /* ── CSRF helpers ───────────────────────────────────────────────── */

  function csrfHeader() {
    var meta = document.querySelector('meta[name="_csrf"]');
    var headerMeta = document.querySelector('meta[name="_csrf_header"]');
    if (!meta || !headerMeta) return {};
    var headers = { 'X-Requested-With': 'XMLHttpRequest' };
    headers[headerMeta.getAttribute('content')] = meta.getAttribute('content');
    return headers;
  }

  /* ── Dropdown panel ─────────────────────────────────────────────── */

  function escapeHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function renderItems(listEl, items) {
    if (!items || !items.length) {
      listEl.innerHTML =
        '<div class="notif-panel-empty">Bạn chưa có thông báo nào.</div>';
      return;
    }
    var html = items.map(function (n) {
      var unread = !n.isRead;
      return (
        '<button type="button" class="notif-panel-item' +
        (unread ? ' is-unread' : '') +
        '" data-notif-id="' + escapeHtml(n.id) + '"' +
        ' data-notif-href="' + escapeHtml(n.href || '') + '">' +
        '<span class="notif-panel-dot' + (unread ? '' : ' is-read') + '"></span>' +
        '<span class="notif-panel-text">' +
        '<span class="notif-panel-item-title">' + escapeHtml(n.title) + '</span>' +
        '<span class="notif-panel-item-content">' + escapeHtml(n.content) + '</span>' +
        '<span class="notif-panel-item-time">' + escapeHtml(n.createdAt) + '</span>' +
        '</span></button>'
      );
    }).join('');
    listEl.innerHTML = html;
  }

  function loadRecent(listEl) {
    listEl.innerHTML = '<div class="notif-panel-loading">Đang tải…</div>';
    fetch(RECENT_URL, {
      credentials: 'same-origin',
      headers: { 'X-Requested-With': 'XMLHttpRequest' }
    })
      .then(function (res) {
        if (!res.ok) throw new Error('load failed');
        return res.json();
      })
      .then(function (data) {
        renderItems(listEl, data.items || []);
        if (typeof data.count === 'number') applyBadge(data.count);
      })
      .catch(function () {
        listEl.innerHTML =
          '<div class="notif-panel-empty">Không tải được thông báo.</div>';
      });
  }

  function openNotification(id, href) {
    fetch(OPEN_URL + encodeURIComponent(id) + '/open?ajax=1', {
      method: 'POST',
      credentials: 'same-origin',
      headers: csrfHeader()
    })
      .then(function (res) {
        if (!res.ok) throw new Error('open failed');
        return res.json();
      })
      .then(function (data) {
        if (typeof data.count === 'number') applyBadge(data.count);
        var target = data.redirect || href || '/my/notifications';
        window.location.href = target;
      })
      .catch(function () {
        // Fallback: full form POST path via navigation to inbox.
        window.location.href = '/my/notifications';
      });
  }

  function bindPanel() {
    // Capture-phase delegation so we win over other document click closers.
    document.addEventListener('click', function (e) {
      var trigger = e.target.closest('[data-notif-link]');
      var dropdown = document.querySelector('[data-notif-dropdown]');
      if (!dropdown) return;
      var listEl = dropdown.querySelector('[data-notif-panel-list]');
      var panel = dropdown.querySelector('.notif-panel');

      // Click on a notification row inside the open panel.
      var item = e.target.closest('[data-notif-id]');
      if (item && dropdown.contains(item)) {
        e.preventDefault();
        e.stopPropagation();
        openNotification(item.getAttribute('data-notif-id'),
            item.getAttribute('data-notif-href'));
        return;
      }

      // Click the bell trigger — toggle panel.
      if (trigger && dropdown.contains(trigger)) {
        e.preventDefault();
        e.stopPropagation();
        var willOpen = !dropdown.classList.contains('open');
        document.querySelectorAll('.dropdown.open').forEach(function (d) {
          if (d !== dropdown) d.classList.remove('open');
        });
        if (willOpen) {
          dropdown.classList.add('open');
          trigger.setAttribute('aria-expanded', 'true');
          if (listEl) loadRecent(listEl);
        } else {
          dropdown.classList.remove('open');
          trigger.setAttribute('aria-expanded', 'false');
        }
        return;
      }

      // Outside click closes the panel.
      if (dropdown.classList.contains('open')
          && panel && !panel.contains(e.target)
          && !trigger) {
        dropdown.classList.remove('open');
        var t = dropdown.querySelector('[data-notif-link]');
        if (t) t.setAttribute('aria-expanded', 'false');
      }
    }, true);
  }

  /* ── Bootstrap ──────────────────────────────────────────────────── */

  function boot() {
    drainFlash();
    startPolling();
    bindPanel();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
}());
