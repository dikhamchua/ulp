/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Admin pages behavior (dashboard chart, etc.)
   Loaded by /admin/*. Requires app.js + Chart.js.
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // Flash toasts are drained once by notifications.js (app-header, defer).
  // Do not drain here — a second pass caused duplicate toasts on admin pages.

  // ── Donut chart for users-by-role ─────────────────────────────────
  // Reads data-labels|data-values|data-colors from #roleChart canvas.
  // Pipe-separated to safely encode Vietnamese labels with commas.
  var canvas = document.getElementById('roleChart');
  if (!canvas || typeof window.Chart === 'undefined') return;

  var labels = (canvas.dataset.labels || '').split('|').filter(Boolean);
  var values = (canvas.dataset.values || '').split(',').filter(Boolean).map(Number);
  var colors = (canvas.dataset.colors || '').split('|').filter(Boolean);
  if (labels.length === 0 || values.length === 0) return;

  new window.Chart(canvas.getContext('2d'), {
    type: 'doughnut',
    data: {
      labels: labels,
      datasets: [{
        data: values,
        backgroundColor: colors,
        borderWidth: 2,
        borderColor: '#FFFFFF'
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      cutout: '62%',
      plugins: {
        legend: { display: false },
        tooltip: {
          backgroundColor: '#202227',
          padding: 10,
          titleFont: { family: 'Inter', weight: '700' },
          bodyFont: { family: 'Inter' }
        }
      }
    }
  });

})();
