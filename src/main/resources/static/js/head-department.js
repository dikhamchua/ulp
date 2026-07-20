/* HEAD department screens — flash → toast drain. */
(function () {
  'use strict';
  var flashData = document.getElementById('flash-data');
  if (flashData && window.UlpToast) {
    if (flashData.dataset.flashSuccess) window.UlpToast.success(flashData.dataset.flashSuccess);
    if (flashData.dataset.flashError) window.UlpToast.error(flashData.dataset.flashError);
  }
})();
