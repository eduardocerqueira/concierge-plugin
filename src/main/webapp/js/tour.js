/*
 * Concierge tour runner. Loaded on every page for users who may use Concierge, and idle unless a
 * tour was started in this browser tab.
 *
 * A tour is fetched once from /concierge/tour and kept in sessionStorage with the current step, so
 * it survives the page loads it causes. All text is written with textContent; nothing from the
 * server is ever parsed as HTML (docs/security-design.md, rule 6).
 */
(function () {
  "use strict";

  var KEY = "concierge.tour";
  var root = (document.head && document.head.getAttribute("data-rooturl")) || "";
  var card = null;
  var target = null;
  var currentSelector = null;
  var watcher = null;
  // Where focus was before the card took it, so it can go back there when the tour ends.
  var returnFocus = null;

  function load() {
    try {
      return JSON.parse(window.sessionStorage.getItem(KEY));
    } catch (e) {
      return null;
    }
  }

  function save(state) {
    try {
      if (state) {
        window.sessionStorage.setItem(KEY, JSON.stringify(state));
      } else {
        window.sessionStorage.removeItem(KEY);
      }
    } catch (e) {
      // Storage blocked: the tour simply ends at the next page load.
    }
  }

  function urlOf(step) {
    return root + "/" + step.path;
  }

  function trimSlash(path) {
    return path.replace(/\/+$/, "");
  }

  function isHere(step) {
    var wanted = new URL(urlOf(step), window.location.origin).pathname;
    return trimSlash(window.location.pathname) === trimSlash(wanted);
  }

  function findTarget(selector) {
    var parts = (selector || "").split(",");
    for (var i = 0; i < parts.length; i++) {
      var candidate = parts[i].trim();
      if (!candidate) {
        continue;
      }
      try {
        var element = document.querySelector(candidate);
        if (element && element.getClientRects().length > 0) {
          return element;
        }
      } catch (e) {
        // An invalid selector from a future Jenkins page: try the next one.
      }
    }
    return null;
  }

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) {
      node.className = className;
    }
    if (text !== undefined) {
      node.textContent = text;
    }
    return node;
  }

  function clear() {
    if (watcher) {
      window.clearInterval(watcher);
      watcher = null;
    }
    currentSelector = null;
    if (target) {
      target.classList.remove("concierge-tour-target");
      target = null;
    }
    if (card) {
      var hadFocus = card.contains(document.activeElement);
      card.remove();
      card = null;
      if (hadFocus && returnFocus && returnFocus.isConnected) {
        returnFocus.focus({ preventScroll: true });
      }
    }
  }

  function end() {
    save(null);
    clear();
  }

  function go(state, index) {
    if (index < 0 || index >= state.steps.length) {
      end();
      return;
    }
    state.index = index;
    save(state);
    var step = state.steps[index];
    if (isHere(step)) {
      render(state);
    } else {
      window.location.href = urlOf(step);
    }
  }

  /**
   * Jenkins refreshes some widgets (the build queue, executors, build history) by replacing their
   * markup, which takes the highlight with it. Find the element again when that happens.
   */
  function refreshTarget() {
    if (!card || !currentSelector || (target && target.isConnected)) {
      return;
    }
    target = findTarget(currentSelector);
    if (target) {
      target.classList.add("concierge-tour-target");
      card.classList.remove("concierge-tour-card--centered");
    }
    position();
  }

  function position() {
    if (!card) {
      return;
    }
    if (!target) {
      card.classList.add("concierge-tour-card--centered");
      card.style.top = "";
      card.style.left = "";
      return;
    }
    var rect = target.getBoundingClientRect();
    var cardRect = card.getBoundingClientRect();
    var margin = 12;
    var top = rect.bottom + margin;
    if (top + cardRect.height > window.innerHeight - margin) {
      top = Math.max(margin, rect.top - cardRect.height - margin);
    }
    var left = Math.min(
      Math.max(margin, rect.left),
      window.innerWidth - cardRect.width - margin
    );
    card.style.top = top + "px";
    card.style.left = Math.max(margin, left) + "px";
  }

  function render(state) {
    clear();
    var step = state.steps[state.index];
    var last = state.index === state.steps.length - 1;

    currentSelector = step.selector;
    target = findTarget(step.selector);
    if (target) {
      target.classList.add("concierge-tour-target");
      target.scrollIntoView({ block: "center", behavior: "auto" });
    }

    if (!returnFocus || !returnFocus.isConnected) {
      returnFocus = document.activeElement;
    }
    card = el("div", "concierge-tour-card");
    card.setAttribute("role", "dialog");
    card.setAttribute("aria-label", step.title);
    card.setAttribute("aria-describedby", "concierge-tour-text");

    var counter = el("p", "concierge-tour-card__counter",
      "Step " + (state.index + 1) + " of " + state.steps.length);
    var title = el("h2", "concierge-tour-card__title", step.title);
    var text = el("p", "concierge-tour-card__text", step.text);
    text.id = "concierge-tour-text";

    var actions = el("div", "concierge-tour-card__actions");
    var exit = el("button", "jenkins-button jenkins-button--tertiary", "Leave tour");
    exit.type = "button";
    exit.addEventListener("click", end);
    actions.appendChild(exit);
    if (state.index > 0) {
      var back = el("button", "jenkins-button", "Back");
      back.type = "button";
      back.addEventListener("click", function () {
        go(state, state.index - 1);
      });
      actions.appendChild(back);
    }
    var next = el("button", "jenkins-button jenkins-button--primary", last ? "Finish" : "Next");
    next.type = "button";
    next.addEventListener("click", function () {
      if (last) {
        end();
      } else {
        go(state, state.index + 1);
      }
    });
    actions.appendChild(next);

    card.appendChild(counter);
    card.appendChild(title);
    card.appendChild(text);
    card.appendChild(actions);
    document.body.appendChild(card);
    position();
    watcher = window.setInterval(refreshTarget, 1000);
    next.focus({ preventScroll: true });
  }

  function start(id) {
    var url = root + "/concierge/tour?id=" + encodeURIComponent(id);
    fetch(url, { credentials: "same-origin", headers: { Accept: "application/json" } })
      .then(function (response) {
        if (!response.ok) {
          throw new Error("HTTP " + response.status);
        }
        return response.json();
      })
      .then(function (tour) {
        // Jenkins wraps okJSON responses as {status, data}.
        var body = tour && tour.data ? tour.data : tour;
        if (!body || !body.steps || body.steps.length === 0) {
          return;
        }
        go({ id: body.id, steps: body.steps, index: 0 }, 0);
      })
      .catch(function () {
        window.alert("The tour could not be loaded. Reload the page and try again.");
      });
  }

  function init() {
    document.addEventListener("click", function (event) {
      var trigger = event.target.closest ? event.target.closest("[data-concierge-tour]") : null;
      if (trigger) {
        event.preventDefault();
        start(trigger.getAttribute("data-concierge-tour"));
      }
    });
    document.addEventListener("keydown", function (event) {
      if (card && event.key === "Escape" && !event.isComposing) {
        end();
      }
    });
    var scheduled = false;
    function reposition() {
      if (!scheduled) {
        scheduled = true;
        window.requestAnimationFrame(function () {
          scheduled = false;
          position();
        });
      }
    }
    window.addEventListener("resize", reposition);
    window.addEventListener("scroll", reposition, true);

    var state = load();
    if (!state || !state.steps || typeof state.index !== "number") {
      return;
    }
    var step = state.steps[state.index];
    if (step && isHere(step)) {
      render(state);
    } else {
      // The user wandered off the tour's page; leave them alone rather than drag them back.
      end();
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
