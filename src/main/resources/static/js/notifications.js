/**
 * notifications.js — client-side behavior for the notification inbox
 * and the app-wide bell badge in the header.
 *
 * Responsibilities:
 *  1. Drain the #flash-data element and fire UlpToast on page load.
 *  2. Poll /my/notifications/unread-count every 60 s to keep the header
 *     bell badge current (best-effort: silently skips if the user is
 *     not on an authenticated page).
 *
 * Dependencies: app.js (UlpToast) must be loaded before this file.
 */
(function () {
  'use strict';

  /* ── Flash toast drain ──────────────────────────────────────────── */

  /**
   * Reads data attributes from the hidden #flash-data div and fires
   * the appropriate UlpToast variant. Runs once on DOMContentLoaded.
   */
  function drainFlash() {
    var el = document.getElementById('flash-data');
    if (!el) return;
    var ok = el.getAttribute('data-flash-success');
    var err = el.getAttribute('data-flash-error');
    if (ok && ok !== 'null' && ok.trim()) {
      window.UlpToast && window.UlpToast.success(ok.trim());
    }
    if (err && err !== 'null' && err.trim()) {
      window.UlpToast && window.UlpToast.error(err.trim());
    }
  }

  /* ── Bell badge polling ─────────────────────────────────────────── */

  var POLL_INTERVAL_MS = 60000; // 60 s

  /**
   * Updates every [data-notif-badge] element in the DOM with the
   * unread count returned by the server.  Shows/hides the badge span
   * using the is-hidden CSS class (same convention as messaging.js).
   *
   * @param {number} count  The current unread notification count.
   */
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

  /**
   * Fetches the unread count from the server and calls applyBadge.
   * Silently swallows network / auth errors (best-effort update).
   */
  function pollUnreadCount() {
    fetch('/my/notifications/unread-count', {
      credentials: 'same-origin',
      headers: { 'X-Requested-With': 'XMLHttpRequest' }
    })
      .then(function (res) {
        // 401/403 means session expired — stop polling gracefully.
        if (res.status === 401 || res.status === 403) return null;
        return res.json();
      })
      .then(function (data) {
        if (data && typeof data.count === 'number') {
          applyBadge(data.count);
        }
      })
      .catch(function () {
        // Network failure: badge stays at last SSR value.
      });
  }

  /**
   * Starts the polling loop. Only activates when the page contains
   * at least one [data-notif-link] anchor (i.e. the app-header is
   * present), so the script is a no-op on public/auth pages.
   */
  function startPolling() {
    if (!document.querySelector('[data-notif-link]')) return;
    // First poll after 60 s; the SSR badge is already accurate on load.
    setTimeout(function tick() {
      pollUnreadCount();
      setTimeout(tick, POLL_INTERVAL_MS);
    }, POLL_INTERVAL_MS);
  }

  /* ── Bootstrap ──────────────────────────────────────────────────── */

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () {
      drainFlash();
      startPolling();
    });
  } else {
    drainFlash();
    startPolling();
  }
}());
