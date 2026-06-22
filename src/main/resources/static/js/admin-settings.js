/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Admin Email Settings test-send AJAX
   Reads CSRF token from <meta> tags placed in fragments/head.html.
   Uses window.UlpToast (defined in app.js) for success/error notifications.
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  function readMeta(name) {
    var el = document.querySelector('meta[name="' + name + '"]');
    return el ? el.getAttribute('content') : null;
  }

  document.addEventListener('DOMContentLoaded', function () {
    var btn = document.getElementById('sendTestBtn');
    var input = document.getElementById('testRecipient');
    var liveRegion = document.getElementById('testSendResult');
    if (!btn || !input) return;

    function announce(message) {
      // Mirror toast into ARIA live region so screen readers announce result
      // even when focus is elsewhere. Clear-then-set to retrigger SR.
      if (!liveRegion) return;
      liveRegion.textContent = '';
      setTimeout(function () { liveRegion.textContent = message; }, 50);
    }

    btn.addEventListener('click', function () {
      var recipient = (input.value || '').trim();
      if (!recipient) {
        if (window.UlpToast) {
          window.UlpToast.error('Vui lòng nhập email người nhận');
        }
        announce('Lỗi: Vui lòng nhập email người nhận');
        return;
      }

      // CSRF token from <meta name="_csrf"> / <meta name="_csrf_header">
      var csrfToken = readMeta('_csrf');
      var csrfHeader = readMeta('_csrf_header');

      btn.disabled = true;
      var originalLabel = btn.textContent;
      btn.textContent = 'Đang gửi...';

      var headers = {
        'Accept': 'application/json',
        'Content-Type': 'application/x-www-form-urlencoded'
      };
      if (csrfHeader && csrfToken) {
        headers[csrfHeader] = csrfToken;
      }

      fetch('/admin/settings/email/test', {
        method: 'POST',
        headers: headers,
        body: 'testRecipient=' + encodeURIComponent(recipient)
      })
        .then(function (response) {
          // Endpoint contract: 200 + JSON body { ok, error? } even on logical failure.
          // Non-2xx (e.g. 403 CSRF, 500) means transport failure — throw.
          if (!response.ok) {
            return response.text().then(function (text) {
              throw new Error('HTTP ' + response.status + ': ' + (text || 'no body'));
            });
          }
          // Defensive: if session expired Spring may redirect to /login (HTML),
          // not the expected JSON. Detect by Content-Type before parsing.
          var ct = response.headers.get('Content-Type') || '';
          if (ct.indexOf('application/json') === -1) {
            throw new Error('Phản hồi không phải JSON (phiên đăng nhập có thể đã hết hạn)');
          }
          return response.json();
        })
        .then(function (json) {
          if (json.ok) {
            var msg = 'Đã gửi email thử đến ' + recipient;
            if (window.UlpToast) window.UlpToast.success(msg);
            announce(msg);
          } else {
            var err = json.error || 'Gửi thất bại';
            if (window.UlpToast) window.UlpToast.error(err);
            announce('Lỗi: ' + err);
          }
        })
        .catch(function (err) {
          var msg = 'Lỗi: ' + (err.message || 'Không xác định');
          if (window.UlpToast) {
            window.UlpToast.error(msg);
          } else {
            console.error(err);
          }
          announce(msg);
        })
        .finally(function () {
          btn.disabled = false;
          btn.textContent = originalLabel;
        });
    });
  });
})();
