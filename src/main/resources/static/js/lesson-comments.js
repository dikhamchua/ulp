/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Lesson comments orchestrator (ULP-4.6)
   ----------------------------------------------------------------------------
   Owns the "Thảo luận" panel's data flow: CSRF-guarded fetch, load-more root
   pagination, the composer, and mutation → reload. DOM tree building is
   delegated to window.UlpCommentThread (lesson-comments-render.js), which must
   load first. Feedback via window.UlpToast.
   ══════════════════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var MSG_CONTENT_REQUIRED = 'Nội dung không được để trống';

  var panel = document.querySelector('.lesson-comments[data-lesson-id]');
  if (!panel) return;
  if (typeof window.UlpCommentThread !== 'function') {
    // Renderer script missing/mis-ordered — fail loud rather than silently blank.
    console.error('lesson-comments: UlpCommentThread renderer not loaded');
    return;
  }

  var lessonId = panel.getAttribute('data-lesson-id');
  var base = '/api/lessons/' + lessonId + '/comments';
  var listEl = panel.querySelector('[data-role="list"]');
  var statusEl = panel.querySelector('[data-role="status"]');
  var composer = panel.querySelector('[data-role="composer"]');
  var composerInput = panel.querySelector('[data-role="input"]');

  // Load-more pagination state: current root page + whether more roots exist.
  var currentPage = 0;
  var hasNext = false;
  var loadMoreBtn = null;

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

  function setStatus(msg) {
    if (msg) { statusEl.textContent = msg; statusEl.hidden = false; }
    else { statusEl.textContent = ''; statusEl.hidden = true; }
  }

  function mutate(promise, okMsg) {
    return promise.then(function () {
      if (okMsg && window.UlpToast) window.UlpToast.success(okMsg);
      // Reset to the first page: newest-first means new comments appear on top.
      // Trade-off: editing/deleting a comment on a deep page also resets to top.
      load(0, false);
    }).catch(function (err) {
      if (window.UlpToast) window.UlpToast.error(err.message || 'Thao tác thất bại');
      throw err;
    });
  }

  // Presentational tree builder; data flow (fetch/pagination/toasts) stays here.
  var thread = window.UlpCommentThread({
    api: api,
    base: base,
    mutate: mutate,
    reload: function () { return load(0, false); },
    contentRequiredMsg: MSG_CONTENT_REQUIRED
  });

  // Renders a page of roots; append=false clears first, true concatenates.
  function renderInto(comments, append) {
    if (!append) listEl.textContent = '';
    comments.forEach(function (c) { listEl.appendChild(thread.node(c, 1)); });
  }

  // Lazily creates the "Xem thêm" button once, placed right after the list.
  function ensureLoadMoreBtn() {
    if (loadMoreBtn) return loadMoreBtn;
    loadMoreBtn = document.createElement('button');
    loadMoreBtn.className = 'lesson-comments-loadmore';
    loadMoreBtn.type = 'button';
    loadMoreBtn.textContent = 'Xem thêm bình luận';
    loadMoreBtn.addEventListener('click', function () {
      loadMoreBtn.disabled = true; // guard against double-tap while loading
      load(currentPage + 1, true).catch(function () { loadMoreBtn.disabled = false; });
    });
    if (listEl.parentNode) listEl.parentNode.insertBefore(loadMoreBtn, listEl.nextSibling);
    return loadMoreBtn;
  }

  function updateLoadMore() {
    if (hasNext) {
      var btn = ensureLoadMoreBtn();
      btn.hidden = false;
      btn.disabled = false;
    } else if (loadMoreBtn) {
      loadMoreBtn.hidden = true;
    }
  }

  // Loads one root page. append=true keeps prior pages (load-more); false resets.
  function load(page, append) {
    return api('GET', base + '?page=' + page).then(function (data) {
      data = data || {};
      var comments = data.comments || [];
      currentPage = typeof data.page === 'number' ? data.page : page;
      hasNext = !!data.hasNext;
      // Empty state only applies to the fresh first page.
      if (!append && data.totalRoots === 0) {
        listEl.textContent = '';
        setStatus('Chưa có thảo luận nào. Hãy là người đầu tiên đặt câu hỏi.');
        updateLoadMore();
        return;
      }
      setStatus(null);
      renderInto(comments, append);
      updateLoadMore();
    }).catch(function (err) {
      if (!append) setStatus('Không tải được thảo luận. Vui lòng thử lại.');
      throw err;
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

  load(0, false);
})();
