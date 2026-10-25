/*
 * Concierge assistant chat. Adds an "Ask Concierge" button and panel to every page for people who
 * may use the assistant, or runs inside #concierge-chat-page on the full-page view.
 *
 * Answers are rendered from plain text into DOM nodes: paragraphs, "- " lists, ``` code blocks,
 * `inline code` and **bold**. Nothing is ever parsed as HTML (docs/security-design.md, rule 6).
 */
(function () {
  "use strict";

  var head = document.head;
  var root = (head && head.getAttribute("data-rooturl")) || "";
  var base = root + "/concierge/assistant/";
  var OPEN_KEY = "concierge.assistant.open";

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) {
      node.className = className;
    }
    if (text !== undefined && text !== null) {
      node.textContent = text;
    }
    return node;
  }

  function inline(parent, text) {
    var pattern = /(`[^`\n]+`|\*\*[^*\n]+\*\*)/g;
    var last = 0;
    var match;
    while ((match = pattern.exec(text)) !== null) {
      if (match.index > last) {
        parent.appendChild(document.createTextNode(text.slice(last, match.index)));
      }
      var token = match[0];
      if (token.charAt(0) === "`") {
        parent.appendChild(el("code", null, token.slice(1, -1)));
      } else {
        parent.appendChild(el("strong", null, token.slice(2, -2)));
      }
      last = match.index + token.length;
    }
    if (last < text.length) {
      parent.appendChild(document.createTextNode(text.slice(last)));
    }
  }

  function prose(container, text) {
    var lines = text.split("\n");
    var paragraph = null;
    var list = null;
    lines.forEach(function (line) {
      var bullet = /^\s*(?:[-*]|\d+[.)])\s+(.*)$/.exec(line);
      if (bullet) {
        paragraph = null;
        if (!list) {
          list = el(/^\s*\d/.test(line) ? "ol" : "ul");
          container.appendChild(list);
        }
        var item = el("li");
        inline(item, bullet[1]);
        list.appendChild(item);
      } else if (line.trim() === "") {
        paragraph = null;
        list = null;
      } else {
        list = null;
        if (!paragraph) {
          paragraph = el("p");
          container.appendChild(paragraph);
        } else {
          paragraph.appendChild(el("br"));
        }
        inline(paragraph, line);
      }
    });
  }

  function renderRich(text, container) {
    var parts = String(text).split("```");
    parts.forEach(function (part, index) {
      if (index % 2 === 1) {
        var code = part.replace(/^[A-Za-z0-9_+.-]*\n/, "").replace(/\n$/, "");
        var pre = el("pre", "concierge-chat__code");
        pre.appendChild(el("code", null, code));
        container.appendChild(pre);
      } else if (part.trim() !== "") {
        prose(container, part);
      }
    });
  }

  function request(path, params) {
    var headers = { Accept: "application/json" };
    var crumbHeader = head.getAttribute("data-crumb-header");
    var crumbValue = head.getAttribute("data-crumb-value");
    var options = { credentials: "same-origin", headers: headers };
    if (params) {
      headers["Content-Type"] = "application/x-www-form-urlencoded";
      if (crumbHeader && crumbValue) {
        headers[crumbHeader] = crumbValue;
      }
      options.method = "POST";
      options.body = new URLSearchParams(params);
    }
    return fetch(base + path, options).then(function (response) {
      return response
        .json()
        .catch(function () {
          return {};
        })
        .then(function (body) {
          if (!response.ok) {
            var error = new Error(body.message || "The assistant could not answer (HTTP " + response.status + ").");
            throw error;
          }
          return body;
        });
    });
  }

  function currentPage() {
    var path = window.location.pathname;
    if (root && path.indexOf(root) === 0) {
      path = path.slice(root.length);
    }
    return path.replace(/^\/+/, "");
  }

  /** A chat bound to a container: message log, input, and the calls to the server. */
  function Chat(container) {
    var log = el("div", "concierge-chat__log");
    log.setAttribute("role", "log");
    log.setAttribute("aria-live", "polite");
    var empty = el("div", "concierge-chat__empty");
    empty.appendChild(el("p", "concierge-chat__empty-title", "Ask anything about this Jenkins."));
    var examples = el("ul", "concierge-chat__examples");
    [
      "What is failing right now, and why?",
      "Explain what this job does.",
      "Why did the last build fail?",
      "Which agents are offline?"
    ].forEach(function (example) {
      var item = el("li");
      var button = el("button", "concierge-chat__example", example);
      button.type = "button";
      button.addEventListener("click", function () {
        input.value = example;
        send();
      });
      item.appendChild(button);
      examples.appendChild(item);
    });
    empty.appendChild(examples);
    log.appendChild(empty);

    var form = el("form", "concierge-chat__form");
    var input = el("textarea", "jenkins-input concierge-chat__input");
    input.rows = 2;
    input.maxLength = 4000;
    input.placeholder = "Ask about a job, a build or a failure";
    input.setAttribute("aria-label", "Question for the Concierge assistant");
    var sendButton = el("button", "jenkins-button jenkins-button--primary", "Ask");
    sendButton.type = "submit";
    form.appendChild(input);
    form.appendChild(sendButton);

    var note = el("p", "concierge-chat__note", "Uses only what you can see in Jenkins. Answers can be wrong.");

    container.appendChild(log);
    container.appendChild(form);
    container.appendChild(note);

    var busy = false;

    /**
     * A confirmation card for a build the assistant proposed. Everything on it comes from the
     * server's structured proposal, never from the model's text, and nothing runs until "Run it".
     */
    function proposalCard(proposal) {
      var card = el("div", "concierge-proposal");
      card.setAttribute("role", "group");
      var heading = proposal.kind === "rebuild"
        ? "Run " + proposal.job + " again" + (proposal.fromBuild ? " (like #" + proposal.fromBuild + ")" : "") + "?"
        : "Start a build of " + proposal.job + "?";
      card.setAttribute("aria-label", heading);
      card.appendChild(el("p", "concierge-proposal__title", heading));
      if (proposal.parameters && proposal.parameters.length) {
        var list = el("dl", "concierge-proposal__params");
        proposal.parameters.forEach(function (parameter) {
          list.appendChild(el("dt", null, parameter.name));
          list.appendChild(el("dd", null, parameter.value));
        });
        card.appendChild(list);
      } else {
        card.appendChild(el("p", "concierge-proposal__none", "No parameters."));
      }
      var actions = el("div", "concierge-proposal__actions");
      var run = el("button", "jenkins-button jenkins-button--primary", "Run it");
      run.type = "button";
      var cancel = el("button", "jenkins-button", "Cancel");
      cancel.type = "button";
      var status = el("p", "concierge-proposal__status");
      status.setAttribute("role", "status");
      actions.appendChild(run);
      actions.appendChild(cancel);
      card.appendChild(actions);
      card.appendChild(status);

      function done(text, link) {
        actions.remove();
        status.textContent = text;
        if (link) {
          status.appendChild(document.createTextNode(" "));
          var a = el("a", null, "Open the job");
          a.href = root + "/" + link;
          status.appendChild(a);
        }
      }

      run.addEventListener("click", function () {
        run.disabled = true;
        cancel.disabled = true;
        request("confirm", { id: proposal.id })
          .then(function (body) {
            done("Queued. You started this build.", body.url);
          })
          .catch(function (error) {
            done(error.message);
          });
      });
      cancel.addEventListener("click", function () {
        request("cancel", { id: proposal.id }).catch(function () {});
        done("Cancelled. Nothing ran.");
      });
      return card;
    }

    function usageLine(usage) {
      if (!usage || (!usage.inputTokens && !usage.outputTokens)) {
        return null;
      }
      var parts = [
        Number(usage.inputTokens).toLocaleString() + " in / " + Number(usage.outputTokens).toLocaleString() + " out tokens"
      ];
      if (usage.ownKey) {
        parts.push("your key");
      } else if (usage.cost !== null && usage.cost !== undefined) {
        var cost = Number(usage.cost);
        parts.push(cost > 0 && cost < 0.01 ? "under $0.01" : "about $" + cost.toFixed(2));
      } else {
        parts.push("price unknown");
      }
      return el("p", "concierge-chat__usage", parts.join(" \u00b7 "));
    }

    function addMessage(role, text, tools, proposals, usage) {
      empty.hidden = true;
      var message = el("div", "concierge-chat__message concierge-chat__message--" + role);
      if (role === "assistant") {
        renderRich(text, message);
        (proposals || []).forEach(function (proposal) {
          message.appendChild(proposalCard(proposal));
        });
        if (tools && tools.length) {
          var unique = tools.filter(function (name, i) {
            return tools.indexOf(name) === i;
          });
          message.appendChild(el("p", "concierge-chat__tools", "Looked at: " + unique.join(", ").replace(/_/g, " ")));
        }
        var line = usageLine(usage);
        if (line) {
          message.appendChild(line);
        }
      } else if (role === "error") {
        message.textContent = text;
        message.setAttribute("role", "alert");
      } else {
        message.textContent = text;
      }
      log.appendChild(message);
      log.scrollTop = log.scrollHeight;
      return message;
    }

    function send() {
      var question = input.value.trim();
      if (!question || busy) {
        return;
      }
      busy = true;
      sendButton.disabled = true;
      input.value = "";
      addMessage("user", question);
      var pending = addMessage("pending", "Looking into it...");
      request("ask", { question: question, page: currentPage() })
        .then(function (body) {
          pending.remove();
          addMessage("assistant", body.answer, body.tools, body.proposals, body.usage);
        })
        .catch(function (error) {
          pending.remove();
          addMessage("error", error.message);
        })
        .then(function () {
          busy = false;
          sendButton.disabled = false;
          input.focus();
        });
    }

    form.addEventListener("submit", function (event) {
      event.preventDefault();
      send();
    });
    input.addEventListener("keydown", function (event) {
      if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        send();
      }
    });

    this.load = function () {
      return request("history").then(function (body) {
        (body.turns || []).forEach(function (turn) {
          addMessage(turn.role === "user" ? "user" : "assistant", turn.text);
        });
        if (body.provider) {
          note.textContent = "Answers come from " + body.provider + ". Uses only what you can see in Jenkins. Answers can be wrong.";
        }
      });
    };

    this.reset = function () {
      return request("reset", {}).then(function () {
        Array.prototype.slice.call(log.querySelectorAll(".concierge-chat__message")).forEach(function (m) {
          m.remove();
        });
        empty.hidden = false;
      });
    };

    this.focus = function () {
      input.focus();
    };
  }

  function remember(open) {
    try {
      window.sessionStorage.setItem(OPEN_KEY, open ? "1" : "0");
    } catch (e) {
      // Storage blocked: the panel just starts closed on the next page.
    }
  }

  function wasOpen() {
    try {
      return window.sessionStorage.getItem(OPEN_KEY) === "1";
    } catch (e) {
      return false;
    }
  }

  var SVG = "http://www.w3.org/2000/svg";

  /** A concierge's service bell, drawn with DOM calls like everything else here. */
  function bellIcon() {
    var svg = document.createElementNS(SVG, "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("aria-hidden", "true");
    svg.setAttribute("focusable", "false");
    var button = document.createElementNS(SVG, "circle");
    button.setAttribute("cx", "12");
    button.setAttribute("cy", "5.2");
    button.setAttribute("r", "1.6");
    var dome = document.createElementNS(SVG, "path");
    dome.setAttribute("d", "M12 7.6c-4.1 0-7.4 3.1-7.7 7.1h15.4c-.3-4-3.6-7.1-7.7-7.1z");
    var base = document.createElementNS(SVG, "rect");
    base.setAttribute("x", "2.5");
    base.setAttribute("y", "15.8");
    base.setAttribute("width", "19");
    base.setAttribute("height", "2.6");
    base.setAttribute("rx", "1.3");
    svg.appendChild(button);
    svg.appendChild(dome);
    svg.appendChild(base);
    return svg;
  }

  /**
   * Keeps the launcher above Jenkins' page footer (version, REST API) when the footer scrolls into
   * view, instead of sitting on top of it.
   */
  function keepClearOfFooter(launcher) {
    var footer = document.querySelector("footer.page-footer, footer");
    if (!footer) {
      return;
    }
    var scheduled = false;
    function place() {
      scheduled = false;
      var rect = footer.getBoundingClientRect();
      // Jenkins hides the footer on small screens; a hidden footer reports a top of 0.
      var shown = rect.height > 0 && footer.getClientRects().length > 0;
      var overlap = shown ? Math.max(0, window.innerHeight - rect.top) : 0;
      launcher.style.bottom = "calc(" + (20 + overlap) + "px + env(safe-area-inset-bottom, 0px))";
    }
    function schedule() {
      if (!scheduled) {
        scheduled = true;
        window.requestAnimationFrame(place);
      }
    }
    window.addEventListener("scroll", schedule, { passive: true });
    window.addEventListener("resize", schedule);
    place();
  }

  function initPanel() {
    var launcher = el("button", "concierge-launcher");
    launcher.type = "button";
    launcher.setAttribute("aria-expanded", "false");
    launcher.setAttribute("aria-label", "Ask Concierge about this page");
    launcher.appendChild(bellIcon());
    // Shown on hover and keyboard focus; screen readers get the same words from aria-label.
    var tooltip = el("span", "concierge-launcher__tooltip", "Ask Concierge about this page");
    tooltip.setAttribute("aria-hidden", "true");
    launcher.appendChild(tooltip);

    var panel = el("aside", "concierge-panel");
    panel.setAttribute("aria-label", "Concierge assistant");
    panel.hidden = true;

    var header = el("header", "concierge-panel__header");
    header.appendChild(el("h2", "concierge-panel__title", "Concierge"));
    var actions = el("div", "concierge-panel__actions");
    var reset = el("button", "jenkins-button jenkins-button--tertiary", "New conversation");
    reset.type = "button";
    var full = el("a", "jenkins-button jenkins-button--tertiary", "Full page");
    full.href = base;
    var close = el("button", "jenkins-button jenkins-button--tertiary", "Close");
    close.type = "button";
    close.setAttribute("aria-label", "Close the assistant");
    actions.appendChild(reset);
    actions.appendChild(full);
    actions.appendChild(close);
    header.appendChild(actions);
    panel.appendChild(header);

    var body = el("div", "concierge-chat concierge-chat--panel");
    panel.appendChild(body);
    document.body.appendChild(panel);
    document.body.appendChild(launcher);
    keepClearOfFooter(launcher);

    var chat = new Chat(body);
    var loaded = false;

    function open() {
      panel.hidden = false;
      launcher.hidden = true;
      launcher.setAttribute("aria-expanded", "true");
      remember(true);
      if (!loaded) {
        loaded = true;
        chat.load().catch(function () {
          loaded = false;
        });
      }
      chat.focus();
    }

    function hide() {
      panel.hidden = true;
      launcher.hidden = false;
      launcher.setAttribute("aria-expanded", "false");
      remember(false);
      launcher.focus();
    }

    launcher.addEventListener("click", open);
    close.addEventListener("click", hide);
    reset.addEventListener("click", function () {
      chat.reset();
    });
    panel.addEventListener("keydown", function (event) {
      if (event.key === "Escape") {
        hide();
      }
    });
    if (wasOpen()) {
      open();
    }
  }

  function init() {
    var page = document.getElementById("concierge-chat-page");
    if (page) {
      var chat = new Chat(page);
      chat.load();
      chat.focus();
      return;
    }
    initPanel();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
