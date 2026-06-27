/* ═══════════════════════════════════════════════════════════════════════
   ULP — Lesson attachments uploader (ULP-4.0c)
   Wires the drag-and-drop zone on the lesson edit page to the upload +
   delete API. Visual + behavioral style mirrors `import-excel.js` so the
   two uploaders feel like the same product.
   Toasts via window.UlpToast — no inline alerts, no native alert().

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

  /** Maps a filename's extension to the CSS class used by the colored ext badge. */
  function extClass(filename) {
    var ext = (filename || '').toLowerCase().split('.').pop();
    if (ext === 'pdf')                                 return 'ext-pdf';
    if (ext === 'doc' || ext === 'docx')               return 'ext-doc';
    if (ext === 'xls' || ext === 'xlsx')               return 'ext-xls';
    if (ext === 'ppt' || ext === 'pptx')               return 'ext-ppt';
    if (ext === 'zip')                                 return 'ext-zip';
    return '';
  }

  function showProgress(card, percent) {
    var box = card.querySelector('#lessonAttachProgress');
    var bar = box ? box.querySelector('.latt-progress-bar') : null;
    if (!box || !bar) return;
    box.hidden = false;
    bar.style.width = Math.max(0, Math.min(100, percent)) + '%';
  }

  function hideProgress(card) {
    var box = card.querySelector('#lessonAttachProgress');
    var bar = box ? box.querySelector('.latt-progress-bar') : null;
    if (!box) return;
    box.hidden = true;
    if (bar) bar.style.width = '0%';
  }

  function refreshEmptyState(card) {
    var list = card.querySelector('#lessonAttachList');
    var empty = card.querySelector('#lessonAttachEmpty');
    if (!list) return;
    var count = list.querySelectorAll('.latt-item').length;
    list.hidden = count === 0;
    if (empty) empty.style.display = count === 0 ? '' : 'none';
  }

  /**
   * Builds a <li> matching the Thymeleaf-rendered row markup. Keeps the
   * data-* attributes the delete handler relies on, plus the ext badge
   * driven from the original filename.
   */
  function buildRow(card, row) {
    var deleteUrl = card.dataset.uploadUrl + '/' + row.id;
    var li = document.createElement('li');
    li.className = 'latt-item';
    li.setAttribute('data-attachment-id', row.id);
    li.setAttribute('data-delete-url', deleteUrl);

    var ext = (row.originalFilename || '').split('.').pop();
    var badge = document.createElement('span');
    badge.className = 'latt-ext ' + extClass(row.originalFilename);
    badge.setAttribute('aria-hidden', 'true');
    badge.textContent = ext;

    var meta = document.createElement('span');
    meta.className = 'latt-meta';

    var name = document.createElement('a');
    name.className = 'latt-name';
    name.href = row.downloadUrl;
    name.textContent = row.originalFilename;
    name.title = row.originalFilename;

    var sub = document.createElement('span');
    sub.className = 'latt-sub';
    sub.setAttribute('data-size-bytes', row.sizeBytes);
    sub.textContent = formatSize(row.sizeBytes);

    meta.appendChild(name);
    meta.appendChild(sub);

    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'latt-del';
    btn.setAttribute('aria-label', 'Xoá tệp đính kèm');
    // Mirror the inline trash SVG used in the server-rendered row so the
    // dynamic and static rows visually match.
    btn.innerHTML =
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
      'stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
      '<path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>' +
      '<path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>';

    li.appendChild(badge);
    li.appendChild(meta);
    li.appendChild(btn);
    return li;
  }

  function setFileLabel(card, name) {
    var label = card.querySelector('#lessonAttachFileLabel');
    if (label) label.textContent = name || 'Chưa chọn tệp';
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
        setFileLabel(card, 'Chưa chọn tệp');
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
    setFileLabel(card, 'Đang tải lên: ' + file.name);
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

  /**
   * Wires the drop area: click forwards to the hidden file input, drag-drop
   * forwards the first dropped file to upload. Keyboard activation via
   * Enter/Space mirrors the import-excel drop area's a11y pattern.
   */
  function bindDropArea(card) {
    var drop = card.querySelector('#lessonAttachDrop');
    var input = card.querySelector('#lessonAttachInput');
    if (!drop || !input) return;

    drop.addEventListener('click', function () { input.click(); });
    drop.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        input.click();
      }
    });

    ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(function (ev) {
      drop.addEventListener(ev, function (e) {
        e.preventDefault();
        e.stopPropagation();
        drop.classList.toggle('is-dragover',
            ev === 'dragenter' || ev === 'dragover');
      });
    });
    drop.addEventListener('drop', function (e) {
      var files = e.dataTransfer && e.dataTransfer.files;
      if (files && files.length) {
        uploadFile(card, files[0]);
      }
    });

    input.addEventListener('change', function () {
      var file = input.files && input.files[0];
      if (file) uploadFile(card, file);
      input.value = '';
    });
  }

  function init() {
    var card = el('lessonAttachmentsCard');
    if (!card) return;
    // Render server-rendered sizes (raw byte counts) into KB/MB on first paint.
    var sizes = card.querySelectorAll('.latt-sub[data-size-bytes]');
    sizes.forEach(function (span) {
      span.textContent = formatSize(span.getAttribute('data-size-bytes'));
    });

    bindDropArea(card);

    card.addEventListener('click', function (evt) {
      var btn = evt.target.closest && evt.target.closest('.latt-del');
      if (!btn) return;
      var item = btn.closest('.latt-item');
      if (item) deleteAttachment(card, item);
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
