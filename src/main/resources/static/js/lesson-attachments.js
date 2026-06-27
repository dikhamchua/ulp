/* ═══════════════════════════════════════════════════════════════════════
   ULP — Lesson attachments uploader (ULP-4.0c)
   Wires the file picker on the lesson edit page to the upload + delete API.
   Surfaces toasts via window.UlpToast — no inline alerts, no native alert().

   Page markup: templates/classes/lesson-form.html (section #lessonAttachmentsCard)
   ════════════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  function el(id) { return document.getElementById(id); }

  function csrfPair() {
    var t = document.querySelector('meta[name="_csrf"]');
    var h = document.querySelector('meta[name="_csrf_header"]');
    if (!t || !h || !t.content || !h.content) return null;
    return { header: h.content, token: t.content };
  }

  function toast(kind, message) {
    if (window.UlpToast && typeof window.UlpToast[kind] === 'function') {
      window.UlpToast[kind](message);
    }
  }

  function formatSize(bytes) {
    var n = Number(bytes);
    if (!isFinite(n) || n < 0) return '—';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / (1024 * 1024)).toFixed(1) + ' MB';
  }

  function showProgress(card, percent) {
    var box = card.querySelector('#lessonAttachProgress');
    var bar = box ? box.querySelector('.lesson-attach-progress-bar') : null;
    if (!box || !bar) return;
    box.hidden = false;
    bar.style.width = Math.max(0, Math.min(100, percent)) + '%';
  }

  function hideProgress(card) {
    var box = card.querySelector('#lessonAttachProgress');
    var bar = box ? box.querySelector('.lesson-attach-progress-bar') : null;
    if (!box) return;
    box.hidden = true;
    if (bar) bar.style.width = '0%';
  }

  function refreshEmptyState(card) {
    var list = card.querySelector('#lessonAttachList');
    var empty = card.querySelector('#lessonAttachEmpty');
    if (!list) return;
    var count = list.querySelectorAll('.lesson-attach-item').length;
    list.hidden = count === 0;
    if (empty) empty.style.display = count === 0 ? '' : 'none';
  }

  /**
   * Builds a <li> for a freshly-uploaded attachment so the DOM mirrors the
   * Thymeleaf-rendered rows. Keeps the data-* attributes the delete handler
   * relies on.
   */
  function buildRow(card, row) {
    var deleteUrl = card.dataset.uploadUrl + '/' + row.id;
    var li = document.createElement('li');
    li.className = 'lesson-attach-item';
    li.setAttribute('data-attachment-id', row.id);
    li.setAttribute('data-delete-url', deleteUrl);

    var icon = document.createElement('span');
    icon.className = 'lesson-attach-icon';
    icon.setAttribute('aria-hidden', 'true');
    icon.textContent = '📎';

    var name = document.createElement('a');
    name.className = 'lesson-attach-name';
    name.href = row.downloadUrl;
    name.textContent = row.originalFilename;
    name.title = row.originalFilename;

    var size = document.createElement('span');
    size.className = 'lesson-attach-size';
    size.setAttribute('data-size-bytes', row.sizeBytes);
    size.textContent = formatSize(row.sizeBytes);

    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'lesson-attach-del';
    btn.setAttribute('aria-label', 'Xoá tệp đính kèm');
    btn.textContent = '🗑';

    li.appendChild(icon);
    li.appendChild(name);
    li.appendChild(size);
    li.appendChild(btn);
    return li;
  }

  function uploadFile(card, file) {
    var csrf = csrfPair();
    var xhr = new XMLHttpRequest();
    xhr.open('POST', card.dataset.uploadUrl, true);
    if (csrf) xhr.setRequestHeader(csrf.header, csrf.token);
    xhr.upload.addEventListener('progress', function (evt) {
      if (evt.lengthComputable) {
        showProgress(card, (evt.loaded / evt.total) * 100);
      }
    });
    xhr.addEventListener('load', function () {
      hideProgress(card);
      var body = null;
      try { body = JSON.parse(xhr.responseText || 'null'); } catch (e) { body = null; }
      if (xhr.status >= 200 && xhr.status < 300 && body && body.id) {
        var list = card.querySelector('#lessonAttachList');
        if (list) list.appendChild(buildRow(card, body));
        refreshEmptyState(card);
        toast('success', 'Đã tải lên tệp đính kèm');
      } else {
        var msg = (body && body.message) || 'Tải lên thất bại, vui lòng thử lại.';
        toast('error', msg);
      }
    });
    xhr.addEventListener('error', function () {
      hideProgress(card);
      toast('error', 'Không kết nối được tới server.');
    });
    var form = new FormData();
    form.append('file', file);
    showProgress(card, 0);
    xhr.send(form);
  }

  function deleteAttachment(card, item) {
    var url = item.getAttribute('data-delete-url');
    if (!url) return;
    if (!window.confirm('Xoá tệp đính kèm này?')) return;
    var csrf = csrfPair();
    var headers = { 'Accept': 'application/json' };
    if (csrf) headers[csrf.header] = csrf.token;
    fetch(url, {
      method: 'DELETE',
      headers: headers,
      credentials: 'same-origin'
    }).then(function (res) {
      return res.json()
        .catch(function () { return { ok: false, message: 'Phản hồi không hợp lệ' }; })
        .then(function (json) { return { status: res.status, ok: res.ok && json.ok === true, message: json.message }; });
    }).then(function (result) {
      if (result.ok) {
        item.remove();
        refreshEmptyState(card);
        toast('success', 'Đã xoá tệp đính kèm');
      } else {
        toast('error', result.message || 'Xoá thất bại, vui lòng thử lại.');
      }
    }).catch(function () {
      toast('error', 'Không kết nối được tới server.');
    });
  }

  function init() {
    var card = el('lessonAttachmentsCard');
    if (!card) return;
    // Render server-rendered sizes (raw byte counts) into KB/MB on first paint.
    var sizes = card.querySelectorAll('.lesson-attach-size[data-size-bytes]');
    sizes.forEach(function (span) {
      span.textContent = formatSize(span.getAttribute('data-size-bytes'));
    });

    var input = el('lessonAttachInput');
    if (input) {
      input.addEventListener('change', function () {
        var file = input.files && input.files[0];
        if (file) uploadFile(card, file);
        input.value = '';
      });
    }

    card.addEventListener('click', function (evt) {
      var btn = evt.target.closest && evt.target.closest('.lesson-attach-del');
      if (!btn) return;
      var item = btn.closest('.lesson-attach-item');
      if (item) deleteAttachment(card, item);
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
