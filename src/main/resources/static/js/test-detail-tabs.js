/* Exam detail tabs (Epic #11): switch the four tabs (thông tin / theo dõi /
 * bài nộp / lịch sử) in place — no full-page reload. The orchestrator fetches
 * the same ?tab= URL the tab link points at, lifts #tabPanel out of the HTML
 * response, and swaps it into the live DOM. Pager links and the submissions
 * search form inside the panel are intercepted too, so the whole detail screen
 * navigates without a reload.
 *
 * Lifecycle: the monitor tab owns two setInterval timers, so its teardown() is
 * invoked before every swap to avoid leaking a poll loop across tabs. The info
 * builder re-mounts from the #lfData JSON island that travels inside #tabPanel.
 *
 * Progressive enhancement: with JS off, every tab link / pager / search is a
 * plain server-rendered navigation, so the screen still works.
 */
(function () {
    'use strict';

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

    ready(function () {
        var panel = document.getElementById('tabPanel');
        var tabsNav = document.querySelector('.detail-tabs');
        // Create mode (no tabs) or an unexpected DOM: let the per-module
        // DOMContentLoaded mounts handle everything, no orchestration needed.
        if (!panel || !tabsNav) return;

        var saveBtn = document.getElementById('lfSave');
        var monitorTeardown = function () {};

        /** Tears down the outgoing tab, then mounts the incoming one. */
        function remount() {
            if (typeof monitorTeardown === 'function') monitorTeardown();
            if (window.LfForm) window.LfForm.mount();
            monitorTeardown = window.MnMonitor
                ? window.MnMonitor.mount(panel)
                : function () {};
        }

        /** Reflects the active tab in the nav + the toolbar Save button. */
        function syncChrome(tab) {
            tabsNav.querySelectorAll('.detail-tab').forEach(function (a) {
                a.classList.toggle('active', tabOf(a.getAttribute('href')) === tab);
            });
            // Save is form-associated with #lfForm, which only exists on the
            // info tab; disable it elsewhere so it never posts an absent form.
            if (saveBtn) saveBtn.disabled = tab !== 'info';
        }

        var navigating = false;

        /**
         * Fetches `url`, swaps #tabPanel in place, and re-mounts tab JS. Falls
         * back to a full navigation on any error (e.g. an auth redirect to the
         * login page, where the response carries no #tabPanel).
         */
        function navigate(url, push) {
            if (navigating) return;
            navigating = true;
            fetch(url, { headers: { 'X-Requested-With': 'XMLHttpRequest' } })
                .then(function (r) {
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    return r.text();
                })
                .then(function (html) {
                    var doc = new DOMParser().parseFromString(html, 'text/html');
                    var fresh = doc.getElementById('tabPanel');
                    if (!fresh) throw new Error('no #tabPanel in response');

                    if (typeof monitorTeardown === 'function') monitorTeardown();
                    panel.innerHTML = fresh.innerHTML;

                    var tab = tabOf(url);
                    syncChrome(tab);
                    var title = doc.querySelector('title');
                    if (title) document.title = title.textContent;
                    if (push) window.history.pushState({ tab: tab }, '', url);

                    // Re-mount info builder + monitor against the fresh DOM.
                    if (window.LfForm) window.LfForm.mount();
                    monitorTeardown = window.MnMonitor
                        ? window.MnMonitor.mount(panel)
                        : function () {};
                    navigating = false;
                })
                .catch(function () {
                    // Non-recoverable in-place: hand off to a real navigation.
                    window.location.href = url;
                });
        }

        // ── Delegated clicks: tab links + in-panel pager links ─────────────
        document.addEventListener('click', function (e) {
            var link = e.target.closest ? e.target.closest('a') : null;
            if (!link) return;
            var href = link.getAttribute('href');
            if (!href) return;

            var isTab = tabsNav.contains(link) && link.classList.contains('detail-tab');
            var isPanelPager = panel.contains(link) && link.classList.contains('page-link');
            if (!isTab && !isPanelPager) return;

            // Let modified clicks (new tab / download) behave natively.
            if (e.defaultPrevented || e.button !== 0 ||
                e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;

            e.preventDefault();
            navigate(href, true);
        });

        // ── In-panel submissions search: GET form → AJAX navigation ────────
        document.addEventListener('submit', function (e) {
            var form = e.target;
            if (!panel.contains(form) || !form.classList.contains('sb-search')) return;
            e.preventDefault();
            var action = form.getAttribute('action') || window.location.pathname;
            var params = new URLSearchParams(new FormData(form)).toString();
            navigate(action + (params ? '?' + params : ''), true);
        });

        // ── Back / forward: re-fetch the URL the history entry points at ───
        window.addEventListener('popstate', function () {
            navigate(window.location.href, false);
        });

        // ── First load: mount whatever tab the server rendered ─────────────
        remount();
    });
})();
