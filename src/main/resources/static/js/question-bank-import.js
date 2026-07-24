(function () {
  'use strict';

  var fileInput = document.getElementById('qb-import-file');
  var panel = document.getElementById('qb-import-panel');
  if (!fileInput || !panel || !window.fetch || !window.UlpToast) {
    return;
  }

  var summary = document.getElementById('qb-import-summary');
  var loading = document.getElementById('qb-import-loading');
  var errorBox = document.getElementById('qb-import-error');
  var tableWrap = document.getElementById('qb-import-table-wrap');
  var rowsBody = document.getElementById('qb-import-rows');
  var confirmButton = document.getElementById('qb-import-confirm');
  var resetButton = document.getElementById('qb-import-reset');
  var preview = null;
  var csrfToken = document.querySelector('meta[name="_csrf"]');
  var csrfHeader = document.querySelector('meta[name="_csrf_header"]');
  var IMPORT_BASE = '/lecturer/question-bank/import';

  fileInput.addEventListener('change', function () {
    if (!fileInput.files || !fileInput.files[0]) {
      return;
    }
    uploadPreview(fileInput.files[0]);
  });

  resetButton.addEventListener('click', resetState);
  confirmButton.addEventListener('click', confirmImport);

  function resetState() {
    fileInput.value = '';
    preview = null;
    confirmButton.disabled = true;
    panel.hidden = true;
    loading.hidden = true;
    errorBox.hidden = true;
    tableWrap.hidden = true;
    summary.innerHTML = '';
    rowsBody.innerHTML = '';
  }

  function uploadPreview(file) {
    var formData = new FormData();
    formData.append('file', file);
    panel.hidden = false;
    loading.hidden = false;
    errorBox.hidden = true;
    tableWrap.hidden = true;
    confirmButton.disabled = true;
    summary.innerHTML = '';
    rowsBody.innerHTML = '';

    fetch(IMPORT_BASE + '/preview', {
      method: 'POST',
      body: formData,
      credentials: 'same-origin'
    }).then(readJson)
      .then(function (result) {
        loading.hidden = true;
        if (!result.ok) {
          throw new Error(result.error || 'Không thể xem trước file import');
        }
        preview = result.data;
        renderPreview(result.data);
      })
      .catch(function (error) {
        preview = null;
        confirmButton.disabled = true;
        errorBox.hidden = false;
        errorBox.textContent = error.message || 'Không thể xem trước file import';
        window.UlpToast.error(errorBox.textContent);
      });
  }

  function renderPreview(data) {
    summary.innerHTML = [
      badge('Tổng dòng: ' + data.totalRows, ''),
      badge('Hợp lệ: ' + data.acceptedRows, 'is-ready'),
      badge('Lỗi chặn: ' + data.errorRows, data.errorRows > 0 ? 'is-error' : ''),
      badge(data.confirmable ? 'Có thể xác nhận import' : 'Cần sửa file trước khi xác nhận', data.confirmable ? 'is-ready' : 'is-error')
    ].join('');

    rowsBody.innerHTML = (data.rows || []).map(function (row) {
      return '<tr>'
        + cell(row.rowNumber)
        + cell(row.categoryName || '—')
        + cell(row.questionType || '—')
        + cell(row.contentPreview || '—')
        + cell(row.optionCount)
        + cell(row.correctCount)
        + cell('<div class="qb-import-message"><span class="status-pill ' + (row.blocking ? 'qb-status-rejected' : 'qb-status-approved') + '">' + escapeHtml(row.status) + '</span><div>' + escapeHtml(row.message) + '</div></div>', true)
        + '</tr>';
    }).join('');

    tableWrap.hidden = false;
    confirmButton.disabled = !data.confirmable;
  }

  function confirmImport() {
    if (!preview || !preview.sessionId) {
      return;
    }
    confirmButton.disabled = true;
    var headers = { 'Content-Type': 'application/json' };
    if (csrfToken && csrfHeader) {
      headers[csrfHeader.content] = csrfToken.content;
    }
    fetch(IMPORT_BASE + '/confirm', {
      method: 'POST',
      credentials: 'same-origin',
      headers: headers,
      body: JSON.stringify({ sessionId: preview.sessionId })
    }).then(readJson)
      .then(function (result) {
        if (!result.ok) {
          throw new Error(result.error || 'Không thể xác nhận import');
        }
        window.UlpToast.success('Đã import ' + result.data.createdCount + ' câu hỏi vào ngân hàng bộ môn');
        window.location.reload();
      })
      .catch(function (error) {
        confirmButton.disabled = false;
        window.UlpToast.error(error.message || 'Không thể xác nhận import');
      });
  }

  function readJson(response) {
    return response.text().then(function (text) {
      var data = text ? JSON.parse(text) : {};
      return {
        ok: response.ok,
        data: data,
        error: data && data.error
      };
    });
  }

  function badge(text, cls) {
    return '<span class="qb-import-badge ' + cls + '">' + escapeHtml(text) + '</span>';
  }

  function cell(value, raw) {
    var text = value == null ? '—' : String(value);
    return '<td>' + (raw ? text : escapeHtml(text)) + '</td>';
  }

  function escapeHtml(value) {
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }
})();
