/* Sizes the budget meter on the usage page from its data attribute; no inline styles in the view. */
(function () {
  "use strict";
  document.querySelectorAll(".concierge-meter__fill[data-percent]").forEach(function (fill) {
    var percent = Math.max(0, Math.min(100, parseInt(fill.getAttribute("data-percent"), 10) || 0));
    fill.style.width = percent + "%";
    if (percent >= 90) {
      fill.classList.add("concierge-meter__fill--high");
    }
  });
})();
