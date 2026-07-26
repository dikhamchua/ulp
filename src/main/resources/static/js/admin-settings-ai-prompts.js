/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Admin AI System Prompt catalog

   Only one interaction lives here: the delete confirmation dialog on
   /admin/settings/ai/prompts. Unlike the provider screen there is no secret to
   reveal or copy, and no connection to test — a prompt is plain text that is
   rendered straight into the page.

   Flash messages are drained into toasts by admin.js via #flash-data.
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  var BASE = '/admin/settings/ai/prompts';

  document.addEventListener('DOMContentLoaded', function () {
    var liveRegion = document.getElementById('aiPromptActionResult');
    var modal = document.getElementById('aiPromptDeleteModal');
    var deleteForm = document.getElementById('aiPromptDeleteForm');
    var deleteName = document.getElementById('aiPromptDeleteName');
    var deleteCancel = document.getElementById('aiPromptDeleteCancel');
    var lastFocused = null;

    /** Announces a message to screen readers; clear-then-set retriggers SR. */
    function announce(message) {
      if (!liveRegion) return;
      liveRegion.textContent = '';
      setTimeout(function () { liveRegion.textContent = message; }, 50);
    }

    function closeModal() {
      if (!modal) return;
      modal.hidden = true;
      if (lastFocused) lastFocused.focus();
    }

    if (!modal || !deleteForm) return;

    document.querySelectorAll('.js-ai-prompt-delete').forEach(function (btn) {
      btn.addEventListener('click', function () {
        lastFocused = btn;
        var name = btn.getAttribute('data-name') || '';
        deleteForm.setAttribute('action', BASE + '/' + btn.getAttribute('data-id') + '/delete');
        if (deleteName) deleteName.textContent = name;
        modal.hidden = false;
        if (deleteCancel) deleteCancel.focus();
        announce('Xác nhận xoá prompt ' + name);
      });
    });

    if (deleteCancel) deleteCancel.addEventListener('click', closeModal);

    // Click on the backdrop (not the box) dismisses.
    modal.addEventListener('click', function (e) {
      if (e.target === modal) closeModal();
    });

    document.addEventListener('keydown', function (e) {
      if (modal.hidden) return;
      if (e.key === 'Escape') {
        closeModal();
        return;
      }
      if (e.key !== 'Tab') return;

      // Trap focus inside the dialog while it is open.
      var focusable = modal.querySelectorAll('button, [href], input, select, textarea');
      if (!focusable.length) return;
      var first = focusable[0];
      var last = focusable[focusable.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    });
  });
})();
