/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Shared client-side behavior (vanilla JS, no framework)
   - Dropdown toggle (click trigger → open/close menu, close-on-outside-click)
   - Tab switching
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // ── Dropdown toggle ────────────────────────────────────────────────
  document.addEventListener('click', function (e) {
    // Close any open dropdown when clicking outside
    document.querySelectorAll('.open').forEach(function (el) {
      if (!el.contains(e.target)) {
        el.classList.remove('open');
      }
    });
  });

  document.querySelectorAll('[data-toggle="dropdown"]').forEach(function (trigger) {
    trigger.addEventListener('click', function (e) {
      e.stopPropagation();
      var parent = this.closest('.dropdown') || this.parentElement;
      if (parent) {
        // Close all other dropdowns
        document.querySelectorAll('.dropdown.open').forEach(function (d) {
          if (d !== parent) d.classList.remove('open');
        });
        parent.classList.toggle('open');
      }
    });
  });

  // ── Tab switching ──────────────────────────────────────────────────
  document.querySelectorAll('[data-tab]').forEach(function (tab) {
    tab.addEventListener('click', function () {
      var group = this.closest('[data-tab-group]');
      if (!group) return;

      var tabName = this.getAttribute('data-tab');

      // Deactivate all tabs in group
      group.querySelectorAll('[data-tab].active').forEach(function (t) {
        t.classList.remove('active');
      });

      // Activate clicked tab
      this.classList.add('active');

      // Show matching panel, hide others
      group.querySelectorAll('[data-tab-panel]').forEach(function (panel) {
        if (panel.getAttribute('data-tab-panel') === tabName) {
          panel.style.display = '';
        } else {
          panel.style.display = 'none';
        }
      });
    });
  });

})();
