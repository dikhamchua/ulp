/* ═══════════════════════════════════════════════════════════════════════
   ULP — Lecturer progress drill-down (lecturer-student-progress)
   Row click → fetch per-student lesson breakdown JSON → render a side panel.
   All content is written via textContent (never innerHTML) so lesson/section
   titles containing markup render as literal text (XSS-safe). Uses
   window.UlpToast (app.js) for error feedback and the CSRF <meta> pattern
   from admin-settings.js.
   ══════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // Vietnamese label per drill-down status; unknown keys fall back to the key.
  var STATUS_LABEL = {
    COMPLETED: 'Hoàn thành',
    IN_PROGRESS: 'Đang học',
    NOT_STARTED: 'Chưa mở'
  };

  function readMeta(name) {
    var el = document.querySelector('meta[name="' + name + '"]');
    return el ? el.getAttribute('content') : null;
  }

  document.addEventListener('DOMContentLoaded', function () {
    var panel = document.getElementById('progressPanel');
    var body = document.getElementById('progressPanelBody');
    var title = document.getElementById('progressPanelTitle');
    var closeBtn = document.getElementById('progressPanelClose');
    var container = document.querySelector('.detail-panel[data-class-id]');
    if (!panel || !body || !container) return;

    var classId = container.getAttribute('data-class-id');
    var selectedRow = null;

    function closePanel() {
      panel.setAttribute('hidden', '');
      panel.setAttribute('aria-hidden', 'true');
      if (selectedRow) {
        selectedRow.classList.remove('is-selected');
        selectedRow = null;
      }
    }

    function openPanel() {
      panel.removeAttribute('hidden');
      panel.setAttribute('aria-hidden', 'false');
    }

    function clearBody() {
      while (body.firstChild) body.removeChild(body.firstChild);
    }

    function renderEmpty(message) {
      clearBody();
      var p = document.createElement('p');
      p.className = 'pg-panel-empty';
      p.textContent = message;
      body.appendChild(p);
    }

    function renderBreakdown(data) {
      clearBody();
      var sections = (data && data.sections) || [];
      if (!sections.length) {
        renderEmpty('Lớp chưa có bài học nào được xuất bản.');
        return;
      }
      sections.forEach(function (section) {
        var secEl = document.createElement('div');
        secEl.className = 'pg-sec';

        var h = document.createElement('h3');
        h.className = 'pg-sec-title';
        h.textContent = section.title || '';
        secEl.appendChild(h);

        (section.lessons || []).forEach(function (lesson) {
          var row = document.createElement('div');
          row.className = 'pg-lesson';

          var name = document.createElement('span');
          name.textContent = lesson.title || '';
          row.appendChild(name);

          var badge = document.createElement('span');
          var st = lesson.status || 'NOT_STARTED';
          badge.className = 'pg-badge pg-badge-' + st;
          badge.textContent = STATUS_LABEL[st] || st;
          row.appendChild(badge);

          secEl.appendChild(row);
        });
        body.appendChild(secEl);
      });
    }

    function loadStudent(studentId, studentName) {
      title.textContent = studentName ? ('Tiến độ: ' + studentName) : 'Chi tiết tiến độ';
      renderEmpty('Đang tải...');
      openPanel();

      var headers = { 'Accept': 'application/json' };
      var csrfToken = readMeta('_csrf');
      var csrfHeader = readMeta('_csrf_header');
      if (csrfHeader && csrfToken) headers[csrfHeader] = csrfToken;

      fetch('/lecturer/classes/' + classId + '/progress/' + studentId + '/lessons', {
        method: 'GET',
        headers: headers
      })
        .then(function (response) {
          if (!response.ok) {
            return response.json().then(function (json) {
              throw new Error((json && json.message) || ('HTTP ' + response.status));
            }, function () {
              throw new Error('HTTP ' + response.status);
            });
          }
          var ct = response.headers.get('Content-Type') || '';
          if (ct.indexOf('application/json') === -1) {
            throw new Error('Phản hồi không hợp lệ (phiên đăng nhập có thể đã hết hạn)');
          }
          return response.json();
        })
        .then(renderBreakdown)
        .catch(function (err) {
          var msg = err && err.message ? err.message : 'Không tải được tiến độ';
          if (window.UlpToast) window.UlpToast.error(msg);
          renderEmpty(msg);
        });
    }

    container.querySelectorAll('.pg-row').forEach(function (row) {
      row.addEventListener('click', function () {
        var studentId = row.getAttribute('data-student-id');
        if (!studentId) return;
        if (selectedRow) selectedRow.classList.remove('is-selected');
        selectedRow = row;
        row.classList.add('is-selected');
        loadStudent(studentId, row.getAttribute('data-student-name'));
      });
    });

    if (closeBtn) closeBtn.addEventListener('click', closePanel);
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') closePanel();
    });
  });
})();
