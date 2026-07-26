/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Admin AI Provider Settings

   Three interactions live here, all against /admin/settings/ai:
     • reveal / re-mask an API key (GET  {id}/key)
     • copy an API key to the clipboard (same endpoint)
     • test one provider's connection (POST {id}/test)

   The API keys are deliberately absent from the page source — every reveal is
   a fresh authenticated request, and re-masking drops the value from the DOM.

   CSRF token comes from the <meta> tags in fragments/head.html.
   All user feedback goes through window.UlpToast (defined in app.js).
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  var BASE = '/admin/settings/ai';

  function readMeta(name) {
    var el = document.querySelector('meta[name="' + name + '"]');
    return el ? el.getAttribute('content') : null;
  }

  function toastError(message) {
    if (window.UlpToast) {
      window.UlpToast.error(message);
    } else {
      console.error(message);
    }
  }

  function toastSuccess(message) {
    if (window.UlpToast) window.UlpToast.success(message);
  }

  document.addEventListener('DOMContentLoaded', function () {
    var liveRegion = document.getElementById('aiActionResult');

    /** Mirrors a toast into the ARIA live region; clear-then-set retriggers SR. */
    function announce(message) {
      if (!liveRegion) return;
      liveRegion.textContent = '';
      setTimeout(function () { liveRegion.textContent = message; }, 50);
    }

    /**
     * Unwraps a JSON endpoint response.
     * Non-2xx means transport/auth failure; an HTML body means the session
     * expired and Spring redirected to /login instead of answering.
     */
    function parseJson(response) {
      if (!response.ok) {
        return response.text().then(function (text) {
          throw new Error('HTTP ' + response.status + ': ' + (text || 'no body'));
        });
      }
      var ct = response.headers.get('Content-Type') || '';
      if (ct.indexOf('application/json') === -1) {
        throw new Error('Phản hồi không phải JSON (phiên đăng nhập có thể đã hết hạn)');
      }
      return response.json();
    }

    /** Fetches the plain API key of one provider. Resolves with the key string. */
    function fetchKey(id) {
      return fetch(BASE + '/' + id + '/key', {
        headers: { 'Accept': 'application/json' }
      })
        .then(parseJson)
        .then(function (json) {
          if (!json.ok) throw new Error(json.error || 'Không lấy được API key');
          return json.apiKey || '';
        });
    }

    // ── Reveal / re-mask ────────────────────────────────────────────────
    document.querySelectorAll('.js-ai-reveal').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var id = btn.getAttribute('data-id');
        var cell = document.getElementById('aiKey-' + id);
        if (!cell) return;

        var revealed = btn.getAttribute('aria-pressed') === 'true';
        if (revealed) {
          // Drop the plain value from the DOM again.
          cell.textContent = cell.getAttribute('data-masked');
          btn.setAttribute('aria-pressed', 'false');
          announce('Đã ẩn API key');
          return;
        }

        btn.disabled = true;
        fetchKey(id)
          .then(function (key) {
            cell.textContent = key;
            btn.setAttribute('aria-pressed', 'true');
            announce('Đã hiện API key');
          })
          .catch(function (err) {
            toastError(err.message || 'Không lấy được API key');
            announce('Lỗi khi hiện API key');
          })
          .finally(function () { btn.disabled = false; });
      });
    });

    // ── Copy to clipboard ───────────────────────────────────────────────
    document.querySelectorAll('.js-ai-copy').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var id = btn.getAttribute('data-id');
        btn.disabled = true;
        fetchKey(id)
          .then(function (key) {
            if (!navigator.clipboard) {
              // Clipboard API needs a secure context (https or localhost).
              throw new Error('Trình duyệt không cho phép sao chép ở kết nối này');
            }
            return navigator.clipboard.writeText(key);
          })
          .then(function () {
            toastSuccess('Đã sao chép API key');
            announce('Đã sao chép API key');
          })
          .catch(function (err) {
            toastError(err.message || 'Không sao chép được API key');
            announce('Lỗi khi sao chép API key');
          })
          .finally(function () { btn.disabled = false; });
      });
    });

    // ── Test connection ─────────────────────────────────────────────────
    document.querySelectorAll('.js-ai-test').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var id = btn.getAttribute('data-id');
        var name = btn.getAttribute('data-name') || 'provider';

        var csrfToken = readMeta('_csrf');
        var csrfHeader = readMeta('_csrf_header');
        var headers = { 'Accept': 'application/json' };
        if (csrfHeader && csrfToken) headers[csrfHeader] = csrfToken;

        btn.disabled = true;
        btn.classList.add('is-loading');
        announce('Đang kiểm tra kết nối ' + name);

        fetch(BASE + '/' + id + '/test', { method: 'POST', headers: headers })
          .then(parseJson)
          .then(function (json) {
            if (json.ok) {
              toastSuccess('Kết nối tới ' + name + ' thành công');
              announce('Kết nối tới ' + name + ' thành công');
            } else {
              var err = json.error || 'Kết nối thất bại';
              toastError(name + ': ' + err);
              announce('Lỗi: ' + name + ': ' + err);
            }
          })
          .catch(function (err) {
            var msg = name + ': ' + (err.message || 'Không xác định');
            toastError(msg);
            announce('Lỗi: ' + msg);
          })
          .finally(function () {
            btn.disabled = false;
            btn.classList.remove('is-loading');
          });
      });
    });

    // ── Delete confirmation ─────────────────────────────────────────────
    var modal = document.getElementById('aiDeleteModal');
    var deleteForm = document.getElementById('aiDeleteForm');
    var deleteName = document.getElementById('aiDeleteName');
    var deleteCancel = document.getElementById('aiDeleteCancel');
    var lastFocused = null;

    function closeModal() {
      if (!modal) return;
      modal.hidden = true;
      if (lastFocused) lastFocused.focus();
    }

    if (modal && deleteForm) {
      document.querySelectorAll('.js-ai-delete').forEach(function (btn) {
        btn.addEventListener('click', function () {
          lastFocused = btn;
          deleteForm.setAttribute('action', BASE + '/' + btn.getAttribute('data-id') + '/delete');
          if (deleteName) deleteName.textContent = btn.getAttribute('data-name') || '';
          modal.hidden = false;
          if (deleteCancel) deleteCancel.focus();
        });
      });

      if (deleteCancel) deleteCancel.addEventListener('click', closeModal);

      // Click on the backdrop (not the box) dismisses.
      modal.addEventListener('click', function (e) {
        if (e.target === modal) closeModal();
      });

      document.addEventListener('keydown', function (e) {
        if (modal.hidden) return;
        if (e.key === 'Escape') {
          closeModal();
          return;
        }
        if (e.key !== 'Tab') return;

        // Trap focus inside the dialog while it is open.
        var focusable = modal.querySelectorAll('button, [href], input, select, textarea');
        if (!focusable.length) return;
        var first = focusable[0];
        var last = focusable[focusable.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      });
    }
  });
})();
