/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Lesson comment thread renderer (ULP-4.6)
   ----------------------------------------------------------------------------
   DOM-only builder for one comment subtree (Facebook-style: avatar + rounded
   bubble, relative time, replies collapsed behind a toggle, nesting capped at 3).
   All user content is injected via textContent (never innerHTML); avatarGradient
   is a server-generated CSS value applied via style.background.

   Exposes window.UlpCommentThread(deps) → { node }. The orchestrator
   (lesson-comments.js) owns fetching, pagination, CSRF and toasts and injects
   them as deps = { api, base, mutate, reload, contentRequiredMsg }.
   ══════════════════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var MAX_DEPTH = 3;

  function pad(n) { return n < 10 ? '0' + n : '' + n; }

  function absoluteTime(d) {
    return pad(d.getDate()) + '/' + pad(d.getMonth() + 1) + '/' + d.getFullYear()
      + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }

  // Relative "time ago" label; falls back to DD/MM/YYYY past a week.
  function relativeTime(d) {
    var diff = Date.now() - d.getTime();
    if (diff < 0) diff = 0;
    var s = Math.floor(diff / 1000);
    if (s < 60) return 'Vừa xong';
    var m = Math.floor(s / 60);
    if (m < 60) return m + ' phút';
    var h = Math.floor(m / 60);
    if (h < 24) return h + ' giờ';
    var days = Math.floor(h / 24);
    if (days < 7) return days + ' ngày';
    return pad(d.getDate()) + '/' + pad(d.getMonth() + 1) + '/' + d.getFullYear();
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

  /**
   * Builds a comment subtree. deps wires the DOM back to the orchestrator's data
   * flow so this file stays purely presentational.
   */
  function createThread(deps) {
    var api = deps.api, base = deps.base, mutate = deps.mutate, reload = deps.reload;
    var MSG_CONTENT_REQUIRED = deps.contentRequiredMsg;

    // Inline textarea + submit/cancel used by reply and edit flows.
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

    // Round avatar: author image when present, else initials on a colour gradient.
    function avatar(c) {
      var wrap = el('div', 'lesson-comment-avatar');
      if (c.authorAvatarUrl) {
        var img = el('img', 'lesson-comment-avatar-img');
        img.src = c.authorAvatarUrl;
        img.alt = '';
        wrap.appendChild(img);
      } else {
        wrap.classList.add('is-fallback');
        if (c.avatarGradient) wrap.style.background = c.avatarGradient; // server value
        if (!c.deleted) wrap.textContent = c.avatarLabel || '?';
      }
      return wrap;
    }

    function bubble(c) {
      var cls = 'lesson-comment-bubble' + (c.deleted ? ' is-deleted' : '')
        + (c.hidden ? ' is-hidden' : '');
      var b = el('div', cls);
      b.appendChild(el('span', 'lesson-comment-author',
        c.deleted ? 'Người dùng' : (c.authorName || 'Người dùng')));
      if (c.lecturer && !c.deleted) b.appendChild(el('span', 'lesson-comment-badge', 'GV'));
      // Only a moderator ever receives a hidden row; flag it so intent is clear.
      if (c.hidden) b.appendChild(el('span', 'lesson-comment-hidden-tag', 'Đã ẩn'));
      var body = el('div', 'lesson-comment-body',
        c.deleted ? '[Bình luận đã bị xoá]' : c.content);
      b.appendChild(body);
      return { bubble: b, body: body };
    }

    // Appends the relative-time label (with edited marker) when parseable.
    function appendTime(c, row) {
      var d = new Date(c.createdAt);
      if (isNaN(d.getTime())) return;
      var time = el('span', 'lesson-comment-time',
        relativeTime(d) + (c.edited ? ' · đã chỉnh sửa' : ''));
      time.title = absoluteTime(d);
      row.appendChild(time);
    }

    function actionRow(c, node, body) {
      var row = el('div', 'lesson-comment-actions');
      if (c.hidden) {
        // Hidden node (moderator view only): offer just the unhide control.
        if (c.canModerate) {
          row.appendChild(actionButton('Mở lại', false, function () { confirmModerate(c, row, false); }));
        }
        appendTime(c, row);
        return row;
      }
      // Reply is offered on every level; the API clamps nesting back to 3.
      row.appendChild(actionButton('Trả lời', false, function () { openReply(c, node); }));
      if (c.canEdit) row.appendChild(actionButton('Sửa', false, function () { startEdit(c, body); }));
      if (c.canDelete) row.appendChild(actionButton('Xoá', true, function () { confirmDelete(c, row); }));
      if (c.canModerate) row.appendChild(actionButton('Ẩn', false, function () { confirmModerate(c, row, true); }));
      appendTime(c, row);
      return row;
    }

    function commentNode(c, depth) {
      var node = el('div', 'lesson-comment');
      node.setAttribute('data-depth', depth);
      node.appendChild(avatar(c));

      var col = el('div', 'lesson-comment-col');
      var parts = bubble(c);
      col.appendChild(parts.bubble);
      if (!c.deleted) col.appendChild(actionRow(c, node, parts.body));

      // Reply composer mounts here, above any nested replies.
      var replyMount = el('div', 'lesson-comment-reply-mount');
      col.appendChild(replyMount);
      node._replyMount = replyMount;

      if (c.replies && c.replies.length) {
        col.appendChild(repliesBlock(c, depth));
      }

      node.appendChild(col);
      return node;
    }

    // Collapsed-by-default replies plus a "Xem N câu trả lời" / "Ẩn" toggle.
    function repliesBlock(c, depth) {
      var frag = document.createDocumentFragment();
      var count = c.replies.length;
      var wrap = el('div', 'lesson-comment-replies');
      wrap.hidden = true;
      var childDepth = Math.min(depth + 1, MAX_DEPTH);
      c.replies.forEach(function (r) { wrap.appendChild(commentNode(r, childDepth)); });

      var toggle = el('button', 'lesson-comment-toggle');
      toggle.type = 'button';
      function sync() {
        toggle.textContent = wrap.hidden
          ? '▾ Xem ' + count + ' câu trả lời'
          : '▴ Ẩn câu trả lời';
      }
      sync();
      toggle.addEventListener('click', function () {
        wrap.hidden = !wrap.hidden;
        sync();
      });

      frag.appendChild(toggle);
      frag.appendChild(wrap);
      return frag;
    }

    function openReply(c, node) {
      var mount = node._replyMount;
      if (!mount || mount.querySelector('.lesson-comment-reply-box')) return;
      var box = miniComposer('', 'Gửi', function (val) {
        return mutate(api('POST', base, { content: val, parentId: c.id }), 'Đã gửi trả lời');
      }, function () { mount.textContent = ''; });
      mount.appendChild(box);
      var ta = box.querySelector('textarea');
      if (ta) ta.focus();
    }

    function startEdit(c, body) {
      var box = miniComposer(c.content, 'Lưu', function (val) {
        return mutate(api('PUT', base + '/' + c.id, { content: val }), 'Đã cập nhật bình luận');
      }, function () { reload(); });
      body.replaceWith(box);
    }

    // Inline confirm (house rule bans native confirm/alert).
    function confirmDelete(c, row) {
      row.textContent = '';
      row.appendChild(el('span', 'lesson-comment-time', 'Xoá bình luận này?'));
      row.appendChild(actionButton('Xoá', true, function () {
        mutate(api('DELETE', base + '/' + c.id), 'Đã xoá bình luận');
      }));
      row.appendChild(actionButton('Huỷ', false, function () { reload(); }));
    }

    // Inline confirm for moderator hide/unhide; hide=true → Ẩn, false → Mở lại.
    function confirmModerate(c, row, hide) {
      var prompt = hide ? 'Ẩn bình luận này?' : 'Hiện lại bình luận này?';
      var label = hide ? 'Ẩn' : 'Mở lại';
      var okMsg = hide ? 'Đã ẩn bình luận' : 'Đã hiện lại bình luận';
      var path = hide ? '/hide' : '/unhide';
      row.textContent = '';
      row.appendChild(el('span', 'lesson-comment-time', prompt));
      row.appendChild(actionButton(label, hide, function () {
        mutate(api('POST', base + '/' + c.id + path), okMsg);
      }));
      row.appendChild(actionButton('Huỷ', false, function () { reload(); }));
    }

    return { node: commentNode };
  }

  window.UlpCommentThread = createThread;
})();
