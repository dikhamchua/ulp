/* Flip study mode (ULP-5.x): flip front/back, next/prev, shuffle, counter.
 * User content is rendered with textContent (never innerHTML).
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

    ready(function () {
        var host = document.getElementById('fcStudy');
        if (!host) return;
        var cards;
        try { cards = JSON.parse(host.dataset.cards || '[]'); } catch (e) { cards = []; }

        var viewer = document.getElementById('fcViewer');
        var empty = document.getElementById('fcEmpty');
        if (!cards.length) {
            if (empty) empty.hidden = false;
            if (viewer) viewer.hidden = true;
            return;
        }
        if (empty) empty.hidden = true;
        if (viewer) viewer.hidden = false;

        var flipCard = document.getElementById('fcFlipCard');
        var frontText = document.getElementById('fcFrontText');
        var backText = document.getElementById('fcBackText');
        var posEl = document.getElementById('fcPos');
        var totalEl = document.getElementById('fcTotal');

        var order = cards.map(function (_, i) { return i; });
        var pos = 0;

        function render() {
            var card = cards[order[pos]];
            frontText.textContent = card.front || '';
            backText.textContent = card.back || '';
            flipCard.classList.remove('is-flipped');
            posEl.textContent = String(pos + 1);
            totalEl.textContent = String(cards.length);
        }

        function flip() { flipCard.classList.toggle('is-flipped'); }

        function go(delta) {
            var next = pos + delta;
            if (next < 0 || next >= cards.length) return;
            pos = next;
            render();
        }

        function shuffle() {
            for (var i = order.length - 1; i > 0; i--) {
                var j = Math.floor(Math.random() * (i + 1));
                var t = order[i]; order[i] = order[j]; order[j] = t;
            }
            pos = 0;
            render();
        }

        flipCard.addEventListener('click', flip);
        flipCard.addEventListener('keydown', function (e) {
            if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); flip(); }
        });
        document.getElementById('fcPrev').addEventListener('click', function () { go(-1); });
        document.getElementById('fcNext').addEventListener('click', function () { go(1); });
        document.getElementById('fcShuffle').addEventListener('click', shuffle);

        render();
    });
})();
