/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Direct messaging client (Epic #13, ULP-8.3 + ULP-8.4)
   ----------------------------------------------------------------------------
   Loaded app-wide from fragments/app-header.html so the header badge updates
   live over STOMP on every page. On the messaging page it also wires the
   composer (fetch send + form fallback) and appends realtime messages to the
   open thread. All wiring is guarded so pages without a chat UI just get the
   live badge.
   ══════════════════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  // Prevent double-bind when the script is included more than once (duplicate
  // submit handlers caused concurrent POSTs and MySQL deadlocks on send).
  if (window.__ulpMessagingBooted) return;
  window.__ulpMessagingBooted = true;

  // ── CSRF + small DOM helpers ───────────────────────────────────────────
  function meta(name) {
    var el = document.querySelector('meta[name="' + name + '"]');
    return el ? el.getAttribute('content') : null;
  }

  // All unread badges on the page (header chat icon + class-sidebar item).
  var badgeEls = document.querySelectorAll('[data-msg-badge]');

  // Reflect a total unread count in every badge (hidden at 0, 99+ cap).
  function setBadge(count) {
    var n = typeof count === 'number' ? count : parseInt(count, 10) || 0;
    badgeEls.forEach(function (el) {
      if (n > 0) {
        el.textContent = n > 99 ? '99+' : String(n);
        el.classList.remove('is-hidden');
      } else {
        el.textContent = '0';
        el.classList.add('is-hidden');
      }
    });
  }

  // ── Thread wiring (only on the messaging page with a conversation open) ──
  var thread = document.querySelector('[data-msg-thread]');
  var openConvId = thread ? thread.getAttribute('data-conv-id') : null;

  function scrollThreadToBottom() {
    var list = thread && thread.querySelector('[data-msg-list]');
    if (list) list.scrollTop = list.scrollHeight;
  }

  // Builds one message bubble element. mine=true right-aligns it.
  function bubble(body, mine) {
    var wrap = document.createElement('div');
    wrap.className = 'msg-bubble-row ' + (mine ? 'is-mine' : 'is-peer');
    var b = document.createElement('div');
    b.className = 'msg-bubble';
    b.textContent = body;
    wrap.appendChild(b);
    return wrap;
  }

  function appendMessage(body, mine) {
    var list = thread && thread.querySelector('[data-msg-list]');
    if (!list) return;
    var empty = list.querySelector('[data-msg-empty]');
    if (empty) empty.remove();
    list.appendChild(bubble(body, mine));
    scrollThreadToBottom();
  }

  // ── Composer: fetch send with graceful form fallback ─────────────────────
  var composer = document.querySelector('[data-msg-composer]');
  var input = composer ? composer.querySelector('[data-msg-input]') : null;
  var errorEl = composer ? composer.querySelector('[data-msg-error]') : null;

  function showError(msg) {
    if (!errorEl) return;
    errorEl.textContent = msg || 'Không gửi được tin nhắn. Vui lòng thử lại.';
    errorEl.hidden = false;
  }

  function clearError() {
    if (errorEl) { errorEl.textContent = ''; errorEl.hidden = true; }
  }

  if (composer && input && openConvId) {
    var sending = false;
    composer.addEventListener('submit', function (e) {
      // Progressive enhancement: intercept only when fetch is usable.
      if (typeof window.fetch !== 'function') return; // fall back to native POST
      e.preventDefault();
      if (sending) return; // ignore double-submit while in flight
      var body = (input.value || '').trim();
      clearError();
      if (!body) return;
      if (body.length > 2000) { showError('Nội dung tối đa 2000 ký tự'); return; }

      var headers = {
        'X-Requested-With': 'XMLHttpRequest',
        'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'
      };
      var token = meta('_csrf'), header = meta('_csrf_header');
      if (token && header) headers[header] = token;

      var sendBtn = composer.querySelector('[data-msg-send]');
      if (sendBtn) sendBtn.disabled = true;
      sending = true;

      fetch(composer.getAttribute('action'), {
        method: 'POST',
        credentials: 'same-origin',
        headers: headers,
        body: 'body=' + encodeURIComponent(body)
      }).then(function (res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json().catch(function () { return {}; });
      }).then(function () {
        appendMessage(body, true);
        input.value = '';
        clearError();
      }).catch(function () {
        showError('Không gửi được tin nhắn. Vui lòng thử lại.');
      }).then(function () {
        sending = false;
        if (sendBtn) sendBtn.disabled = false;
      });
    });
  }

  // ── STOMP connection (present on every authenticated page) ───────────────
  function connectStomp() {
    if (typeof window.SockJS === 'undefined' || typeof window.Stomp === 'undefined') return;
    var socket = new window.SockJS('/ws');
    var client = window.Stomp.over(socket);
    client.debug = null; // silence frame logging in the console
    client.connect({}, function () {
      client.subscribe('/user/queue/messages', function (frame) {
        var payload;
        try { payload = JSON.parse(frame.body); } catch (err) { return; }
        // Always refresh the header badge from the authoritative server total.
        if (typeof payload.unreadTotal !== 'undefined') setBadge(payload.unreadTotal);
        // If the pushed message belongs to the open thread, append + mark read.
        if (openConvId && String(payload.convId) === String(openConvId)) {
          appendMessage(payload.snippet, false);
          // Opening/being-in the thread means it is read; drop the badge again
          // by asking the server for the fresh total (the message we just
          // appended is unread server-side until the next open).
          fetch('/my/messages/unread-count', { headers: { 'X-Requested-With': 'XMLHttpRequest' } })
            .then(function (r) { return r.json(); })
            .then(function (d) { if (d && typeof d.count !== 'undefined') setBadge(d.count); })
            .catch(function () { /* badge stays as pushed total */ });
        }
      });
    }, function () {
      // Connection error/drop: the server-rendered badge remains the source of
      // truth; a page reload re-establishes the count. No noisy retry loop.
    });
  }

  // Keep the opened thread scrolled to the newest message on load.
  if (thread) scrollThreadToBottom();

  // ── Compose: client-side filter over the full eligible roster ───────────
  // Server renders every peer the caller may message; this only shows/hides
  // rows as the user types (no network round-trip).
  (function bindComposeFilter() {
    var root = document.querySelector('[data-msg-compose]');
    if (!root) return;
    var input = root.querySelector('[data-msg-compose-q]');
    var empty = root.querySelector('[data-msg-compose-empty]');
    var rows = root.querySelectorAll('.msg-recipient[data-search]');
    if (!input || !rows.length) return;

    function applyFilter() {
      var q = (input.value || '').trim().toLowerCase();
      var visible = 0;
      rows.forEach(function (row) {
        var hay = (row.getAttribute('data-search') || '').toLowerCase();
        var show = !q || hay.indexOf(q) !== -1;
        row.style.display = show ? '' : 'none';
        if (show) visible += 1;
      });
      if (empty) {
        // Only show "no match" when the user typed something and nothing matched.
        empty.style.display = (q && visible === 0) ? '' : 'none';
      }
    }

    input.addEventListener('input', applyFilter);
    input.addEventListener('search', applyFilter); // clear (x) on type=search
  })();

  connectStomp();
})();
