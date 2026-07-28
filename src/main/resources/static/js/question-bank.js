/* Question bank screens — auto-submit toggle switches + category modal. */
(function () {
  'use strict';

  // NOTE: Do NOT drain the flash payload here. notifications.js (loaded by
  // fragments/app-header.html) is the single owner of the flash→toast drain.
  // A second drain in a page script fires a duplicate toast — see
  // .claude/rules/flash-toast-drain.md

  // Submit the parent form when an auto-submit switch changes (no inline handler).
  document.addEventListener('change', function (event) {
    var input = event.target;
    if (input && input.matches && input.matches('.qb-switch input[data-autosubmit]')) {
      var form = input.closest('form');
      if (form) {
        form.requestSubmit ? form.requestSubmit() : form.submit();
      }
    }
  });

  /* ── Create/edit category modal ─────────────────────────────────── */
  var categoryModal = document.getElementById('qbCategoryModal');
  if (categoryModal) {
    var openCategoryModal = function () {
      categoryModal.hidden = false;
      document.body.classList.add('qb-modal-open');
      var firstInput = categoryModal.querySelector('input[type="text"], textarea');
      if (firstInput) {
        firstInput.focus();
      }
    };
    var closeCategoryModal = function () {
      categoryModal.hidden = true;
      document.body.classList.remove('qb-modal-open');
    };

    document.addEventListener('click', function (event) {
      if (event.target.closest('[data-qb-category-open]')) {
        openCategoryModal();
        return;
      }
      // Overlay / X / cancel close it; edit-mode "Huỷ sửa" is a real link, left alone.
      if (event.target.closest('[data-qb-category-close]')) {
        closeCategoryModal();
      }
    });

    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && !categoryModal.hidden) {
        closeCategoryModal();
      }
    });

    // Server rendered edit mode or a validation error: open on load.
    if (categoryModal.hasAttribute('data-qb-category-autoopen')) {
      openCategoryModal();
    }
  }
})();
