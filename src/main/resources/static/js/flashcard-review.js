/* Smart Review mode (ULP-5.x): flip to reveal, then rate recall (Không nhớ /
 * Khó / Tốt / Dễ → quality 1/3/4/5). Each rating POSTs to the review endpoint,
 * upserting the user's SM-2 state, then advances to the next due card.
 * User content is rendered with textContent.
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

    function toast(kind, msg) {
        if (window.FcCommon) window.FcCommon.toast(kind, msg);
    }

    ready(function () {
        var host = document.getElementById('fcStudy');
        if (!host) return;
        var cards;
        try { cards = JSON.parse(host.dataset.cards || '[]'); } catch (e) { cards = []; }

        var viewer = document.getElementById('fcViewer');
        var empty = document.getElementById('fcEmpty');
        var badge = document.getElementById('fcDueBadge');

        function showDone(remaining) {
            if (viewer) viewer.classList.add('is-hidden');
            if (empty) empty.classList.remove('is-hidden');
            if (badge) badge.textContent = remaining > 0
                ? 'Còn ' + remaining + ' thẻ đến hạn' : 'Đã ôn xong các thẻ đến hạn';
        }

        if (!cards.length) { showDone(0); return; }

        var flipCard = document.getElementById('fcFlipCard');
        var frontText = document.getElementById('fcFrontText');
        var backText = document.getElementById('fcBackText');
        var ratings = document.getElementById('fcRatings');
        var hint = document.getElementById('fcHint');
        var idx = 0;

        function render() {
            var card = cards[idx];
            frontText.textContent = card.front || '';
            backText.textContent = card.back || '';
            flipCard.classList.remove('is-flipped');
            if (ratings) ratings.hidden = true;
            if (hint) hint.hidden = false;
        }

        function reveal() {
            flipCard.classList.add('is-flipped');
            if (ratings) ratings.hidden = false;
            if (hint) hint.hidden = true;
        }

        function setRatingsDisabled(disabled) {
            ratings.querySelectorAll('.fc-rate').forEach(function (b) { b.disabled = disabled; });
        }

        function rate(quality) {
            var card = cards[idx];
            setRatingsDisabled(true);
            window.FcCommon.postJson('/api/flashcards/cards/' + card.id + '/review', { quality: quality })
                .then(function (res) {
                    var remaining = res.data ? res.data.dueRemaining : 0;
                    if (badge) badge.textContent = 'Còn ' + remaining + ' thẻ đến hạn';
                    idx += 1;
                    setRatingsDisabled(false);
                    if (idx >= cards.length) { showDone(remaining); return; }
                    render();
                })
                .catch(function (err) {
                    setRatingsDisabled(false);
                    toast('error', err.message || 'Ghi nhận đánh giá thất bại');
                });
        }

        flipCard.addEventListener('click', reveal);
        flipCard.addEventListener('keydown', function (e) {
            if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); reveal(); }
        });
        ratings.querySelectorAll('.fc-rate').forEach(function (b) {
            b.addEventListener('click', function () { rate(Number(b.getAttribute('data-quality'))); });
        });

        render();
    });
})();
