/*
 * Concierge assistant chat. Adds a bell launcher and a floating, resizable chat window to every
 * page for people who may use the assistant, or runs inside #concierge-chat-page on the full-page
 * view.
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
  var SIZE_KEY = "concierge.assistant.size";
  var CAUTION = "AI can be confidently wrong: think critically and check anything important before acting on it.";
  var SVG = "http://www.w3.org/2000/svg";

  // ------------------------------------------------------------------ small helpers

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

  /** Icons are a few SVG shapes, built with DOM calls. */
  var ICONS = {
    bell: [["circle", { cx: 12, cy: 5.2, r: 1.6 }],
      ["path", { d: "M12 7.6c-4.1 0-7.4 3.1-7.7 7.1h15.4c-.3-4-3.6-7.1-7.7-7.1z" }],
      ["rect", { x: 2.5, y: 15.8, width: 19, height: 2.6, rx: 1.3 }]],
    send: [["path", { d: "M3.4 20.4 21 12 3.4 3.6l-.1 6.5 12.6 1.9-12.6 1.9z" }]],
    plus: [["path", { d: "M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6z" }]],
    expand: [["path", { d: "M4 4h7v2H7.4l4.3 4.3-1.4 1.4L6 7.4V11H4zm16 16h-7v-2h3.6l-4.3-4.3 1.4-1.4 4.3 4.3V13h2z" }]],
    collapse: [["path", { d: "M11 13v7H9v-3.6l-4.3 4.3-1.4-1.4L7.6 15H4v-2zm2-2V4h2v3.6l4.3-4.3 1.4 1.4L16.4 9H20v2z" }]],
    external: [["path", { d: "M14 3h7v7h-2V6.4l-8.3 8.3-1.4-1.4L17.6 5H14zM5 5h6v2H7v10h10v-4h2v6H5z" }]],
    close: [["path", { d: "m6.4 5 5.6 5.6L17.6 5 19 6.4 13.4 12l5.6 5.6-1.4 1.4-5.6-5.6L6.4 19 5 17.6l5.6-5.6L5 6.4z" }]],
    copy: [["path", { d: "M8 3h11v13h-2V5H8zM5 7h11v14H5zm2 2v10h7V9z" }]],
    check: [["path", { d: "m9.5 16.2-4.2-4.2-1.4 1.4 5.6 5.6L21 7.4 19.6 6z" }]],
  };

  function icon(name) {
    var svg = document.createElementNS(SVG, "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("aria-hidden", "true");
    svg.setAttribute("focusable", "false");
    ICONS[name].forEach(function (shape) {
      var node = document.createElementNS(SVG, shape[0]);
      Object.keys(shape[1]).forEach(function (key) {
        node.setAttribute(key, String(shape[1][key]));
      });
      svg.appendChild(node);
    });
    return svg;
  }

  function iconButton(name, label, className) {
    var button = el("button", "concierge-icon-button" + (className ? " " + className : ""));
    button.type = "button";
    button.setAttribute("aria-label", label);
    button.title = label;
    button.appendChild(icon(name));
    return button;
  }

  /** The clipboard API only exists on HTTPS (or localhost), so plain-HTTP Jenkins gets no copy buttons. */
  var CAN_COPY = !!(navigator.clipboard && navigator.clipboard.writeText);

  function copyText(text, button) {
    function confirmCopied() {
      button.replaceChildren(icon("check"));
      button.setAttribute("aria-label", "Copied");
      window.setTimeout(function () {
        button.replaceChildren(icon("copy"));
        button.setAttribute("aria-label", "Copy");
      }, 1500);
    }
    navigator.clipboard.writeText(text).then(confirmCopied, function () {});
  }

  function read(kind, key) {
    try {
      var store = window[kind];
      return store ? store.getItem(key) : null;
    } catch (e) {
      return null;
    }
  }

  function write(kind, key, value) {
    try {
      var store = window[kind];
      if (store) {
        store.setItem(key, value);
      }
    } catch (e) {
      // Storage blocked: the setting simply is not remembered.
    }
  }

  // ------------------------------------------------------------------ dates

  function timeOf(ms) {
    return new Date(ms).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
  }

  function dayOf(ms) {
    var day = new Date(ms);
    var today = new Date();
    var yesterday = new Date();
    yesterday.setDate(today.getDate() - 1);
    if (day.toDateString() === today.toDateString()) {
      return "Today";
    }
    if (day.toDateString() === yesterday.toDateString()) {
      return "Yesterday";
    }
    return day.toLocaleDateString([], { weekday: "long", month: "short", day: "numeric" });
  }

  // ------------------------------------------------------------------ rendering answers

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

  function codeBlock(container, part) {
    var language = (/^([A-Za-z0-9_+.-]*)\n/.exec(part) || [null, ""])[1];
    var code = part.replace(/^[A-Za-z0-9_+.-]*\n/, "").replace(/\n$/, "");
    var block = el("div", "concierge-code");
    var bar = el("div", "concierge-code__bar");
    bar.appendChild(el("span", "concierge-code__lang", language || "text"));
    if (CAN_COPY) {
      var copy = iconButton("copy", "Copy", "concierge-code__copy");
      copy.addEventListener("click", function () {
        copyText(code, copy);
      });
      bar.appendChild(copy);
    }
    var pre = el("pre", "concierge-code__body");
    pre.appendChild(el("code", null, code));
    block.appendChild(bar);
    block.appendChild(pre);
    container.appendChild(block);
  }

  function renderRich(text, container) {
    String(text).split("```").forEach(function (part, index) {
      if (index % 2 === 1) {
        codeBlock(container, part);
      } else if (part.trim() !== "") {
        prose(container, part);
      }
    });
  }

  // ------------------------------------------------------------------ server

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
          if (response.status === 403 && !body.message) {
            throw new Error("Jenkins refused the request. Your session may have expired: reload the page and try again.");
          }
          if (!response.ok) {
            throw new Error(body.message || "The assistant could not answer (HTTP " + response.status + ").");
          }
          if (!body || body.status !== "ok") {
            throw new Error("Jenkins sent an unexpected reply. Reload the page and try again.");
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

  // ------------------------------------------------------------------ the chat

  /** A chat bound to a container: message list, composer, and the calls to the server. */
  function Chat(container) {
    var userName = "";
    var lastDay = null;
    var busy = false;
    var loading = Promise.resolve();

    var log = el("div", "concierge-chat__log");
    log.setAttribute("role", "log");
    log.setAttribute("aria-live", "polite");

    var composer = el("form", "concierge-composer");
    var input = el("textarea", "concierge-composer__input");
    input.rows = 1;
    input.maxLength = 4000;
    input.placeholder = "Ask about a job, a build or a failure";
    input.setAttribute("aria-label", "Question for the Concierge assistant");
    var sendButton = iconButton("send", "Send", "concierge-composer__send");
    sendButton.type = "submit";
    sendButton.disabled = true;
    composer.appendChild(input);
    composer.appendChild(sendButton);

    var empty = el("div", "concierge-chat__empty");
    var hero = el("div", "concierge-chat__hero");
    hero.appendChild(icon("bell"));
    empty.appendChild(hero);
    empty.appendChild(el("p", "concierge-chat__empty-title", "How can I help?"));
    empty.appendChild(el("p", "concierge-chat__empty-text", "Ask about jobs, builds and failures on this Jenkins."));
    var examples = el("div", "concierge-chat__examples");
    [
      "What is failing right now, and why?",
      "Explain what this job does.",
      "Why did the last build fail?",
      "Which agents are offline?",
    ].forEach(function (example) {
      var button = el("button", "concierge-chat__example", example);
      button.type = "button";
      button.addEventListener("click", function () {
        if (busy) {
          return;
        }
        input.value = example;
        resize();
        send();
      });
      examples.appendChild(button);
    });
    empty.appendChild(examples);
    log.appendChild(empty);

    var note = el("p", "concierge-chat__note", "Uses only what you can see in Jenkins. " + CAUTION);

    container.appendChild(log);
    container.appendChild(composer);
    container.appendChild(note);

    function resize() {
      input.style.height = "auto";
      input.style.height = Math.min(input.scrollHeight, 180) + "px";
      sendButton.disabled = busy || input.value.trim() === "";
    }

    function scrollDown() {
      log.scrollTop = log.scrollHeight;
    }

    function daySeparator(at) {
      var day = at ? dayOf(at) : null;
      if (day && day !== lastDay) {
        lastDay = day;
        var separator = el("div", "concierge-chat__day");
        separator.appendChild(el("span", null, day));
        log.appendChild(separator);
      }
    }

    function avatar(role) {
      var node = el("div", "concierge-avatar concierge-avatar--" + role);
      node.setAttribute("aria-hidden", "true");
      if (role === "assistant") {
        node.appendChild(icon("bell"));
      } else {
        node.textContent = (userName || "You")
          .split(/\s+/)
          .map(function (word) {
            return word.charAt(0);
          })
          .join("")
          .slice(0, 2)
          .toUpperCase();
      }
      return node;
    }

    function messageRow(role, at) {
      empty.hidden = true;
      daySeparator(at);
      var row = el("div", "concierge-msg concierge-msg--" + role);
      row.appendChild(avatar(role));
      var column = el("div", "concierge-msg__column");
      var meta = el("div", "concierge-msg__meta");
      meta.appendChild(el("span", "concierge-msg__name", role === "user" ? userName || "You" : "Concierge"));
      if (at) {
        var time = el("time", "concierge-msg__time", timeOf(at));
        time.dateTime = new Date(at).toISOString();
        time.title = new Date(at).toLocaleString();
        meta.appendChild(time);
      }
      column.appendChild(meta);
      var bubble = el("div", "concierge-msg__bubble");
      column.appendChild(bubble);
      row.appendChild(column);
      log.appendChild(row);
      return { row: row, column: column, bubble: bubble };
    }

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

    function addUser(text, at) {
      messageRow("user", at).bubble.textContent = text;
      scrollDown();
    }

    function addAssistant(text, at, tools, proposals) {
      var parts = messageRow("assistant", at);
      renderRich(text, parts.bubble);
      (proposals || []).forEach(function (proposal) {
        parts.bubble.appendChild(proposalCard(proposal));
      });
      var footer = el("div", "concierge-msg__footer");
      if (tools && tools.length) {
        var chips = el("div", "concierge-msg__tools");
        chips.appendChild(el("span", "concierge-msg__tools-label", "Looked at"));
        tools
          .filter(function (name, i) {
            return tools.indexOf(name) === i;
          })
          .forEach(function (name) {
            chips.appendChild(el("span", "concierge-chip", name.replace(/_/g, " ")));
          });
        footer.appendChild(chips);
      }
      if (CAN_COPY) {
        var copy = iconButton("copy", "Copy", "concierge-msg__copy");
        copy.addEventListener("click", function () {
          copyText(text, copy);
        });
        footer.appendChild(copy);
      }
      parts.column.appendChild(footer);
      scrollDown();
    }

    function addError(text) {
      var parts = messageRow("assistant", Date.now());
      parts.row.classList.add("concierge-msg--error");
      parts.bubble.textContent = text;
      scrollDown();
    }

    function typing() {
      var row = el("div", "concierge-msg concierge-msg--assistant concierge-msg--typing");
      row.appendChild(avatar("assistant"));
      var bubble = el("div", "concierge-msg__bubble");
      var dots = el("span", "concierge-typing");
      dots.setAttribute("aria-hidden", "true");
      for (var i = 0; i < 3; i++) {
        dots.appendChild(el("span"));
      }
      bubble.appendChild(dots);
      bubble.appendChild(el("span", "concierge-visually-hidden", "Concierge is looking into it"));
      row.appendChild(bubble);
      log.appendChild(row);
      scrollDown();
      return row;
    }

    function send() {
      var question = input.value.trim();
      if (!question || busy) {
        return;
      }
      busy = true;
      input.value = "";
      resize();
      var indicator;
      // A question sent while the history is still loading goes after it, under the person's name.
      loading
        .catch(function () {})
        .then(function () {
          addUser(question, Date.now());
          indicator = typing();
          return request("ask", { question: question, page: currentPage() });
        })
        .then(function (body) {
          indicator.remove();
          addAssistant(String(body.answer || ""), body.at || Date.now(), body.tools, body.proposals);
        })
        .catch(function (error) {
          if (indicator) {
            indicator.remove();
          }
          addError(error.message);
          if (!input.value) {
            input.value = question;
          }
        })
        .then(function () {
          busy = false;
          resize();
          input.focus();
        });
    }

    composer.addEventListener("submit", function (event) {
      event.preventDefault();
      send();
    });
    input.addEventListener("input", resize);
    input.addEventListener("keydown", function (event) {
      if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
        event.preventDefault();
        send();
      }
    });

    this.load = function () {
      // aria-busy keeps screen readers from reading the restored conversation out on every page.
      log.setAttribute("aria-busy", "true");
      loading = request("history").then(function (body) {
        userName = body.user || "";
        (body.turns || []).forEach(function (turn) {
          if (turn.role === "user") {
            addUser(turn.text, turn.at);
          } else {
            addAssistant(turn.text, turn.at);
          }
        });
        if (body.provider) {
          note.textContent = "Answers come from " + body.provider + ". " + CAUTION;
        }
        log.setAttribute("aria-busy", "false");
        return body;
      });
      loading.catch(function (error) {
        log.setAttribute("aria-busy", "false");
        addError(error.message);
      });
      return loading;
    };

    this.reset = function () {
      if (busy) {
        return Promise.resolve();
      }
      return request("reset", {}).then(function () {
        Array.prototype.slice.call(log.querySelectorAll(".concierge-msg, .concierge-chat__day")).forEach(function (m) {
          m.remove();
        });
        lastDay = null;
        empty.hidden = false;
      });
    };

    this.focus = function () {
      input.focus();
    };
  }

  // ------------------------------------------------------------------ the floating window

  var MIN_WIDTH = 340;
  var MIN_HEIGHT = 420;

  /** Room above the window's bottom edge, which moves up to clear the launcher and the footer. */
  var bottomEdge = 88;

  function availableHeight() {
    return Math.max(160, window.innerHeight - bottomEdge - 16);
  }

  /** At least the minimum size, but never more than the screen has room for. */
  function clampSize(width, height) {
    return {
      width: Math.round(Math.min(Math.max(MIN_WIDTH, width), window.innerWidth - 32)),
      height: Math.round(Math.min(Math.max(MIN_HEIGHT, height), availableHeight())),
    };
  }

  function savedSize() {
    try {
      var saved = JSON.parse(read("localStorage", SIZE_KEY) || "null");
      if (saved && saved.width && saved.height) {
        return saved;
      }
    } catch (e) {
      // Not JSON: use the default.
    }
    return { width: 420, height: 620 };
  }

  function initWindow() {
    var launcher = el("button", "concierge-launcher");
    launcher.type = "button";
    launcher.setAttribute("aria-expanded", "false");
    launcher.setAttribute("aria-controls", "concierge-window");
    launcher.setAttribute("aria-label", "Ask Concierge about this page");
    var bell = icon("bell");
    bell.classList.add("concierge-launcher__bell");
    var cross = icon("close");
    cross.classList.add("concierge-launcher__close");
    launcher.appendChild(bell);
    launcher.appendChild(cross);
    var tooltip = el("span", "concierge-launcher__tooltip", "Ask Concierge about this page");
    tooltip.setAttribute("aria-hidden", "true");
    launcher.appendChild(tooltip);

    var win = el("section", "concierge-window");
    win.id = "concierge-window";
    win.setAttribute("role", "dialog");
    win.setAttribute("aria-label", "Concierge assistant");
    win.hidden = true;

    var handle = el("div", "concierge-window__resize");
    handle.setAttribute("role", "separator");
    handle.setAttribute("aria-label", "Resize the assistant. Drag, or use the arrow keys.");
    handle.title = "Drag to resize";
    handle.tabIndex = 0;
    win.appendChild(handle);

    var header = el("header", "concierge-window__header");
    var identity = el("div", "concierge-window__identity");
    var badge = el("div", "concierge-avatar concierge-avatar--assistant concierge-avatar--large");
    badge.setAttribute("aria-hidden", "true");
    badge.appendChild(icon("bell"));
    identity.appendChild(badge);
    var titles = el("div");
    titles.appendChild(el("h2", "concierge-window__title", "Concierge"));
    var subtitle = el("p", "concierge-window__subtitle", "Your guide to this Jenkins");
    titles.appendChild(subtitle);
    identity.appendChild(titles);
    header.appendChild(identity);
    var actions = el("div", "concierge-window__actions");
    var newChat = iconButton("plus", "New conversation");
    var expand = iconButton("expand", "Expand");
    var fullPage = el("a", "concierge-icon-button");
    fullPage.href = base;
    fullPage.setAttribute("aria-label", "Open as a full page");
    fullPage.title = "Open as a full page";
    fullPage.appendChild(icon("external"));
    var close = iconButton("close", "Close");
    [newChat, expand, fullPage, close].forEach(function (button) {
      actions.appendChild(button);
    });
    header.appendChild(actions);
    win.appendChild(header);

    var body = el("div", "concierge-chat concierge-chat--window");
    win.appendChild(body);
    document.body.appendChild(win);
    document.body.appendChild(launcher);

    var chat = new Chat(body);
    var loaded = false;
    var expanded = false;
    var size = savedSize();

    function applySize() {
      if (expanded) {
        win.style.width = "min(960px, calc(100vw - 32px))";
        win.style.height = availableHeight() + "px";
      } else {
        // Fit what the person chose to the room there is now, without forgetting what they chose.
        var shown = clampSize(size.width, size.height);
        win.style.width = shown.width + "px";
        win.style.height = shown.height + "px";
      }
      if (expand.getAttribute("data-expanded") !== String(expanded)) {
        expand.setAttribute("data-expanded", String(expanded));
        expand.replaceChildren(icon(expanded ? "collapse" : "expand"));
        expand.setAttribute("aria-label", expanded ? "Restore size" : "Expand");
        expand.title = expanded ? "Restore size" : "Expand";
      }
    }

    function setSize(width, height, save) {
      expanded = false;
      size = clampSize(width, height);
      applySize();
      if (save !== false) {
        write("localStorage", SIZE_KEY, JSON.stringify(size));
      }
    }

    // Dragging the top-left corner grows the window up and to the left, since it is anchored to
    // the bottom-right corner of the screen.
    handle.addEventListener("pointerdown", function (event) {
      if (event.button !== 0) {
        return;
      }
      event.preventDefault();
      try {
        handle.setPointerCapture(event.pointerId);
      } catch (e) {
        // A pointer the browser does not consider active cannot be captured; dragging still works
        // while it stays over the handle.
      }
      var start = { x: event.clientX, y: event.clientY, width: win.offsetWidth, height: win.offsetHeight };
      win.classList.add("concierge-window--resizing");
      function move(e) {
        setSize(start.width + (start.x - e.clientX), start.height + (start.y - e.clientY), false);
      }
      function up() {
        win.classList.remove("concierge-window--resizing");
        handle.removeEventListener("pointermove", move);
        handle.removeEventListener("pointerup", up);
        handle.removeEventListener("pointercancel", up);
        handle.removeEventListener("lostpointercapture", up);
        write("localStorage", SIZE_KEY, JSON.stringify(size));
      }
      handle.addEventListener("pointermove", move);
      handle.addEventListener("pointerup", up);
      handle.addEventListener("pointercancel", up);
      handle.addEventListener("lostpointercapture", up);
    });
    handle.addEventListener("keydown", function (event) {
      var step = event.shiftKey ? 80 : 20;
      var delta = { ArrowLeft: [step, 0], ArrowRight: [-step, 0], ArrowUp: [0, step], ArrowDown: [0, -step] }[event.key];
      if (delta) {
        event.preventDefault();
        setSize(win.offsetWidth + delta[0], win.offsetHeight + delta[1]);
      }
    });

    // Keep the launcher, and the window above it, clear of Jenkins' page footer (version, REST
    // API) when the footer scrolls into view.
    var footer = document.querySelector("footer.page-footer") || document.querySelector("footer");
    var scheduled = false;
    function place() {
      scheduled = false;
      var lift = 0;
      if (footer) {
        var rect = footer.getBoundingClientRect();
        // Jenkins hides the footer on small screens; a hidden footer reports a top of 0.
        if (rect.height > 0 && footer.getClientRects().length > 0) {
          lift = Math.max(0, window.innerHeight - rect.top);
        }
      }
      launcher.style.bottom = "calc(" + (20 + lift) + "px + env(safe-area-inset-bottom, 0px))";
      bottomEdge = 88 + lift;
      win.style.bottom = "calc(" + bottomEdge + "px + env(safe-area-inset-bottom, 0px))";
      win.style.maxHeight = availableHeight() + "px";
      if (!win.hidden) {
        applySize();
      }
    }
    function schedule() {
      if (!scheduled) {
        scheduled = true;
        window.requestAnimationFrame(place);
      }
    }
    window.addEventListener("scroll", schedule, { passive: true });
    window.addEventListener("resize", function () {
      schedule();
      if (!win.hidden) {
        applySize();
      }
    });

    function open(takeFocus) {
      win.hidden = false;
      launcher.classList.add("concierge-launcher--open");
      launcher.setAttribute("aria-expanded", "true");
      launcher.setAttribute("aria-label", "Close the assistant");
      applySize();
      place();
      write("sessionStorage", OPEN_KEY, "1");
      if (!loaded) {
        loaded = true;
        chat
          .load()
          .then(function (history) {
            if (history.provider) {
              subtitle.textContent = history.provider;
            }
          })
          .catch(function () {
            loaded = false;
          });
      }
      if (takeFocus !== false) {
        chat.focus();
      }
    }

    function hide() {
      win.hidden = true;
      launcher.classList.remove("concierge-launcher--open");
      launcher.setAttribute("aria-expanded", "false");
      launcher.setAttribute("aria-label", "Ask Concierge about this page");
      write("sessionStorage", OPEN_KEY, "0");
      launcher.focus();
    }

    launcher.addEventListener("click", function () {
      if (win.hidden) {
        open(true);
      } else {
        hide();
      }
    });
    close.addEventListener("click", hide);
    newChat.addEventListener("click", function () {
      chat.reset().then(chat.focus);
    });
    expand.addEventListener("click", function () {
      expanded = !expanded;
      applySize();
    });
    win.addEventListener("keydown", function (event) {
      if (event.key === "Escape" && !event.isComposing) {
        event.stopPropagation();
        hide();
      }
    });

    place();
    if (read("sessionStorage", OPEN_KEY) === "1") {
      open(false);
    }
  }

  function init() {
    var page = document.getElementById("concierge-chat-page");
    if (page) {
      var chat = new Chat(page);
      chat.load().catch(function () {
        // Shown in the conversation by load().
      });
      chat.focus();
      return;
    }
    initWindow();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
