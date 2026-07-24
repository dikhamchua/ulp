/* Question bank category detail — bulk selection UX + per-row view modal.
   Wires the select-all header, per-row checkboxes, live count and the
   show/hide of the sticky bulk toolbar, plus a client-side detail modal
   populated from each row's hidden <template>. No inline handlers. */
(function () {
  'use strict';
  var table = document.querySelector('[data-bulk-table]');
  if (!table) {
    return;
  }
  var selectAll = table.querySelector('[data-bulk-select-all]');
  var toolbar = document.querySelector('.qb-bulk-toolbar');
  var countEl = document.querySelector('[data-bulk-count]');

  function rowChecks() {
    return Array.prototype.slice.call(table.querySelectorAll('.qb-row-check'));
  }

  function refresh() {
    var checks = rowChecks();
    var checked = checks.filter(function (c) { return c.checked; }).length;
    if (countEl) {
      countEl.textContent = String(checked);
    }
    if (toolbar) {
      toolbar.hidden = checked === 0;
    }
    if (selectAll) {
      // Header reflects all/none/partial selection state.
      selectAll.checked = checked > 0 && checked === checks.length;
      selectAll.indeterminate = checked > 0 && checked < checks.length;
    }
  }

  if (selectAll) {
    selectAll.addEventListener('change', function () {
      rowChecks().forEach(function (c) { c.checked = selectAll.checked; });
      refresh();
    });
  }

  table.addEventListener('change', function (event) {
    if (event.target && event.target.classList.contains('qb-row-check')) {
      refresh();
    }
  });

  refresh();

  /* ── View modal ─────────────────────────────────────────────────── */
  var modal = document.getElementById('qbViewModal');
  if (!modal) {
    return;
  }
  var el = {
    type: document.getElementById('qbModalType'),
    status: document.getElementById('qbModalStatus'),
    contributor: document.getElementById('qbModalContributor'),
    content: document.getElementById('qbModalContent'),
    options: document.getElementById('qbModalOptions'),
    explWrap: document.getElementById('qbModalExplanationWrap'),
    expl: document.getElementById('qbModalExplanation'),
    noteWrap: document.getElementById('qbModalNoteWrap'),
    note: document.getElementById('qbModalNote')
  };

  // Footer action buttons: id → the single-row form prefix they submit into.
  var actions = [
    { btn: document.getElementById('qbModalApprove'), form: 'rowApprove_', flag: 'data-reviewable' },
    { btn: document.getElementById('qbModalReject'), form: 'rowReject_', flag: 'data-reviewable' },
    { btn: document.getElementById('qbModalArchive'), form: 'rowArchive_', flag: 'data-archivable' },
    { btn: document.getElementById('qbModalUnarchive'), form: 'rowUnarchive_', flag: 'data-unarchivable' }
  ];

  // Point each footer button at the viewed row's form and show it only when the
  // row supports that action (flags carried on the "Chi tiết" button).
  function wireActions(itemId, viewBtn) {
    actions.forEach(function (action) {
      if (!action.btn) {
        return;
      }
      var allowed = viewBtn && viewBtn.getAttribute(action.flag) === 'true';
      action.btn.hidden = !allowed;
      if (allowed) {
        action.btn.setAttribute('form', action.form + itemId);
      } else {
        action.btn.removeAttribute('form');
      }
    });
  }

  function detailTemplate(itemId) {
    return document.querySelector('template[data-detail-for="' + itemId + '"]');
  }

  function pick(fragment, selector) {
    return fragment.querySelector(selector);
  }

  function openModal(itemId, viewBtn) {
    var tpl = detailTemplate(itemId);
    if (!tpl) {
      return;
    }
    wireActions(itemId, viewBtn);
    var frag = tpl.content.cloneNode(true);
    // Status pill styling mirrors the row (qb-status-<lowercase>).
    el.type.textContent = tpl.getAttribute('data-type') || '';
    var status = tpl.getAttribute('data-status') || '';
    el.status.textContent = status;
    el.status.className = 'status-pill qb-status-' + status.toLowerCase();
    el.contributor.textContent = tpl.getAttribute('data-contributor') || '';

    var contentSrc = pick(frag, '[data-detail-content]');
    el.content.innerHTML = contentSrc ? contentSrc.innerHTML : '';

    var optionsSrc = pick(frag, '[data-detail-options]');
    el.options.innerHTML = optionsSrc ? optionsSrc.innerHTML : '';

    var explSrc = pick(frag, '[data-detail-explanation]');
    if (explSrc) {
      el.expl.innerHTML = explSrc.innerHTML;
      el.explWrap.hidden = false;
    } else {
      el.explWrap.hidden = true;
    }

    var noteSrc = pick(frag, '[data-detail-note]');
    if (noteSrc) {
      el.note.textContent = noteSrc.textContent;
      el.noteWrap.hidden = false;
    } else {
      el.noteWrap.hidden = true;
    }

    modal.hidden = false;
    document.body.classList.add('qb-modal-open');
  }

  function closeModal() {
    modal.hidden = true;
    document.body.classList.remove('qb-modal-open');
  }

  // Delegate "Xem" clicks so newly rendered rows are covered.
  document.addEventListener('click', function (event) {
    var viewBtn = event.target.closest('.qb-view-btn');
    if (viewBtn) {
      openModal(viewBtn.getAttribute('data-view-target'), viewBtn);
      return;
    }
    if (event.target.closest('[data-modal-close]')) {
      closeModal();
    }
  });

  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape' && !modal.hidden) {
      closeModal();
    }
  });
})();
