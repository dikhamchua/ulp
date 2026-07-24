/* Question bank screens — auto-submit toggle switches + flash → toast drain. */
(function () {
  'use strict';

  // Flash → toast drain. Lecturer pages (list/detail/form) do not load
  // head-department.js, so the drain must live here too. The one-shot
  // data-flash-drained guard keeps HEAD pages (which load both scripts) from
  // firing the toast twice.
  var flashData = document.getElementById('flash-data');
  if (flashData && !flashData.dataset.flashDrained && window.UlpToast) {
    flashData.dataset.flashDrained = '1';
    if (flashData.dataset.flashSuccess) window.UlpToast.success(flashData.dataset.flashSuccess);
    if (flashData.dataset.flashError) window.UlpToast.error(flashData.dataset.flashError);
  }

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
