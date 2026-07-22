/* Lesson bulk selection on library "Bài giảng" rail.
 * Checkboxes + action bar → LessonCloneWizard bulk open.
 */
(function () {
  'use strict';

  var MAX_SELECT = 20;
  var MSG_MAX = 'Chỉ chọn tối đa ' + MAX_SELECT + ' bài giảng mỗi lần.';

  /** @type {Map<string, {title:string,cloneUrl:string}>} */
  var selected = new Map();

  function toast(kind, message) {
    if (window.UlpToast && typeof window.UlpToast[kind] === 'function') {
      window.UlpToast[kind](message);
    }
  }

  function el(id) {
    return document.getElementById(id);
  }

  function lessonFromCheckbox(cb) {
    return {
      id: cb.getAttribute('data-lesson-id'),
      title: cb.getAttribute('data-lesson-title') || '',
      cloneUrl: cb.getAttribute('data-clone-url') || ''
    };
  }

  function rowForCheckbox(cb) {
    return cb.closest('.lesson-row') || cb.closest('.asset-row');
  }

  function syncRowClass(cb) {
    var row = rowForCheckbox(cb);
    if (!row) return;
    row.classList.toggle('is-selected', !!cb.checked);
  }

  function updateBar() {
    var bar = el('lessonSelectionBar');
    var countEl = el('lessonSelectionCount');
    var n = selected.size;
    if (countEl) {
      countEl.textContent = 'Đã chọn ' + n + ' bài';
    }
    if (bar) {
      bar.hidden = n === 0;
    }
    syncSelectAllState();
  }

  function syncSelectAllState() {
    var all = el('lessonSelectAll');
    if (!all) return;
    var boxes = document.querySelectorAll('.lesson-row-check');
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

  function setSelected(lesson, on) {
    if (!lesson || !lesson.id || !lesson.cloneUrl) return false;
    var key = String(lesson.id);
    if (on) {
      if (selected.has(key)) return true;
      if (selected.size >= MAX_SELECT) {
        toast('error', MSG_MAX);
        return false;
      }
      selected.set(key, { title: lesson.title, cloneUrl: lesson.cloneUrl });
      return true;
    }
    selected.delete(key);
    return true;
  }

  function onRowCheckChange(cb) {
    var lesson = lessonFromCheckbox(cb);
    if (cb.checked) {
      if (!setSelected(lesson, true)) {
        cb.checked = false;
        syncRowClass(cb);
        updateBar();
        return;
      }
    } else {
      setSelected(lesson, false);
    }
    syncRowClass(cb);
    updateBar();
  }

  function onSelectAllChange(allCb) {
    var boxes = document.querySelectorAll('.lesson-row-check');
    if (!allCb.checked) {
      boxes.forEach(function (cb) {
        cb.checked = false;
        setSelected(lessonFromCheckbox(cb), false);
        syncRowClass(cb);
      });
      updateBar();
      return;
    }

    // Page-local select; stop at max and leave remaining unchecked.
    var blocked = false;
    boxes.forEach(function (cb) {
      if (blocked) {
        cb.checked = false;
        syncRowClass(cb);
        return;
      }
      var lesson = lessonFromCheckbox(cb);
      var key = String(lesson.id);
      if (selected.has(key)) {
        cb.checked = true;
        syncRowClass(cb);
        return;
      }
      if (!setSelected(lesson, true)) {
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
    document.querySelectorAll('.lesson-row-check').forEach(function (cb) {
      cb.checked = false;
      syncRowClass(cb);
    });
    var all = el('lessonSelectAll');
    if (all) {
      all.checked = false;
      all.indeterminate = false;
    }
    updateBar();
  }

  function openBulkClone() {
    if (!selected.size) return;
    if (!window.LessonCloneWizard || typeof window.LessonCloneWizard.open !== 'function') {
      toast('error', 'Không mở được wizard clone.');
      return;
    }
    var lessons = Array.from(selected.values());
    window.LessonCloneWizard.open({ mode: 'lesson', lessons: lessons });
  }

  function bindUi() {
    var list = document.querySelector('.asset-table:has(.lesson-row) .asset-list')
      || document.querySelector('.lesson-list')
      || document.querySelector('.asset-list');
    // Only bind when lesson checkboxes exist (TEMPLATES tab).
    if (!document.querySelector('.lesson-row-check')) return;

    var table = document.querySelector('.asset-table:has(.lesson-row)');
    if (table) {
      table.addEventListener('change', function (e) {
        var t = e.target;
        if (!t || !t.classList || !t.classList.contains('lesson-row-check')) return;
        onRowCheckChange(t);
      });
    }

    var all = el('lessonSelectAll');
    if (all) {
      all.addEventListener('change', function () {
        onSelectAllChange(all);
      });
    }

    var clearBtn = el('lessonSelectionClear');
    if (clearBtn) clearBtn.addEventListener('click', clear);

    var cloneBtn = el('lessonSelectionClone');
    if (cloneBtn) cloneBtn.addEventListener('click', openBulkClone);

    clear();
  }

  function ready(fn) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', fn);
    } else {
      fn();
    }
  }

  window.LibraryLessonBulkSelect = {
    clear: clear,
    size: function () { return selected.size; }
  };

  ready(bindUi);
})();
