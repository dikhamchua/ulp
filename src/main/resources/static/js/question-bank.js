/* Question bank screens — subject → chapter dependent dropdowns. */
(function () {
  'use strict';

  // NOTE: Do NOT drain the flash payload here. notifications.js (loaded by
  // fragments/app-header.html) is the single owner of the flash→toast drain.
  // A second drain in a page script fires a duplicate toast — see
  // .claude/rules/flash-toast-drain.md

  function chapterOptions(subjectId) {
    var all = window.QbChaptersBySubject || {};
    var list = subjectId ? (all[String(subjectId)] || []) : [];
    return list;
  }

  function populateChapterSelect(subjectSelect, chapterSelect, selectedChapterId) {
    if (!subjectSelect || !chapterSelect) {
      return;
    }
    var emptyLabel = chapterSelect.getAttribute('data-empty-label') || '— Không chọn chương —';
    chapterSelect.innerHTML = '';
    var empty = document.createElement('option');
    empty.value = '';
    empty.textContent = emptyLabel;
    chapterSelect.appendChild(empty);
    chapterOptions(subjectSelect.value).forEach(function (chapter) {
      var option = document.createElement('option');
      option.value = String(chapter.id);
      option.textContent = chapter.title;
      chapterSelect.appendChild(option);
    });
    if (selectedChapterId) {
      chapterSelect.value = String(selectedChapterId);
    }
  }

  function bindSubjectChapter() {
    Array.prototype.forEach.call(document.querySelectorAll('[data-qb-subject-select]'), function (subjectSelect) {
      var form = subjectSelect.closest('form');
      var chapterSelect = form ? form.querySelector('[data-qb-chapter-select]') : null;
      if (!chapterSelect) {
        return;
      }
      var selectedChapterId = chapterSelect.getAttribute('data-selected-chapter-id');
      populateChapterSelect(subjectSelect, chapterSelect, selectedChapterId);
      subjectSelect.addEventListener('change', function () {
        // Clear the chapter when the subject changes (a chapter is subject-scoped).
        populateChapterSelect(subjectSelect, chapterSelect, null);
      });
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bindSubjectChapter);
  } else {
    bindSubjectChapter();
  }
})();
