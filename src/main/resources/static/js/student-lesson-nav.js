/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Student class-lessons sidebar nav
   ----------------------------------------------------------------------------
   Wires the "Rời khỏi lớp này" button in the left class-nav sidebar. Mirrors
   the leave flow on my-classes.html: confirm modal → submit the hidden POST
   form (Thymeleaf injects CSRF). Falls back to a bare submit when the modal
   helper is unavailable.
   ══════════════════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  document.addEventListener('click', function (e) {
    var trigger = e.target.closest('[data-action="leave-class"]');
    if (!trigger) return;
    e.preventDefault();

    var classId = trigger.dataset.classId;
    var className = trigger.dataset.className || 'này';
    var form = document.getElementById('leave-form-' + classId);
    if (!form) return;

    // No modal helper loaded → submit directly rather than trap the user.
    if (!window.UlpModal || !window.UlpModal.confirm) {
      form.submit();
      return;
    }
    window.UlpModal.confirm({
      title: 'Rời lớp học',
      body: 'Bạn có chắc muốn rời lớp ' + className + '?',
      confirmLabel: 'Rời lớp',
      onConfirm: function () { form.submit(); }
    });
  });
})();
