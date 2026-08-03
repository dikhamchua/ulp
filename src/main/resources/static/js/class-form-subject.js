/**
 * Class form — show a non-blocking warning when the selected subject belongs
 * to a different department than the lecturer.
 *
 * Does NOT drain #flash-data (notifications.js owns that).
 */
(function () {
    'use strict';

    function init() {
        // Create form uses #classForm; settings tab reuses the same subject picker as #classEditForm.
        var form = document.getElementById('classForm')
            || document.getElementById('classEditForm');
        var select = document.getElementById('subjectId');
        var warning = document.getElementById('subject-cross-dept-warning');
        if (!form || !select || !warning) {
            return;
        }

        var lecturerDeptRaw = form.getAttribute('data-lecturer-department-id');
        var lecturerDeptId = lecturerDeptRaw && lecturerDeptRaw !== ''
            ? String(lecturerDeptRaw)
            : null;

        function refresh() {
            var opt = select.options[select.selectedIndex];
            if (!opt || !opt.value) {
                warning.hidden = true;
                return;
            }
            var subjectDept = opt.getAttribute('data-department-id');
            // Cross-dept when lecturer has no dept, or depts differ.
            var cross = !lecturerDeptId
                || !subjectDept
                || String(subjectDept) !== lecturerDeptId;
            warning.hidden = !cross;
        }

        select.addEventListener('change', refresh);
        refresh();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
