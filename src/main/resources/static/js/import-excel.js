/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Import Students from Excel (ULP-3.4)
   Vanilla JS for the 3-step modal on /lecturer/classes/{id}/members.
   Exports a single global: window.openImportExcelModal(opts).
   Markup lives in templates/classes/detail-members.html.
   ══════════════════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var state = {
    classId: null, uploadUrl: null, templateUrl: null,
    sessionId: null, rows: [], filter: 'ALL'
  };

  function el(id) { return document.getElementById(id); }

  function csrfHeaders() {
    var tokenMeta = document.querySelector('meta[name="_csrf"]');
    var headerMeta = document.querySelector('meta[name="_csrf_header"]');
    var headers = {};
    if (tokenMeta && headerMeta && tokenMeta.content && headerMeta.content) {
      headers[headerMeta.content] = tokenMeta.content;
    }
    return headers;
  }

  function showStep(stepName) {
    document.querySelectorAll('#importExcelModal [data-step]').forEach(function (s) {
      s.style.display = s.getAttribute('data-step') === stepName ? '' : 'none';
    });
  }
  function openModal() {
    var modal = el('importExcelModal');
    if (!modal) return;
    if (typeof modal.showModal === 'function') modal.showModal();
    else modal.setAttribute('open', '');
    showStep('step1');
    resetForm();
  }

  function closeModal() {
    var modal = el('importExcelModal');
    if (!modal) return;
    if (typeof modal.close === 'function' && modal.open) modal.close();
    else modal.removeAttribute('open');
  }

  function resetForm() {
    state.sessionId = null; state.rows = []; state.filter = 'ALL';
    var input = el('importExcelFile'); if (input) input.value = '';
    var fileLabel = el('importExcelFileLabel');
    if (fileLabel) fileLabel.textContent = 'Chưa chọn file';
    var skip = el('importExcelSkipErrors'); if (skip) skip.checked = false;
    var btn = el('importExcelUploadBtn'); if (btn) btn.disabled = true;
  }

  // ── Click handlers per button id ────────────────────────────────────
  function onUploadClick() {
    var input = el('importExcelFile');
    var f = input && input.files && input.files[0];
    if (f) doUpload(f);
  }
  function onTemplateClick() {
    if (state.templateUrl) window.location.href = state.templateUrl;
  }
  function onBackClick() { resetForm(); showStep('step1'); }
  function onDoneClick() { closeModal(); window.location.reload(); }

  /**
   * Map of element id → click handler. Building wiring from a single table
   * avoids the per-step boilerplate of the previous bindStep1/2/3 functions.
   */
  var CLICK_HANDLERS = {
    'importExcelUploadBtn':   onUploadClick,
    'importExcelCancelBtn':   closeModal,
    'importExcelTemplateBtn': onTemplateClick,
    'importExcelBackBtn':     onBackClick,
    'importExcelConfirmBtn':  doConfirm,
    'importExcelDoneBtn':     onDoneClick
  };
  function bindAllClicks() {
    Object.keys(CLICK_HANDLERS).forEach(function (id) {
      var elem = el(id);
      if (elem) elem.addEventListener('click', CLICK_HANDLERS[id]);
    });
  }

  // ── Step 1: file input + drag-and-drop ──────────────────────────────
  function bindFileInputAndDropZone() {
    var input = el('importExcelFile');
    var fileLabel = el('importExcelFileLabel');
    var uploadBtn = el('importExcelUploadBtn');
    var dropArea = el('importExcelDropArea');
    if (input) input.addEventListener('change', function () {
      var f = input.files && input.files[0];
      if (fileLabel) fileLabel.textContent = f ? f.name : 'Chưa chọn file';
      if (uploadBtn) uploadBtn.disabled = !f;
    });
    if (!dropArea || !input) return;

    ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(function (ev) {
      dropArea.addEventListener(ev, function (e) {
        e.preventDefault(); e.stopPropagation();
        dropArea.classList.toggle('is-dragover',
            ev === 'dragenter' || ev === 'dragover');
      });
    });
    dropArea.addEventListener('drop', function (e) {
      var files = e.dataTransfer && e.dataTransfer.files;
      if (files && files.length > 0) {
        input.files = files;
        if (fileLabel) fileLabel.textContent = files[0].name;
        if (uploadBtn) uploadBtn.disabled = false;
      }
    });
    dropArea.addEventListener('click', function () { input.click(); });
  }

  // ── Step 2: filter pills (data-filter attribute) ────────────────────
  function bindFilterButtons() {
    var filters = document.querySelectorAll('#importExcelModal [data-filter]');
    filters.forEach(function (btn) {
      btn.addEventListener('click', function () {
        state.filter = btn.getAttribute('data-filter');
        filters.forEach(function (b) { b.classList.toggle('is-active', b === btn); });
        renderRows();
      });
    });
  }

  /**
   * Shared HTTP wrapper. Posts the request, parses JSON, then routes the
   * result through onOk/onFail with consistent toast + button-reset handling.
   * `init` is forwarded directly to fetch() — caller is responsible for
   * headers and body.
   */
  function postAndHandle(url, init, btn, defaultErrorMsg, onOk) {
    fetch(url, init)
      .then(function (res) {
        return res.json().then(function (j) { return { status: res.status, body: j }; });
      })
      .then(function (out) {
        if (out.status !== 200) {
          var msg = (out.body && out.body.error) || defaultErrorMsg;
          if (window.UlpToast) window.UlpToast.error(msg);
          if (btn) btn.disabled = false;
          return;
        }
        onOk(out.body);
      })
      .catch(function (err) {
        console.error(err);
        if (window.UlpToast) window.UlpToast.error('Không kết nối được tới server.');
        if (btn) btn.disabled = false;
      });
  }

  // ── Upload (step 1 → step 2) ────────────────────────────────────────
  function doUpload(file) {
    var uploadBtn = el('importExcelUploadBtn');
    if (uploadBtn) uploadBtn.disabled = true;
    var formData = new FormData(); formData.append('file', file);
    postAndHandle(state.uploadUrl, {
      method: 'POST', headers: csrfHeaders(),
      body: formData, credentials: 'same-origin'
    }, uploadBtn, 'Tải lên thất bại.', function (body) {
      state.sessionId = body.sessionId;
      state.rows = body.rows || [];
      renderPreview(body);
      showStep('step2');
    });
  }

  function renderPreview(payload) {
    setText('importExcelStatTotal', payload.totalRows);
    setText('importExcelStatOk',    payload.okCount);
    setText('importExcelStatWarn',  payload.warningCount);
    setText('importExcelStatErr',   payload.errorCount);

    var skip = el('importExcelSkipErrors');
    var confirmBtn = el('importExcelConfirmBtn');
    var errorCount = payload.errorCount || 0;

    function refreshConfirmState() {
      if (!confirmBtn) return;
      // Confirm is enabled when either there are no errors, or the user
      // explicitly ticked "skip errors".
      var importable = (payload.okCount || 0) > 0;
      confirmBtn.disabled = (errorCount > 0 && (!skip || !skip.checked))
          ? true
          : !importable;
    }
    if (skip) skip.addEventListener('change', refreshConfirmState);
    refreshConfirmState();

    state.filter = 'ALL';
    document.querySelectorAll('#importExcelModal [data-filter]').forEach(function (b) {
      b.classList.toggle('is-active', b.getAttribute('data-filter') === 'ALL');
    });
    renderRows();
  }

  function renderRows() {
    var tbody = el('importExcelTableBody');
    if (!tbody) return;
    tbody.innerHTML = '';
    var rows = state.rows.filter(function (r) {
      if (state.filter === 'ALL') return true;
      if (state.filter === 'ERROR') return r.isError;
      if (state.filter === 'WARNING') return r.isWarning;
      if (state.filter === 'OK') return r.status === 'OK';
      return true;
    });

    if (rows.length === 0) {
      var emptyTr = document.createElement('tr');
      var emptyTd = document.createElement('td');
      emptyTd.colSpan = 6; emptyTd.className = 'iex-empty';
      emptyTd.textContent = 'Không có dòng nào khớp với bộ lọc hiện tại.';
      emptyTr.appendChild(emptyTd); tbody.appendChild(emptyTr);
      return;
    }
    rows.forEach(function (r) {
      var tr = document.createElement('tr');
      tr.className = r.isError ? 'iex-row-error'
          : (r.isWarning ? 'iex-row-warn' : 'iex-row-ok');
      tr.appendChild(td(String(r.rowNumber)));
      var badge = document.createElement('span');
      badge.className = 'iex-badge ' + (r.isError ? 'iex-badge-error'
          : (r.isWarning ? 'iex-badge-warn' : 'iex-badge-ok'));
      badge.textContent = r.statusMessage || r.status || '';
      var statusTd = document.createElement('td'); statusTd.appendChild(badge);
      tr.appendChild(statusTd);
      tr.appendChild(td(r.studentId || '—'));
      tr.appendChild(td(r.email || '—'));
      tr.appendChild(td(r.fullName || '—'));
      var note = r.detail || '';
      var noteTd = td(note);
      if (note) noteTd.setAttribute('title', note);
      tr.appendChild(noteTd);
      tbody.appendChild(tr);
    });
  }

  function td(text) {
    var c = document.createElement('td');
    c.textContent = text == null ? '' : text;
    return c;
  }

  // ── Confirm (step 2 → step 3) ───────────────────────────────────────
  function doConfirm() {
    if (!state.sessionId) return;
    var skip = el('importExcelSkipErrors');
    var skipErrors = skip ? !!skip.checked : false;
    var confirmBtn = el('importExcelConfirmBtn');
    if (confirmBtn) confirmBtn.disabled = true;

    var url = '/lecturer/classes/' + state.classId
      + '/import-students/' + encodeURIComponent(state.sessionId) + '/confirm';
    var headers = Object.assign({ 'Content-Type': 'application/json' }, csrfHeaders());

    postAndHandle(url, {
      method: 'POST', headers: headers, credentials: 'same-origin',
      body: JSON.stringify({ skipErrors: skipErrors })
    }, confirmBtn, 'Import thất bại.', function (body) {
      // Refresh row table first — the server may have mutated row status
      // (e.g. USER_NOT_FOUND when a user vanished between preview and
      // confirm). Re-render before switching step so the back-nav view is
      // correct if the user inspects step 2 later.
      state.rows = (body && body.rows) || state.rows;
      renderRows();
      renderSummary(body);
      showStep('step3');
    });
  }

  function renderSummary(payload) {
    setText('importExcelSumImported', payload.imported);
    setText('importExcelSumReactivated', payload.reactivated);
    setText('importExcelSumSkipped',
      (payload.skippedDuplicate || 0) + (payload.skippedError || 0));
    setText('importExcelSumFailed', payload.failed);
  }

  function setText(id, value) {
    var node = el(id);
    if (node) node.textContent = value == null ? '0' : String(value);
  }

  // ── Public API ──────────────────────────────────────────────────────
  window.openImportExcelModal = function (opts) {
    opts = opts || {};
    state.classId = opts.classId;
    state.uploadUrl = '/lecturer/classes/' + opts.classId + '/import-students/upload';
    state.templateUrl = '/lecturer/classes/' + opts.classId + '/import-students/template';
    openModal();
  };

  document.addEventListener('DOMContentLoaded', function () {
    if (!el('importExcelModal')) return;
    bindAllClicks();
    bindFileInputAndDropZone();
    bindFilterButtons();

    // Event delegation so any element with data-action="open-import-excel"
    // (including SVG children) opens the modal — Element.closest walks the
    // DOM tree.
    document.addEventListener('click', function (event) {
      var trigger = event.target && event.target.closest
          ? event.target.closest('[data-action="open-import-excel"]')
          : null;
      if (!trigger) return;
      event.preventDefault();
      var classId = trigger.getAttribute('data-class-id');
      if (classId) window.openImportExcelModal({ classId: classId });
    });
  });
})();
