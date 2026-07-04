/* Live monitor (Epic #11): polls the monitor-data JSON endpoint ~30s and
 * updates the counts + per-student table without a full reload. All dynamic
 * values are written with textContent (XSS-safe), never innerHTML.
 *
 * Exposes window.MnMonitor.mount(root): scans for #mnPage inside root, wires
 * the countdown + poll loop, and returns a teardown() that clears both
 * intervals. The tab orchestrator calls mount() after an AJAX swap and invokes
 * the returned teardown() before leaving the tab so no interval leaks.
 */
(function () {
    'use strict';

    var POLL_MS = 30000;

    function stateLabel(state) {
        if (state === 'submitted') return 'Đã nộp';
        if (state === 'in-progress') return 'Đang làm';
        return 'Chưa làm';
    }

    function pad(n) { return (n < 10 ? '0' : '') + n; }

    /** Formats a countdown of whole seconds as HH:MM:SS. */
    function fmtClock(total) {
        if (total == null || total < 0) total = 0;
        var h = Math.floor(total / 3600);
        var m = Math.floor((total % 3600) / 60);
        return pad(h) + ':' + pad(m) + ':' + pad(total % 60);
    }

    /** Formats an ISO timestamp as HH:mm:ss dd/MM (local, from the raw string). */
    function fmtActivity(iso) {
        if (!iso) return '—';
        var s = String(iso);
        var t = s.indexOf('T');
        if (t < 0) return s;
        var date = s.slice(0, t);         // yyyy-MM-dd
        var time = s.slice(t + 1, t + 9); // HH:mm:ss
        var parts = date.split('-');
        return time + ' ' + parts[2] + '/' + parts[1];
    }

    /**
     * Wires the monitor inside `root` (document by default). Returns a
     * teardown() that clears the timers, or a no-op when there is no monitor
     * on the page (e.g. a non-monitor tab).
     */
    function mount(root) {
        var scope = root || document;
        var page = scope.querySelector('#mnPage');
        if (!page) return function () {};

        var url = page.getAttribute('data-poll-url');
        var tbody = page.querySelector('#mnStudents');
        var countdownEl = page.querySelector('#mnCountdown');

        function setText(id, value) {
            var el = page.querySelector('#' + id);
            if (el) el.textContent = value;
        }

        // Local per-second countdown; resynced from each poll's remainingSeconds.
        var remaining = null;
        var initSeconds = countdownEl && countdownEl.getAttribute('data-seconds');
        if (initSeconds != null && initSeconds !== '') remaining = parseInt(initSeconds, 10);

        function tick() {
            if (remaining == null) { setText('mnCountdown', '—'); return; }
            setText('mnCountdown', fmtClock(remaining));
            if (remaining > 0) remaining--;
        }

        function appendCell(tr, text) {
            var td = document.createElement('td');
            td.textContent = text == null ? '' : text;
            tr.appendChild(td);
        }

        function renderStudents(students) {
            tbody.textContent = '';
            (students || []).forEach(function (s) {
                var tr = document.createElement('tr');
                appendCell(tr, s.name);
                appendCell(tr, s.email);

                var stateTd = document.createElement('td');
                var pill = document.createElement('span');
                pill.className = 'mn-pill mn-pill-' + s.state;
                pill.textContent = stateLabel(s.state);
                stateTd.appendChild(pill);
                if (s.active) {
                    var dot = document.createElement('span');
                    dot.className = 'mn-active-dot';
                    dot.textContent = ' ●';
                    stateTd.appendChild(dot);
                }
                tr.appendChild(stateTd);

                appendCell(tr, fmtActivity(s.lastActivity));
                tbody.appendChild(tr);
            });
        }

        function poll() {
            fetch(url, { headers: { 'Accept': 'application/json' } })
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (data) {
                    if (!data) return;
                    setText('mnSubmitted', data.submittedCount);
                    setText('mnInProgress', data.inProgressCount);
                    setText('mnActive', data.activeCount);
                    // Resync the local countdown to the server's authoritative value.
                    remaining = data.remainingSeconds == null ? null : data.remainingSeconds;
                    tick();
                    renderStudents(data.students);
                })
                .catch(function () {
                    // A missed poll is non-fatal; the next interval retries.
                });
        }

        tick();               // format the SSR seconds immediately
        poll();               // refresh data on load (no 30s raw-value flash)
        var tickId = setInterval(tick, 1000);
        var pollId = setInterval(poll, POLL_MS);

        return function teardown() {
            clearInterval(tickId);
            clearInterval(pollId);
        };
    }

    window.MnMonitor = { mount: mount };
})();
