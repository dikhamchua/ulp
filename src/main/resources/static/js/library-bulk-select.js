/* Library bulk selection — checkboxes + action bar.
 * Opens multi-mode attach wizard via window.LibraryAttachWizard.openMany.
 */
(function () {
  'use strict';

  var MAX_SELECT = 20;
  var MSG_MAX = 'Chỉ chọn tối đa ' + MAX_SELECT + ' học liệu mỗi lần.';

  /** @type {Map<string, object>} */
  var selected = new Map();

  function toast(kind, message) {
    if (window.UlpToast && typeof window.UlpToast[kind] === 'function') {
      window.UlpToast[kind](message);
    }
  }

  function el(id) {
    return document.getElementById(id);
  }

  function assetFromCheckbox(cb) {
    return {
      id: cb.getAttribute('data-asset-id'),
      title: cb.getAttribute('data-asset-title') || '',
      kind: cb.getAttribute('data-asset-kind') || '',
      mime: cb.getAttribute('data-asset-mime') || '',
      filename: cb.getAttribute('data-asset-filename') || ''
    };
  }

  function rowForCheckbox(cb) {
    return cb.closest('.asset-row');
  }

  function syncRowClass(cb) {
    var row = rowForCheckbox(cb);
    if (!row) return;
    row.classList.toggle('is-selected', !!cb.checked);
  }

  function updateBar() {
    var bar = el('librarySelectionBar');
    var countEl = el('librarySelectionCount');
    var n = selected.size;
    if (countEl) {
      countEl.textContent = 'Đã chọn ' + n + ' mục';
    }
    if (bar) {
      bar.hidden = n === 0;
    }
    syncSelectAllState();
  }

  function syncSelectAllState() {
    var all = el('librarySelectAll');
    if (!all) return;
    var boxes = document.querySelectorAll('.library-row-check');
    if (!boxes.length) {
      all.checked = false;
      all.indeterminate = false;
      return;
    }
    var checked = 0;
    boxes.forEach(function (cb) {
      if (cb.checked) checked += 1;
    });
    all.checked = checked === boxes.length && checked > 0;
    all.indeterminate = checked > 0 && checked < boxes.length;
  }

  function setSelected(asset, on) {
    if (!asset || !asset.id) return false;
    var key = String(asset.id);
    if (on) {
      if (selected.has(key)) return true;
      if (selected.size >= MAX_SELECT) {
        toast('error', MSG_MAX);
        return false;
      }
      selected.set(key, asset);
      return true;
    }
    selected.delete(key);
    return true;
  }

  function onRowCheckChange(cb) {
    var asset = assetFromCheckbox(cb);
    if (cb.checked) {
      if (!setSelected(asset, true)) {
        cb.checked = false;
        syncRowClass(cb);
        updateBar();
        return;
      }
    } else {
      setSelected(asset, false);
    }
    syncRowClass(cb);
    updateBar();
  }

  function onSelectAllChange(allCb) {
    var boxes = document.querySelectorAll('.library-row-check');
    if (!allCb.checked) {
      boxes.forEach(function (cb) {
        cb.checked = false;
        setSelected(assetFromCheckbox(cb), false);
        syncRowClass(cb);
      });
      updateBar();
      return;
    }

    // Select current page only; stop at max and leave remaining unchecked.
    var blocked = false;
    boxes.forEach(function (cb) {
      if (blocked) {
        cb.checked = false;
        syncRowClass(cb);
        return;
      }
      var asset = assetFromCheckbox(cb);
      var key = String(asset.id);
      if (selected.has(key)) {
        cb.checked = true;
        syncRowClass(cb);
        return;
      }
      if (!setSelected(asset, true)) {
        blocked = true;
        cb.checked = false;
        syncRowClass(cb);
        return;
      }
      cb.checked = true;
      syncRowClass(cb);
    });
    updateBar();
  }

  function clear() {
    selected.clear();
    document.querySelectorAll('.library-row-check').forEach(function (cb) {
      cb.checked = false;
      syncRowClass(cb);
    });
    var all = el('librarySelectAll');
    if (all) {
      all.checked = false;
      all.indeterminate = false;
    }
    updateBar();
  }

  function openAttach() {
    if (!selected.size) return;
    if (!window.LibraryAttachWizard || typeof window.LibraryAttachWizard.openMany !== 'function') {
      toast('error', 'Không mở được wizard gắn vào lớp.');
      return;
    }
    // Preserve insertion order from Map (selection order).
    var assets = Array.from(selected.values());
    window.LibraryAttachWizard.openMany(assets);
  }

  function bindUi() {
    var list = document.querySelector('.asset-list');
    if (!list) return;

    list.addEventListener('change', function (e) {
      var t = e.target;
      if (!t || !t.classList || !t.classList.contains('library-row-check')) return;
      onRowCheckChange(t);
    });

    var all = el('librarySelectAll');
    if (all) {
      all.addEventListener('change', function () {
        onSelectAllChange(all);
      });
    }

    var clearBtn = el('librarySelectionClear');
    if (clearBtn) clearBtn.addEventListener('click', clear);

    var attachBtn = el('librarySelectionAttach');
    if (attachBtn) attachBtn.addEventListener('click', openAttach);

    // Hydrate checked state if any (e.g. bfcache); start clean.
    clear();
  }

  function ready(fn) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', fn);
    } else {
      fn();
    }
  }

  window.LibraryBulkSelect = {
    clear: clear,
    size: function () { return selected.size; }
  };

  ready(bindUi);
})();
