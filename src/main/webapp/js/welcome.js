/*
 * Concierge welcome page: saves learning-path progress. Tours are started by tour.js, which every
 * page loads.
 */
(function () {
  "use strict";

  function init() {
    var page = document.getElementById("concierge-welcome");
    if (!page || page.getAttribute("data-logged-in") !== "true") {
      return;
    }
    var url = page.getAttribute("data-progress-url");
    var error = document.getElementById("concierge-progress-error");

    function updateCount(pathId) {
      var boxes = page.querySelectorAll('input[data-path="' + pathId + '"]');
      var done = 0;
      boxes.forEach(function (box) {
        if (box.checked) {
          done++;
        }
      });
      var count = page.querySelector('[data-path-count="' + pathId + '"]');
      if (count) {
        count.textContent = done + "/" + boxes.length;
      }
      // Every step done: the card shows the Completed badge, as the server does on page load.
      var card = page.querySelector('article[data-path="' + pathId + '"]');
      if (card) {
        card.classList.toggle("concierge-path--done", boxes.length > 0 && done === boxes.length);
      }
    }

    page.addEventListener("change", function (event) {
      var box = event.target;
      if (!box.matches || !box.matches("input[data-step]")) {
        return;
      }
      // crumb is Jenkins' own helper, defined on every page; wrap adds the CSRF header.
      var headers = crumb.wrap({ "Content-Type": "application/x-www-form-urlencoded" });
      var body = new URLSearchParams();
      body.set("step", box.getAttribute("data-step"));
      body.set("done", box.checked ? "true" : "false");
      error.hidden = true;
      fetch(url, { method: "POST", credentials: "same-origin", headers: headers, body: body })
        .then(function (response) {
          if (!response.ok) {
            throw new Error("HTTP " + response.status);
          }
          updateCount(box.getAttribute("data-path"));
        })
        .catch(function () {
          box.checked = !box.checked;
          error.textContent = "Your progress could not be saved. Reload the page and try again.";
          error.hidden = false;
        });
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
