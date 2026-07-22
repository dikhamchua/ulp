/* Library attach-to-class wizard.
 * Single: open(asset) — class → section → lesson → role → bind.
 * Multi:  openMany(assets) — class → section → lesson → preview (auto roles) → bind loop.
 * Reuses existing bind APIs; stays on the library page after success.
 */
(function () {
  'use strict';

  var TARGETS = '/lecturer/library/targets';
  var STEP_CLASS = 1;
  var STEP_SECTION = 2;
  var STEP_LESSON = 3;
  var STEP_ROLE = 4;
  var STEP_PREVIEW = 4;

  var ROLE_MAIN_PDF = 'MAIN_PDF';
  var ROLE_MAIN_VIDEO = 'MAIN_VIDEO';
  var ROLE_ATTACHMENT = 'ATTACHMENT';

  var MSG_SUCCESS = 'Đã gắn học liệu vào bài giảng';
  var MSG_FAIL = 'Không gắn được học liệu vào bài giảng';
  var MSG_REPLACE_PDF = 'Bài giảng đã có PDF chính. Thay thế bằng tệp từ kho?';
  var MSG_REPLACE_VIDEO = 'Bài giảng đã có video tải lên. Thay thế bằng video từ kho?';
  var MSG_EMPTY_JOBS = 'Không có học liệu nào phù hợp để gắn vào bài.';
  var MSG_BIND_MANY_FAIL = 'Không gắn được học liệu đã chọn.';

  var state = {
    open: false,
    multi: false,
    step: STEP_CLASS,
    asset: null,
    assets: [],
    classId: null,
    className: '',
    sectionId: null,
    sectionTitle: '',
    lessonId: null,
    lessonTitle: '',
    role: null,
    classPage: 0,
    classTotalPages: 0,
    classQ: '',
    binding: false,
    plan: null
  };

  function toast(kind, message) {
    if (window.UlpToast && typeof window.UlpToast[kind] === 'function') {
      window.UlpToast[kind](message);
    }
  }

  function el(id) {
    return document.getElementById(id);
  }

  function csrfHeaders() {
    var tokenMeta = document.querySelector('meta[name="_csrf"]');
    var headerMeta = document.querySelector('meta[name="_csrf_header"]');
    var headers = { 'Accept': 'application/json' };
    if (tokenMeta && headerMeta) {
      var token = tokenMeta.getAttribute('content');
      var header = headerMeta.getAttribute('content') || 'X-CSRF-TOKEN';
      if (token) headers[header] = token;
    }
    return headers;
  }

  function isPdf(mime, filename) {
    var m = (mime || '').toLowerCase();
    if (m === 'application/pdf' || m.indexOf('pdf') >= 0) return true;
    var name = (filename || '').toLowerCase();
    return name.endsWith('.pdf');
  }

  function rolesForAsset(asset) {
    if (!asset) return [];
    if (asset.kind === 'VIDEO') {
      return [{ key: ROLE_MAIN_VIDEO, label: 'Video chính' }];
    }
    if (asset.kind === 'DOCUMENT') {
      if (isPdf(asset.mime, asset.filename)) {
        return [
          { key: ROLE_MAIN_PDF, label: 'PDF chính' },
          { key: ROLE_ATTACHMENT, label: 'Đính kèm' }
        ];
      }
      return [{ key: ROLE_ATTACHMENT, label: 'Đính kèm' }];
    }
    return [];
  }

  function roleLabel(role) {
    if (role === ROLE_MAIN_PDF) return 'PDF chính';
    if (role === ROLE_MAIN_VIDEO) return 'Video chính';
    if (role === ROLE_ATTACHMENT) return 'Đính kèm';
    return role || '';
  }

  /** Smart role assignment for multi-select (preserves selection/list order). */
  function planMultiRoles(assets) {
    var jobs = [];
    var skippedVideos = [];
    var mainPdfAssigned = false;
    var mainVideoAssigned = false;
    var list = assets || [];

    list.forEach(function (asset) {
      if (!asset || !asset.id) return;
      if (asset.kind === 'VIDEO') {
        if (!mainVideoAssigned) {
          jobs.push({ asset: asset, role: ROLE_MAIN_VIDEO });
          mainVideoAssigned = true;
        } else {
          skippedVideos.push(asset);
        }
        return;
      }
      if (asset.kind === 'DOCUMENT') {
        if (isPdf(asset.mime, asset.filename)) {
          if (!mainPdfAssigned) {
            jobs.push({ asset: asset, role: ROLE_MAIN_PDF });
            mainPdfAssigned = true;
          } else {
            jobs.push({ asset: asset, role: ROLE_ATTACHMENT });
          }
          return;
        }
        jobs.push({ asset: asset, role: ROLE_ATTACHMENT });
        return;
      }
    });

    // Bind order: MAIN_PDF → MAIN_VIDEO → ATTACHMENTs.
    var ordered = [];
    jobs.forEach(function (j) {
      if (j.role === ROLE_MAIN_PDF) ordered.push(j);
    });
    jobs.forEach(function (j) {
      if (j.role === ROLE_MAIN_VIDEO) ordered.push(j);
    });
    jobs.forEach(function (j) {
      if (j.role === ROLE_ATTACHMENT) ordered.push(j);
    });

    return {
      jobs: ordered,
      skippedVideos: skippedVideos,
      hasMainPdf: mainPdfAssigned,
      hasMainVideo: mainVideoAssigned
    };
  }

  function finalStep() {
    return state.multi ? STEP_PREVIEW : STEP_ROLE;
  }

  function setStepChrome() {
    var steps = document.querySelectorAll('#libraryAttachSteps li');
    steps.forEach(function (li) {
      var n = Number(li.getAttribute('data-step'));
      // Multi-mode: step 4 label is preview, still data-step="4".
      li.classList.toggle('is-active', n === state.step);
      li.classList.toggle('is-done', n < state.step);
      if (n === 4) {
        li.textContent = state.multi ? 'Xem trước' : 'Vai trò';
      }
    });

    var tools = el('libraryAttachTools');
    if (tools) tools.hidden = state.step !== STEP_CLASS;

    var back = el('libraryAttachBack');
    var next = el('libraryAttachNext');
    var finish = el('libraryAttachFinish');
    if (back) back.hidden = state.step === STEP_CLASS || state.binding;
    if (next) {
      next.hidden = state.step === finalStep() || state.binding;
      next.disabled = !canAdvance() || state.binding;
    }
    if (finish) {
      finish.hidden = state.step !== finalStep();
      if (state.multi) {
        var hasJobs = state.plan && state.plan.jobs && state.plan.jobs.length > 0;
        finish.disabled = !hasJobs || state.binding;
      } else {
        finish.disabled = !state.role || state.binding;
      }
    }
  }

  function canAdvance() {
    if (state.step === STEP_CLASS) return !!state.classId;
    if (state.step === STEP_SECTION) return !!state.sectionId;
    if (state.step === STEP_LESSON) return !!state.lessonId;
    return false;
  }

  function bodyLoading(text) {
    var body = el('libraryAttachBody');
    if (!body) return;
    body.innerHTML = '<div class="library-attach-loading">' +
      (text || 'Đang tải…') + '</div>';
  }

  function bodyEmpty(title, hint) {
    var body = el('libraryAttachBody');
    if (!body) return;
    body.innerHTML =
      '<div class="library-attach-empty">' +
      '<strong></strong><p></p></div>';
    body.querySelector('strong').textContent = title || 'Không có dữ liệu';
    body.querySelector('p').textContent = hint || '';
  }

  function bodyError(message) {
    var body = el('libraryAttachBody');
    if (!body) return;
    body.innerHTML = '<div class="library-attach-error"></div>';
    body.querySelector('.library-attach-error').textContent =
      message || 'Không tải được dữ liệu. Thử lại.';
  }

  function selectItem(btn, selected) {
    var list = btn.parentElement;
    if (list) {
      list.querySelectorAll('.library-attach-item').forEach(function (node) {
        node.classList.remove('is-selected');
      });
    }
    if (selected) btn.classList.add('is-selected');
  }

  function renderClassList(page) {
    var body = el('libraryAttachBody');
    if (!body) return;
    var items = (page && page.items) || [];
    state.classPage = page ? (page.page || 0) : 0;
    state.classTotalPages = page ? (page.totalPages || 0) : 0;

    if (!items.length) {
      bodyEmpty(
        state.classQ ? 'Không tìm thấy lớp khớp' : 'Chưa có lớp để gắn',
        state.classQ
          ? 'Thử từ khoá khác.'
          : 'Tạo lớp ở mục Lớp học trước, rồi quay lại kho học liệu.'
      );
      setStepChrome();
      return;
    }

    body.innerHTML = '';
    var list = document.createElement('div');
    list.className = 'library-attach-list';
    items.forEach(function (item) {
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'library-attach-item';
      if (state.classId === item.id) btn.classList.add('is-selected');
      btn.innerHTML =
        '<span class="library-attach-item-title"></span>' +
        '<span class="library-attach-item-meta"></span>';
      btn.querySelector('.library-attach-item-title').textContent = item.name || ('Lớp #' + item.id);
      btn.querySelector('.library-attach-item-meta').textContent =
        item.code ? ('Mã: ' + item.code) : '';
      btn.addEventListener('click', function () {
        if (state.binding) return;
        state.classId = item.id;
        state.className = item.name || '';
        state.sectionId = null;
        state.sectionTitle = '';
        state.lessonId = null;
        state.lessonTitle = '';
        state.role = null;
        state.plan = null;
        selectItem(btn, true);
        setStepChrome();
      });
      list.appendChild(btn);
    });
    body.appendChild(list);

    if (state.classTotalPages > 1) {
      var pager = document.createElement('div');
      pager.className = 'library-attach-pager';
      var prev = document.createElement('button');
      prev.type = 'button';
      prev.className = 'btn-ghost btn-sm';
      prev.textContent = 'Trước';
      prev.disabled = state.classPage <= 0 || state.binding;
      prev.addEventListener('click', function () {
        if (state.binding) return;
        if (state.classPage > 0) {
          state.classPage -= 1;
          loadClasses();
        }
      });
      var label = document.createElement('span');
      label.textContent = 'Trang ' + (state.classPage + 1) + ' / ' + state.classTotalPages;
      var next = document.createElement('button');
      next.type = 'button';
      next.className = 'btn-ghost btn-sm';
      next.textContent = 'Sau';
      next.disabled = state.classPage + 1 >= state.classTotalPages || state.binding;
      next.addEventListener('click', function () {
        if (state.binding) return;
        if (state.classPage + 1 < state.classTotalPages) {
          state.classPage += 1;
          loadClasses();
        }
      });
      pager.appendChild(prev);
      pager.appendChild(label);
      pager.appendChild(next);
      body.appendChild(pager);
    }
    setStepChrome();
  }

  function renderSimpleList(items, selectedId, getTitle, getMeta, onPick, emptyTitle, emptyHint) {
    var body = el('libraryAttachBody');
    if (!body) return;
    if (!items || !items.length) {
      bodyEmpty(emptyTitle, emptyHint);
      setStepChrome();
      return;
    }
    body.innerHTML = '';
    var list = document.createElement('div');
    list.className = 'library-attach-list';
    items.forEach(function (item) {
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'library-attach-item';
      if (selectedId === item.id) btn.classList.add('is-selected');
      btn.innerHTML =
        '<span class="library-attach-item-title"></span>' +
        '<span class="library-attach-item-meta"></span>';
      btn.querySelector('.library-attach-item-title').textContent = getTitle(item);
      var meta = getMeta ? getMeta(item) : '';
      btn.querySelector('.library-attach-item-meta').textContent = meta || '';
      btn.addEventListener('click', function () {
        if (state.binding) return;
        onPick(item, btn);
        selectItem(btn, true);
        setStepChrome();
      });
      list.appendChild(btn);
    });
    body.appendChild(list);
    setStepChrome();
  }

  function renderRoles() {
    var body = el('libraryAttachBody');
    if (!body) return;
    var roles = rolesForAsset(state.asset);
    if (!roles.length) {
      bodyEmpty('Không có vai trò phù hợp', 'Loại học liệu này chưa hỗ trợ gắn vào bài.');
      setStepChrome();
      return;
    }
    body.innerHTML = '';
    var summary = document.createElement('div');
    summary.className = 'library-attach-summary';
    summary.innerHTML =
      '<div><span class="k">Lớp</span> <span class="v" data-k="class"></span></div>' +
      '<div><span class="k">Chương</span> <span class="v" data-k="section"></span></div>' +
      '<div><span class="k">Bài giảng</span> <span class="v" data-k="lesson"></span></div>';
    summary.querySelector('[data-k="class"]').textContent = state.className || ('#' + state.classId);
    summary.querySelector('[data-k="section"]').textContent = state.sectionTitle || ('#' + state.sectionId);
    summary.querySelector('[data-k="lesson"]').textContent = state.lessonTitle || ('#' + state.lessonId);
    body.appendChild(summary);

    var list = document.createElement('div');
    list.className = 'library-attach-list library-attach-roles';
    roles.forEach(function (role) {
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'library-attach-item';
      if (state.role === role.key) btn.classList.add('is-selected');
      btn.innerHTML = '<span class="library-attach-item-title"></span>';
      btn.querySelector('.library-attach-item-title').textContent = role.label;
      btn.addEventListener('click', function () {
        if (state.binding) return;
        state.role = role.key;
        selectItem(btn, true);
        setStepChrome();
      });
      list.appendChild(btn);
    });
    body.appendChild(list);

    // Single role: auto-select so Finish is one click away.
    if (roles.length === 1 && !state.role) {
      state.role = roles[0].key;
      var first = list.querySelector('.library-attach-item');
      if (first) first.classList.add('is-selected');
    }
    setStepChrome();
  }

  function renderPreview() {
    var body = el('libraryAttachBody');
    if (!body) return;
    state.plan = planMultiRoles(state.assets);
    var plan = state.plan;

    body.innerHTML = '';
    var summary = document.createElement('div');
    summary.className = 'library-attach-summary';
    summary.innerHTML =
      '<div><span class="k">Lớp</span> <span class="v" data-k="class"></span></div>' +
      '<div><span class="k">Chương</span> <span class="v" data-k="section"></span></div>' +
      '<div><span class="k">Bài giảng</span> <span class="v" data-k="lesson"></span></div>' +
      '<div><span class="k">Sẽ gắn</span> <span class="v" data-k="count"></span></div>';
    summary.querySelector('[data-k="class"]').textContent = state.className || ('#' + state.classId);
    summary.querySelector('[data-k="section"]').textContent = state.sectionTitle || ('#' + state.sectionId);
    summary.querySelector('[data-k="lesson"]').textContent = state.lessonTitle || ('#' + state.lessonId);
    summary.querySelector('[data-k="count"]').textContent =
      plan.jobs.length + ' / ' + (state.assets ? state.assets.length : 0) + ' học liệu';
    body.appendChild(summary);

    if (!plan.jobs.length && !plan.skippedVideos.length) {
      var empty = document.createElement('div');
      empty.className = 'library-attach-empty';
      empty.innerHTML = '<strong></strong><p></p>';
      empty.querySelector('strong').textContent = 'Không có mục để gắn';
      empty.querySelector('p').textContent = MSG_EMPTY_JOBS;
      body.appendChild(empty);
      setStepChrome();
      return;
    }

    var list = document.createElement('div');
    list.className = 'library-attach-list';
    plan.jobs.forEach(function (job) {
      var row = document.createElement('div');
      row.className = 'library-attach-item';
      row.style.cursor = 'default';
      row.innerHTML =
        '<span class="library-attach-item-title"></span>' +
        '<span class="library-attach-item-meta"></span>';
      var title = job.asset.title || job.asset.filename || ('#' + job.asset.id);
      row.querySelector('.library-attach-item-title').textContent = title;
      row.querySelector('.library-attach-item-meta').textContent =
        roleLabel(job.role);
      list.appendChild(row);
    });
    plan.skippedVideos.forEach(function (asset) {
      var row = document.createElement('div');
      row.className = 'library-attach-item';
      row.style.cursor = 'default';
      row.innerHTML =
        '<span class="library-attach-item-title"></span>' +
        '<span class="library-attach-item-meta"></span>';
      var title = asset.title || asset.filename || ('#' + asset.id);
      row.querySelector('.library-attach-item-title').textContent = title;
      row.querySelector('.library-attach-item-meta').textContent =
        'Bỏ qua — mỗi bài chỉ 1 video chính';
      list.appendChild(row);
    });
    body.appendChild(list);
    setStepChrome();
  }

  function loadClasses() {
    bodyLoading('Đang tải danh sách lớp…');
    var url = TARGETS + '/classes?page=' + encodeURIComponent(state.classPage) +
      '&size=12&q=' + encodeURIComponent(state.classQ || '');
    fetch(url, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
      .then(function (res) {
        if (!res.ok) throw new Error('load classes failed');
        return res.json();
      })
      .then(renderClassList)
      .catch(function () {
        bodyError('Không tải được danh sách lớp.');
        setStepChrome();
      });
  }

  function loadSections() {
    bodyLoading('Đang tải chương…');
    var url = TARGETS + '/classes/' + encodeURIComponent(state.classId) + '/sections';
    fetch(url, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
      .then(function (res) {
        if (res.status === 403 || res.status === 404) throw new Error('forbidden');
        if (!res.ok) throw new Error('load sections failed');
        return res.json();
      })
      .then(function (items) {
        // Backend auto-creates "Chương 1" when empty; pre-select sole section.
        if (items && items.length === 1 && !state.sectionId) {
          state.sectionId = items[0].id;
          state.sectionTitle = items[0].title || '';
        }
        renderSimpleList(
          items,
          state.sectionId,
          function (s) { return s.title || ('Chương #' + s.id); },
          null,
          function (s) {
            state.sectionId = s.id;
            state.sectionTitle = s.title || '';
            state.lessonId = null;
            state.lessonTitle = '';
            state.role = null;
            state.plan = null;
          },
          'Không tạo được chương mặc định',
          'Thử lại hoặc mở trang Lớp học để tạo chương thủ công.'
        );
      })
      .catch(function () {
        bodyError('Không tải được danh sách chương.');
        setStepChrome();
      });
  }

  function loadLessons() {
    bodyLoading('Đang tải bài giảng…');
    var url = TARGETS + '/classes/' + encodeURIComponent(state.classId) +
      '/sections/' + encodeURIComponent(state.sectionId) + '/lessons';
    fetch(url, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
      .then(function (res) {
        if (res.status === 403 || res.status === 404) throw new Error('forbidden');
        if (!res.ok) throw new Error('load lessons failed');
        return res.json();
      })
      .then(function (items) {
        renderSimpleList(
          items,
          state.lessonId,
          function (l) { return l.title || ('Bài #' + l.id); },
          function (l) {
            var bits = [];
            if (l.status) bits.push(l.status);
            if (l.contentType) bits.push(l.contentType);
            return bits.join(' · ');
          },
          function (l) {
            state.lessonId = l.id;
            state.lessonTitle = l.title || '';
            state.role = null;
            state.plan = null;
          },
          'Chương chưa có bài giảng',
          'Tạo bài giảng trong tab Bài giảng của lớp, rồi quay lại để gắn.'
        );
      })
      .catch(function () {
        bodyError('Không tải được danh sách bài giảng.');
        setStepChrome();
      });
  }

  function showStep() {
    setStepChrome();
    if (state.step === STEP_CLASS) {
      loadClasses();
    } else if (state.step === STEP_SECTION) {
      loadSections();
    } else if (state.step === STEP_LESSON) {
      loadLessons();
    } else if (state.step === STEP_ROLE || state.step === STEP_PREVIEW) {
      if (state.multi) renderPreview();
      else renderRoles();
    }
  }

  function bindUrlFor(role) {
    var base = '/lecturer/classes/' + state.classId +
      '/sections/' + state.sectionId +
      '/lessons/' + state.lessonId;
    if (role === ROLE_MAIN_PDF) {
      return base + '/content/pdf-from-library';
    }
    if (role === ROLE_MAIN_VIDEO) {
      return base + '/content/video-from-library';
    }
    return base + '/attachments/from-library';
  }

  function fetchContentSummary() {
    var url = TARGETS + '/classes/' + encodeURIComponent(state.classId) +
      '/sections/' + encodeURIComponent(state.sectionId) +
      '/lessons/' + encodeURIComponent(state.lessonId) +
      '/content-summary';
    return fetch(url, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
      .then(function (res) {
        if (!res.ok) return null;
        return res.json();
      })
      .catch(function () { return null; });
  }

  function postBind(role, assetId) {
    var url = bindUrlFor(role) + '?assetId=' + encodeURIComponent(assetId);
    return fetch(url, {
      method: 'POST',
      credentials: 'same-origin',
      headers: csrfHeaders()
    }).then(function (res) {
      return res.json().catch(function () { return {}; }).then(function (body) {
        return { ok: res.ok, status: res.status, body: body };
      });
    });
  }

  function doBind() {
    if (state.binding || !state.asset || !state.role) return;
    state.binding = true;
    setStepChrome();
    var finish = el('libraryAttachFinish');
    if (finish) finish.textContent = 'Đang gắn…';

    postBind(state.role, state.asset.id)
      .then(function (result) {
        state.binding = false;
        if (finish) finish.textContent = 'Gắn vào bài';
        setStepChrome();
        if (result.ok) {
          toast('success', MSG_SUCCESS);
          close();
          return;
        }
        var msg = (result.body && (result.body.message || result.body.error)) || MSG_FAIL;
        toast('error', msg);
      })
      .catch(function () {
        state.binding = false;
        if (finish) finish.textContent = 'Gắn vào bài';
        setStepChrome();
        toast('error', MSG_FAIL);
      });
  }

  function summarizeMany(ok, fail, skipped) {
    var totalAttempted = ok + fail;
    var parts = [];
    if (ok > 0 && fail === 0) {
      parts.push('Đã gắn ' + ok + '/' + totalAttempted + ' học liệu vào bài giảng');
    } else if (ok > 0) {
      parts.push('Đã gắn ' + ok + '/' + totalAttempted + ' học liệu');
      parts.push('lỗi ' + fail);
    } else if (fail > 0) {
      parts.push(MSG_BIND_MANY_FAIL + ' (' + fail + ' lỗi)');
    } else {
      parts.push('Không gắn học liệu nào');
    }
    if (skipped > 0) {
      parts.push('Bỏ qua ' + skipped + ' video (mỗi bài chỉ 1 video chính)');
    }
    return parts.join('. ') + (parts.length ? '.' : '');
  }

  function doBindMany() {
    if (state.binding || !state.multi) return;
    if (!state.plan) state.plan = planMultiRoles(state.assets);
    var plan = state.plan;
    if (!plan.jobs.length) {
      toast('error', MSG_EMPTY_JOBS);
      return;
    }

    state.binding = true;
    setStepChrome();
    var finish = el('libraryAttachFinish');
    if (finish) finish.textContent = 'Đang gắn…';

    var ok = 0;
    var fail = 0;
    var jobs = plan.jobs.slice();
    var skipped = plan.skippedVideos ? plan.skippedVideos.length : 0;

    function next(i) {
      if (i >= jobs.length) {
        state.binding = false;
        if (finish) finish.textContent = 'Gắn vào bài';
        setStepChrome();
        var msg = summarizeMany(ok, fail, skipped);
        if (ok > 0) {
          toast('success', msg);
          if (window.LibraryBulkSelect && typeof window.LibraryBulkSelect.clear === 'function') {
            window.LibraryBulkSelect.clear();
          }
          close();
        } else {
          toast('error', msg);
        }
        return;
      }
      var job = jobs[i];
      if (finish) {
        finish.textContent = 'Đang gắn… (' + (i + 1) + '/' + jobs.length + ')';
      }
      postBind(job.role, job.asset.id)
        .then(function (result) {
          if (result.ok) ok += 1;
          else fail += 1;
          next(i + 1);
        })
        .catch(function () {
          fail += 1;
          next(i + 1);
        });
    }

    next(0);
  }

  function confirmReplaceIfNeededThenBind() {
    if (state.multi) {
      confirmReplaceIfNeededThenBindMany();
      return;
    }
    if (state.role !== ROLE_MAIN_PDF && state.role !== ROLE_MAIN_VIDEO) {
      doBind();
      return;
    }
    var finish = el('libraryAttachFinish');
    if (finish) {
      finish.disabled = true;
      finish.textContent = 'Đang kiểm tra…';
    }
    fetchContentSummary().then(function (summary) {
      if (finish) finish.textContent = 'Gắn vào bài';
      setStepChrome();
      var needsReplace = false;
      var bodyMsg = '';
      if (state.role === ROLE_MAIN_PDF && summary && summary.hasMainPdf) {
        needsReplace = true;
        bodyMsg = MSG_REPLACE_PDF;
        if (summary.pdfFilename) {
          bodyMsg += ' (hiện tại: ' + summary.pdfFilename + ')';
        }
      }
      if (state.role === ROLE_MAIN_VIDEO && summary && summary.hasUploadedVideo) {
        needsReplace = true;
        bodyMsg = MSG_REPLACE_VIDEO;
      }
      if (!needsReplace) {
        doBind();
        return;
      }
      function proceed() { doBind(); }
      if (window.UlpModal && typeof window.UlpModal.confirm === 'function') {
        window.UlpModal.confirm({
          title: 'Thay thế nội dung?',
          body: bodyMsg,
          confirmLabel: 'Thay thế',
          onConfirm: proceed
        });
      } else if (window.confirm(bodyMsg)) {
        proceed();
      }
    });
  }

  function confirmReplaceIfNeededThenBindMany() {
    if (!state.plan) state.plan = planMultiRoles(state.assets);
    var plan = state.plan;
    if (!plan.jobs.length) {
      toast('error', MSG_EMPTY_JOBS);
      return;
    }

    var finish = el('libraryAttachFinish');
    if (finish) {
      finish.disabled = true;
      finish.textContent = 'Đang kiểm tra…';
    }

    var needsPdfCheck = plan.hasMainPdf;
    var needsVideoCheck = plan.hasMainVideo;
    if (!needsPdfCheck && !needsVideoCheck) {
      if (finish) finish.textContent = 'Gắn vào bài';
      setStepChrome();
      doBindMany();
      return;
    }

    fetchContentSummary().then(function (summary) {
      if (finish) finish.textContent = 'Gắn vào bài';
      setStepChrome();

      var parts = [];
      if (needsPdfCheck && summary && summary.hasMainPdf) {
        var pdfMsg = MSG_REPLACE_PDF;
        if (summary.pdfFilename) {
          pdfMsg += ' (hiện tại: ' + summary.pdfFilename + ')';
        }
        parts.push(pdfMsg);
      }
      if (needsVideoCheck && summary && summary.hasUploadedVideo) {
        parts.push(MSG_REPLACE_VIDEO);
      }

      if (!parts.length) {
        doBindMany();
        return;
      }

      var bodyMsg = parts.join(' ');
      function proceed() { doBindMany(); }
      if (window.UlpModal && typeof window.UlpModal.confirm === 'function') {
        window.UlpModal.confirm({
          title: 'Thay thế nội dung?',
          body: bodyMsg,
          confirmLabel: 'Thay thế',
          onConfirm: proceed
        });
      } else if (window.confirm(bodyMsg)) {
        proceed();
      }
    });
  }

  function resetCommon() {
    state.open = true;
    state.step = STEP_CLASS;
    state.classId = null;
    state.className = '';
    state.sectionId = null;
    state.sectionTitle = '';
    state.lessonId = null;
    state.lessonTitle = '';
    state.role = null;
    state.classPage = 0;
    state.classTotalPages = 0;
    state.classQ = '';
    state.binding = false;
    state.plan = null;
  }

  function open(asset) {
    if (!asset || !asset.id) return;
    resetCommon();
    state.multi = false;
    state.asset = asset;
    state.assets = [];

    var modal = el('libraryAttachWizard');
    var label = el('libraryAttachAssetLabel');
    var qInput = el('libraryAttachClassQ');
    var title = el('libraryAttachTitle');
    if (title) title.textContent = 'Thêm vào lớp';
    if (label) {
      var kindLabel = asset.kind === 'VIDEO' ? 'Video' : 'Tài liệu';
      label.textContent = (asset.title || asset.filename || ('#' + asset.id)) +
        ' · ' + kindLabel;
    }
    if (qInput) qInput.value = '';
    if (modal) modal.hidden = false;
    showStep();
  }

  function openMany(assets) {
    var list = (assets || []).filter(function (a) { return a && a.id; });
    if (!list.length) return;
    resetCommon();
    state.multi = true;
    state.asset = null;
    state.assets = list;

    var modal = el('libraryAttachWizard');
    var label = el('libraryAttachAssetLabel');
    var qInput = el('libraryAttachClassQ');
    var title = el('libraryAttachTitle');
    if (title) title.textContent = 'Gắn vào lớp';
    if (label) {
      label.textContent = list.length + ' học liệu đã chọn';
    }
    if (qInput) qInput.value = '';
    if (modal) modal.hidden = false;
    showStep();
  }

  function close() {
    // Ignore close while binding so partial loop is not abandoned mid-flight.
    if (state.binding) return;
    state.open = false;
    state.binding = false;
    state.multi = false;
    state.plan = null;
    var modal = el('libraryAttachWizard');
    if (modal) modal.hidden = true;
    var finish = el('libraryAttachFinish');
    if (finish) finish.textContent = 'Gắn vào bài';
    var title = el('libraryAttachTitle');
    if (title) title.textContent = 'Thêm vào lớp';
    // Restore step 4 label for next single open.
    var step4 = document.querySelector('#libraryAttachSteps li[data-step="4"]');
    if (step4) step4.textContent = 'Vai trò';
  }

  function goNext() {
    if (state.binding || !canAdvance()) return;
    if (state.step === STEP_CLASS) state.step = STEP_SECTION;
    else if (state.step === STEP_SECTION) state.step = STEP_LESSON;
    else if (state.step === STEP_LESSON) state.step = finalStep();
    showStep();
  }

  function goBack() {
    if (state.binding) return;
    if (state.step === STEP_SECTION) {
      state.step = STEP_CLASS;
      state.sectionId = null;
      state.lessonId = null;
      state.role = null;
      state.plan = null;
    } else if (state.step === STEP_LESSON) {
      state.step = STEP_SECTION;
      state.lessonId = null;
      state.role = null;
      state.plan = null;
    } else if (state.step === STEP_ROLE || state.step === STEP_PREVIEW) {
      state.step = STEP_LESSON;
      state.role = null;
      state.plan = null;
    }
    showStep();
  }

  function bindUi() {
    var modal = el('libraryAttachWizard');
    if (!modal) return;

    modal.querySelectorAll('[data-attach-close]').forEach(function (node) {
      node.addEventListener('click', close);
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && state.open) close();
    });

    var next = el('libraryAttachNext');
    var back = el('libraryAttachBack');
    var finish = el('libraryAttachFinish');
    var searchBtn = el('libraryAttachClassSearch');
    var qInput = el('libraryAttachClassQ');

    if (next) next.addEventListener('click', goNext);
    if (back) back.addEventListener('click', goBack);
    if (finish) finish.addEventListener('click', confirmReplaceIfNeededThenBind);
    if (searchBtn) {
      searchBtn.addEventListener('click', function () {
        if (state.binding) return;
        state.classQ = (qInput && qInput.value) || '';
        state.classPage = 0;
        state.classId = null;
        loadClasses();
      });
    }
    if (qInput) {
      qInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
          e.preventDefault();
          if (searchBtn) searchBtn.click();
        }
      });
    }

    document.querySelectorAll('[data-action="attach-to-class"]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var dd = btn.closest('.dropdown');
        if (dd) dd.classList.remove('open');
        open({
          id: btn.getAttribute('data-asset-id'),
          title: btn.getAttribute('data-asset-title') || '',
          kind: btn.getAttribute('data-asset-kind') || '',
          mime: btn.getAttribute('data-asset-mime') || '',
          filename: btn.getAttribute('data-asset-filename') || ''
        });
      });
    });
  }

  function ready(fn) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', fn);
    } else {
      fn();
    }
  }

  window.LibraryAttachWizard = {
    open: open,
    openMany: openMany
  };

  ready(bindUi);
})();
