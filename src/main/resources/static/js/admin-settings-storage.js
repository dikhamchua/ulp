/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Admin Storage Settings test-connection AJAX
   Mirrors admin-settings.js (email test-send): CSRF meta + UlpToast.
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  function readMeta(name) {
    var el = document.querySelector('meta[name="' + name + '"]');
    return el ? el.getAttribute('content') : null;
  }

  document.addEventListener('DOMContentLoaded', function () {
    var btn = document.getElementById('testStorageBtn');
    var liveRegion = document.getElementById('testStorageResult');
    if (!btn) return;

    function announce(message) {
      if (!liveRegion) return;
      liveRegion.textContent = '';
      setTimeout(function () { liveRegion.textContent = message; }, 50);
    }

    btn.addEventListener('click', function () {
      var csrfToken = readMeta('_csrf');
      var csrfHeader = readMeta('_csrf_header');

      btn.disabled = true;
      var originalLabel = btn.textContent;
      btn.textContent = 'Đang kiểm tra...';

      var headers = {
        'Accept': 'application/json',
        'Content-Type': 'application/x-www-form-urlencoded'
      };
      if (csrfHeader && csrfToken) {
        headers[csrfHeader] = csrfToken;
      }

      fetch('/admin/settings/storage/test', {
        method: 'POST',
        headers: headers,
        body: ''
      })
        .then(function (response) {
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
        })
        .then(function (json) {
          if (json.ok) {
            var msg = 'Kết nối Cloudflare R2 thành công';
            if (window.UlpToast) window.UlpToast.success(msg);
            announce(msg);
          } else {
            var err = json.error || 'Kiểm tra thất bại';
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
