/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Lesson comments panel (ULP-4.6)
   ----------------------------------------------------------------------------
   Loads and mutates the "Thảo luận" panel for the inlined lesson. All user
   content is injected via textContent (never innerHTML) so a comment holding
   <script> renders as literal text. CSRF token from <meta> tags; feedback via
   window.UlpToast. Mutations reload the list to refresh canEdit/canDelete.
   ══════════════════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var MSG_CONTENT_REQUIRED = 'Nội dung không được để trống';

  var panel = document.querySelector('.lesson-comments[data-lesson-id]');
  if (!panel) return;

  var lessonId = panel.getAttribute('data-lesson-id');
  var base = '/api/lessons/' + lessonId + '/comments';
  var listEl = panel.querySelector('[data-role="list"]');
  var statusEl = panel.querySelector('[data-role="status"]');
  var composer = panel.querySelector('[data-role="composer"]');
  var composerInput = panel.querySelector('[data-role="input"]');

  function meta(name) {
    var el = document.querySelector('meta[name="' + name + '"]');
    return el ? el.getAttribute('content') : null;
  }

  function headers(hasBody) {
    var h = { 'Accept': 'application/json' };
    if (hasBody) h['Content-Type'] = 'application/json';
    var token = meta('_csrf'), header = meta('_csrf_header');
    if (token && header) h[header] = token;
    return h;
  }

  function api(method, url, body) {
    return fetch(url, {
      method: method,
      headers: headers(!!body),
      body: body ? JSON.stringify(body) : undefined
    }).then(function (res) {
      return res.json()
        .catch(function () { return { ok: false, message: 'Lỗi máy chủ' }; })
        .then(function (json) {
          if (!res.ok || !json.ok) {
            throw new Error(json.message || 'Thao tác thất bại');
          }
          return json.data;
        });
    });
  }

  function pad(n) { return n < 10 ? '0' + n : '' + n; }

  function formatTime(iso) {
    if (!iso) return '';
    var d = new Date(iso);
    if (isNaN(d.getTime())) return '';
    return pad(d.getDate()) + '/' + pad(d.getMonth() + 1) + '/' + d.getFullYear()
      + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }

  function setStatus(msg) {
    if (msg) { statusEl.textContent = msg; statusEl.hidden = false; }
    else { statusEl.textContent = ''; statusEl.hidden = true; }
  }

  function el(tag, cls, text) {
    var node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text != null) node.textContent = text;
    return node;
  }

  function actionButton(label, danger, onClick) {
    var b = el('button', 'lesson-comment-action' + (danger ? ' is-danger' : ''), label);
    b.type = 'button';
    b.addEventListener('click', onClick);
    return b;
  }

  // Builds an inline textarea + submit/cancel used by reply and edit flows.
  function miniComposer(initial, submitLabel, onSubmit, onCancel) {
    var wrap = el('div', 'lesson-comment-reply-box');
    var ta = el('textarea', 'lesson-comments-input');
    ta.rows = 2; ta.maxLength = 2000; ta.value = initial || '';
    var actions = el('div', 'lesson-comments-composer-actions');
    var submit = el('button', 'lesson-comments-reply-submit', submitLabel);
    submit.type = 'button';
    submit.addEventListener('click', function () {
      var val = ta.value.trim();
      if (!val) { if (window.UlpToast) window.UlpToast.error(MSG_CONTENT_REQUIRED); return; }
      submit.disabled = true;
      onSubmit(val).catch(function () { submit.disabled = false; });
    });
    var cancel = actionButton('Huỷ', false, onCancel);
    actions.appendChild(submit);
    actions.appendChild(cancel);
    wrap.appendChild(ta);
    wrap.appendChild(actions);
    return wrap;
  }

  function mutate(promise, okMsg) {
    return promise.then(function () {
      if (okMsg && window.UlpToast) window.UlpToast.success(okMsg);
      load();
    }).catch(function (err) {
      if (window.UlpToast) window.UlpToast.error(err.message || 'Thao tác thất bại');
      throw err;
    });
  }

  function head(c) {
    var wrap = el('div', 'lesson-comment-head');
    wrap.appendChild(el('span', 'lesson-comment-author', c.deleted ? 'Ẩn danh' : (c.authorName || 'Người dùng')));
    if (c.lecturer) wrap.appendChild(el('span', 'lesson-comment-badge', 'GV'));
    wrap.appendChild(el('span', 'lesson-comment-time', formatTime(c.createdAt)));
    if (c.edited) wrap.appendChild(el('span', 'lesson-comment-edited', '(đã chỉnh sửa)'));
    return wrap;
  }

  function commentNode(c, isReply) {
    var node = el('div', 'lesson-comment');
    node.appendChild(head(c));

    var body = el('p', 'lesson-comment-body' + (c.deleted ? ' is-deleted' : ''),
      c.deleted ? '[Bình luận đã bị xoá]' : c.content);
    node.appendChild(body);

    if (!c.deleted) node.appendChild(actions(c, node, body, isReply));

    if (!isReply && c.replies && c.replies.length) {
      var replies = el('div', 'lesson-comment-replies');
      c.replies.forEach(function (r) { replies.appendChild(commentNode(r, true)); });
      node.appendChild(replies);
    }
    return node;
  }

  function actions(c, node, body, isReply) {
    var row = el('div', 'lesson-comment-actions');
    if (!isReply) {
      row.appendChild(actionButton('Trả lời', false, function () {
        if (node.querySelector('.lesson-comment-reply-box')) return;
        node.insertBefore(replyBox(c, node), node.querySelector('.lesson-comment-replies'));
      }));
    }
    if (c.canEdit) {
      row.appendChild(actionButton('Sửa', false, function () { startEdit(c, body); }));
    }
    if (c.canDelete) {
      row.appendChild(actionButton('Xoá', true, function () { confirmDelete(c, row); }));
    }
    return row;
  }

  function replyBox(c, node) {
    return miniComposer('', 'Gửi', function (val) {
      return mutate(api('POST', base, { content: val, parentId: c.id }), 'Đã gửi trả lời');
    }, function () {
      var box = node.querySelector('.lesson-comment-reply-box');
      if (box) box.remove();
    });
  }

  function startEdit(c, body) {
    var box = miniComposer(c.content, 'Lưu', function (val) {
      return mutate(api('PUT', base + '/' + c.id, { content: val }), 'Đã cập nhật bình luận');
    }, function () { load(); });
    body.replaceWith(box);
  }

  // Inline confirm (house rule bans native confirm/alert).
  function confirmDelete(c, row) {
    row.textContent = '';
    row.appendChild(el('span', 'lesson-comment-time', 'Xoá bình luận này?'));
    row.appendChild(actionButton('Xoá', true, function () {
      mutate(api('DELETE', base + '/' + c.id), 'Đã xoá bình luận');
    }));
    row.appendChild(actionButton('Huỷ', false, function () { load(); }));
  }

  function render(comments) {
    listEl.textContent = '';
    if (!comments.length) {
      setStatus('Chưa có thảo luận nào. Hãy là người đầu tiên đặt câu hỏi.');
      return;
    }
    setStatus(null);
    comments.forEach(function (c) { listEl.appendChild(commentNode(c, false)); });
  }

  function load() {
    api('GET', base).then(function (data) {
      render((data && data.comments) || []);
    }).catch(function () {
      setStatus('Không tải được thảo luận. Vui lòng thử lại.');
    });
  }

  if (composer) {
    composer.addEventListener('submit', function (e) {
      e.preventDefault();
      var val = (composerInput.value || '').trim();
      if (!val) { if (window.UlpToast) window.UlpToast.error(MSG_CONTENT_REQUIRED); return; }
      mutate(api('POST', base, { content: val }), 'Đã gửi bình luận').then(function () {
        composerInput.value = '';
      });
    });
  }

  load();
})();
