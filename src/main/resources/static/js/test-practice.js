/* Practice-new form (Epic #11): picking a specific exam supersedes the class
 * scope, so the two source selects are kept mutually exclusive for clarity.
 * The form submits natively (POST); the server redirects into the new test.
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
        var classSel = document.getElementById('prSourceClass');
        var testSel = document.getElementById('prSourceTest');
        if (!classSel || !testSel) return;

        // Choosing an exam clears the class scope (server ignores class then anyway).
        testSel.addEventListener('change', function () {
            if (testSel.value) classSel.value = '';
            classSel.disabled = !!testSel.value;
        });
        classSel.addEventListener('change', function () {
            if (classSel.value) testSel.value = '';
        });
    });
})();
