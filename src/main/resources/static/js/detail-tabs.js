/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Shared AJAX detail tabs
   ----------------------------------------------------------------------------
   Progressive enhancement for any detail page that has:
     - nav.detail-tabs with a.detail-tab links (?tab=…)
     - #tabPanel wrapping the active tab body

   On tab click (or in-panel .page-link click) the orchestrator fetches the
   same URL, lifts #tabPanel from the HTML response, and swaps it in place —
   no full-page reload. A loading spinner is shown while the request is in
   flight. History is updated with pushState so Back/Forward keep working.

   With JS off, every tab/pager link is a normal navigation.

   Optional hooks (page scripts may assign these before or after load):
     window.UlpDetailTabs.onBeforeSwap()  — teardown outgoing tab (timers…)
     window.UlpDetailTabs.onAfterSwap(panel, tab) — remount incoming tab JS
     window.UlpDetailTabs.saveButtonSelector — default '.toolbar-save'
     window.UlpDetailTabs.infoTabName — default 'info' (save enabled only here)

   Requires nothing else. Safe to include on pages without tabs (no-ops).
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  var LOADING_HTML =
    '<div class="detail-tab-loading" role="status" aria-live="polite">' +
      '<span class="detail-tab-spinner" aria-hidden="true"></span>' +
      '<span class="detail-tab-loading-text">Đang tải…</span>' +
    '</div>';

  function ready(fn) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', fn);
    } else {
      fn();
    }
  }

  /** Reads the ?tab= value of a URL, defaulting to 'info'. */
  function tabOf(url) {
    try {
      var u = new URL(url, window.location.origin);
      return u.searchParams.get('tab') || 'info';
    } catch (e) {
      return 'info';
    }
  }

  function api() {
    if (!window.UlpDetailTabs) window.UlpDetailTabs = {};
    return window.UlpDetailTabs;
  }

  ready(function () {
    var panel = document.getElementById('tabPanel');
    var tabsNav = document.querySelector('.detail-tabs');
    if (!panel || !tabsNav) return;

    // Already claimed by a page-specific orchestrator (e.g. test-detail-tabs).
    if (tabsNav.getAttribute('data-ajax-tabs') === 'owned') return;
    tabsNav.setAttribute('data-ajax-tabs', 'on');

    var hooks = api();
    var saveSelector = hooks.saveButtonSelector || '.toolbar-save';
    var infoTab = hooks.infoTabName || 'info';
    var navigating = false;

    function saveButtons() {
      return document.querySelectorAll(saveSelector);
    }

    /** Reflects the active tab in the nav + toolbar Save button(s). */
    function syncChrome(tab) {
      tabsNav.querySelectorAll('a.detail-tab').forEach(function (a) {
        var href = a.getAttribute('href');
        var isActive = href && tabOf(href) === tab;
        a.classList.toggle('active', isActive);
        a.setAttribute('aria-selected', isActive ? 'true' : 'false');
      });
      saveButtons().forEach(function (btn) {
        btn.disabled = tab !== infoTab;
      });
    }

    function showLoading() {
      panel.classList.add('is-loading');
      panel.setAttribute('aria-busy', 'true');
      // Keep min-height so the layout doesn't jump while spinner shows.
      if (!panel.style.minHeight) {
        panel.style.minHeight = Math.max(panel.offsetHeight, 120) + 'px';
      }
      panel.innerHTML = LOADING_HTML;
    }

    function clearLoadingState() {
      panel.classList.remove('is-loading');
      panel.removeAttribute('aria-busy');
      panel.style.minHeight = '';
    }

    /**
     * Fetches `url`, swaps #tabPanel in place. Falls back to a full navigation
     * when the response has no #tabPanel (auth redirect, error page, …).
     */
    function navigate(url, push) {
      if (navigating) return;
      navigating = true;

      if (typeof hooks.onBeforeSwap === 'function') {
        try { hooks.onBeforeSwap(); } catch (e) { /* ignore teardown errors */ }
      }

      showLoading();

      fetch(url, {
        headers: { 'X-Requested-With': 'XMLHttpRequest', 'Accept': 'text/html' },
        credentials: 'same-origin'
      })
        .then(function (r) {
          if (!r.ok) throw new Error('HTTP ' + r.status);
          // Auth middleware may redirect to /login — treat as hard failure.
          if (r.redirected && r.url && r.url.indexOf('/login') !== -1) {
            throw new Error('auth redirect');
          }
          return r.text();
        })
        .then(function (html) {
          var doc = new DOMParser().parseFromString(html, 'text/html');
          var fresh = doc.getElementById('tabPanel');
          if (!fresh) throw new Error('no #tabPanel in response');

          clearLoadingState();
          panel.innerHTML = fresh.innerHTML;

          var tab = tabOf(url);
          syncChrome(tab);

          var title = doc.querySelector('title');
          if (title) document.title = title.textContent;

          if (push) {
            window.history.pushState({ tab: tab }, '', url);
          }

          if (typeof hooks.onAfterSwap === 'function') {
            try { hooks.onAfterSwap(panel, tab); } catch (e) { /* ignore */ }
          }

          // Notify listeners (permission toggles, copy buttons, …).
          document.dispatchEvent(new CustomEvent('ulp:detail-tab-loaded', {
            detail: { panel: panel, tab: tab, url: url }
          }));

          navigating = false;
        })
        .catch(function () {
          // Non-recoverable in-place: hand off to a real navigation.
          window.location.href = url;
        });
    }

    // ── Tab links + in-panel pager links ────────────────────────────────
    document.addEventListener('click', function (e) {
      var link = e.target.closest ? e.target.closest('a') : null;
      if (!link) return;
      var href = link.getAttribute('href');
      if (!href || href.charAt(0) === '#') return;

      var isTab = tabsNav.contains(link) && link.classList.contains('detail-tab');
      var isPanelPager = panel.contains(link) &&
        (link.classList.contains('page-link') || link.closest('.detail-pagination'));
      if (!isTab && !isPanelPager) return;

      // Let modified clicks (new tab) behave natively.
      if (e.defaultPrevented || e.button !== 0 ||
          e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;

      // Same tab already active and no page change — skip the round-trip.
      if (isTab && link.classList.contains('active') && tabOf(href) === tabOf(window.location.href)) {
        var sameUrl;
        try {
          sameUrl = new URL(href, window.location.origin).href === window.location.href;
        } catch (err) {
          sameUrl = false;
        }
        if (sameUrl) {
          e.preventDefault();
          return;
        }
      }

      e.preventDefault();
      navigate(href, true);
    });

    // ── Back / forward ──────────────────────────────────────────────────
    window.addEventListener('popstate', function () {
      navigate(window.location.href, false);
    });

    // Seed history state so the first Back doesn't leave the page blank.
    try {
      var initialTab = tabOf(window.location.href);
      if (!window.history.state || !window.history.state.tab) {
        window.history.replaceState({ tab: initialTab }, '', window.location.href);
      }
      syncChrome(initialTab);
    } catch (e) {
      // ignore
    }
  });
})();
