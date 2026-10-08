// Part 8 figures: the decision ladder, whole view vs targeted patch (with a pointer predict),
// and out-of-order search responses under three request designs.
(function () {
  "use strict";
  const { el, clear, frame, stepper, seg, predict } = EE;

  // ---------------------------------------------------------------- the decision ladder
  const RUNGS = ["Server round-trip", "Native HTML", "CSS", "Datastar attributes", "Custom JS"];

  // For each interaction: one entry per rung tried, in order. The last entry is where it stops.
  const CASES = [
    { name: "Size options for the chosen drink",
      steps: [["stop", "The options depend on the menu and on stock, which only the server knows. Part 4's drop-down: <code>@post('/draft')</code>, the server renders the <code>&lt;option&gt;</code>s."]] },
    { name: "Receipt panel opens and closes",
      steps: [["skip", "Nothing the server needs to know. A request per toggle would buy only \"stays open after a reload\"."],
              ["stop", "<code>&lt;details&gt;&lt;summary&gt;Receipt&lt;/summary&gt;…&lt;/details&gt;</code>. No signal, no request. Inside a re-rendered region, add <code>data-preserve-attr=\"open\"</code> so the morph leaves the user's choice alone."]] },
    { name: "Order details as a popup",
      steps: [["skip", "The details can come from the server, but opening and closing is presentation."],
              ["stop", "<code>&lt;button popovertarget=\"order-7\"&gt;</code> and <code>&lt;div id=\"order-7\" popover&gt;</code>. The browser handles open, close, Escape and click-outside."]] },
    { name: "Hide the error line when there is no error",
      steps: [["skip", "The server already renders the message (or an empty element). Whether an empty element takes space is presentation."],
              ["skip", "No element means \"hide me when I'm empty\", and the id has to stay so the next render can fill it."],
              ["stop", "<code>#draft-error:empty { display: none }</code>. No <code>data-show</code> needed."]] },
    { name: "Highlight the row under the pointer",
      steps: [["skip", "A request per mouse move, for no information."],
              ["skip", "No hover element in HTML."],
              ["stop", "<code>#board li:hover { background: … }</code>"]] },
    { name: "Live character count on the order note",
      steps: [["skip", "A request per keystroke for a value the browser already has."],
              ["skip", "<code>maxlength=\"80\"</code> caps the length but doesn't show a count. Keep it anyway: it's the native rung doing what it can."],
              ["skip", "CSS can't count characters."],
              ["stop", "<code>data-text=\"$note.length + ' / 80'\"</code>. UI bookkeeping: it formats a value the user typed and derives no business rule. The server still enforces 80."]] },
    { name: "Disable Add while its request is in flight",
      steps: [["skip", "The server can't answer \"you're waiting for me\" before it has answered."],
              ["skip", "No native notion of a pending fetch."],
              ["skip", "CSS can't see fetch state."],
              ["stop", "<code>data-indicator:_adding</code> on the button that runs <code>@post</code>, and <code>data-attr:disabled=\"$_adding\"</code>. Datastar turns it off in a <code>finally</code>, even after a network error."]] },
    { name: "Back button returns to the filtered board",
      steps: [["skip", "An <code>@get</code> re-render filters the board, but the URL doesn't change, so Back leaves the page."],
              ["stop", "<code>&lt;form method=\"get\" action=\"/board\"&gt;</code>: a full page load. The URL holds the filter, and Back and bookmarks work with no code. Only if a reload is too slow do you climb to rung 5 for <code>pushState</code>."]] },
    { name: "Haptic tap when an order is ready (native shell)",
      steps: [["skip", "The server decides <em>when</em> (part 7's stream), but it can't reach the phone's motor."],
              ["skip", "No HTML element drives native APIs."],
              ["skip", "CSS can't either."],
              ["skip", "Datastar has no attribute for a native bridge."],
              ["stop", "The <code>window.BrewShell.post</code> shim from part 7. Custom JS, listed and justified in the page's one glue script."]] },
  ];

  function ladderFrames(c) {
    const frames = [];
    c.steps.forEach((s, i) => frames.push({ upto: i, verdicts: c.steps.slice(0, i + 1), note: s[1] }));
    const last = c.steps.length - 1;
    frames.push({ upto: last, verdicts: c.steps, done: true,
      note: `Stops at rung ${last + 1}, <b>${RUNGS[last]}</b>.` + (last > 0 ? ` Leave a one-line comment saying why rung${last > 1 ? "s" : ""} 1${last > 1 ? "–" + last : ""} didn't work.` : " Most Brew Board interactions stop here.") });
    return frames;
  }

  if (document.querySelector("#viz-ladder")) {
    const v = frame("#viz-ladder", "The decision ladder", "pick an interaction; try each rung in order");
    let current = 0;
    const picker = el("div", { class: "lad-picker" });
    const rungsEl = el("ol", { class: "lad-rungs" });
    const note = el("div", { class: "lad-note" });
    const s = stepper(v.controls, ladderFrames(CASES[0]), (f) => {
      clear(rungsEl);
      RUNGS.forEach((r, i) => {
        const verdict = f.verdicts[i];
        const state = !verdict ? "pending" : verdict[0];
        rungsEl.append(el("li", { class: "lad-rung " + state + (i === f.upto && !f.done ? " now" : "") },
          el("span", { class: "lad-n", text: String(i + 1) }),
          el("span", { class: "lad-name", text: r }),
          el("span", { class: "lad-mark", text: state === "stop" ? "✓ stops here" : state === "skip" ? "✗ skip" : "" })));
      });
      note.innerHTML = f.note;
      v.foot.innerHTML = CASES[current].name;
    });
    CASES.forEach((c, i) => picker.append(el("button", {
      text: c.name, class: i === 0 ? "on" : null,
      onclick: (e) => {
        current = i;
        picker.querySelectorAll("button").forEach((b) => b.classList.toggle("on", b === e.target));
        s.setFrames(ladderFrames(c));
      },
    })));
    v.stage.append(el("div", { class: "lad-grid" }, picker, el("div", null, rungsEl, note)));
  }

  if (document.querySelector("#predict-ladder")) {
    predict("#predict-ladder", {
      prompt: "Ada's receipt panel opens and closes. Nothing else depends on it. Where should its open/closed state live?",
      choices: ["An <code>_open</code> signal, with <code>data-show</code>", "On the server, re-rendered on each toggle", "In a native <code>&lt;details&gt;</code> element"],
      answer: 2,
      explain: "Rung 2. The browser keeps the state, and you get keyboard handling and accessibility with zero code and zero requests. A signal works, but it's state you now own for no gain. The server is right only if the state must survive a reload or matter to other viewers. Inside a re-rendered region, add <code>data-preserve-attr=\"open\"</code> (see below).",
    });
  }

  // ---------------------------------------------------------------- whole view vs targeted patch
  // The same toy board as lesson08.field-guide: sorted ready → making → placed, then by id.
  const ORDERS0 = [
    { id: 1, who: "Ada", drink: "Latte", status: "making" },
    { id: 2, who: "Grace", drink: "Mocha", status: "placed" },
    { id: 3, who: "Linus", drink: "Chai Latte", status: "placed" },
    { id: 4, who: "Ada", drink: "Espresso", status: "placed" },
  ];
  const RANK = { ready: 0, making: 1, placed: 2 };
  const sorted = (os) => [...os].sort((a, b) => RANK[a.status] - RANK[b.status] || a.id - b.id);
  const advanced = ORDERS0.map((o) => (o.id === 3 ? { ...o, status: "making" } : o));
  const POINTER_SLOT = 2; // zero-based: the third row, where #3 starts

  // Byte counts are what lesson08's demo prints for these exact patches.
  const BYTES = { whole: 316, targeted: 114 };

  function patchFrames(mode) {
    const before = sorted(ORDERS0);
    const afterRows = mode === 0 ? sorted(advanced) : before.map((o) => advanced.find((a) => a.id === o.id));
    const wire = mode === 0
      ? "event: datastar-patch-elements\ndata: elements <ul id=\"board\"><li class=\"making\" id=\"order-1\">…</li><li class=\"making\" id=\"order-3\">…</li><li class=\"placed\" id=\"order-2\">…</li><li class=\"placed\" id=\"order-4\">…</li></ul>"
      : "event: datastar-patch-elements\ndata: elements <li class=\"making\" id=\"order-3\">#3 Linus, Chai Latte (making)</li>";
    const atPointer = afterRows[POINTER_SLOT];
    return [
      { rows: before, wire: "", note: "Sam's pointer rests on row 3, the Advance button for #3 (Linus). The board is sorted: ready, then making, then placed." },
      { rows: before, wire: "POST /orders/3/advance  →  204, the command changes state", hl: 3, note: "Sam clicks. The server moves #3 from placed to making." },
      { rows: before, wire, hl: 3, note: mode === 0
          ? `The stream sends the whole board: ${BYTES.whole} bytes for 4 rows. The morph matches every &lt;li&gt; by id and reorders them by moving nodes, not rebuilding them.`
          : `The server sends only #order-3: ${BYTES.targeted} bytes. Every other row is untouched.` },
      { rows: afterRows, wire, hl: 3, pointer: true, note: mode === 0
          ? `Sorted and correct, but row 3 now holds <b>#${atPointer.id} (${atPointer.who})</b>. A second click advances the wrong order.`
          : `Row 3 still holds #3, now "making", sitting between two "placed" rows. Nothing moved under the pointer, but the board is no longer sorted, and any count or heading that depends on status is stale until something re-renders it.` },
    ];
  }

  if (document.querySelector("#viz-patch")) {
    const v = frame("#viz-patch", "One change, two ways to send it", "Sam advances #3; watch row 3");
    const board = el("ol", { class: "pb-board" });
    const wire = el("pre", { class: "pb-wire" });
    const s = stepper(v.controls, patchFrames(0), (f) => {
      clear(board);
      f.rows.forEach((o, i) => board.append(el("li", {
        class: ["pb-row", o.status, o.id === f.hl ? "hl" : "", i === POINTER_SLOT ? "slot" : ""].join(" "),
      }, el("span", { text: `#${o.id} ${o.who}, ${o.drink}` }), el("span", { class: "pb-status", text: o.status }),
         el("span", { class: "pb-btn", text: "Advance" + (i === POINTER_SLOT ? "  ← pointer" : "") }))));
      wire.textContent = f.wire || "(nothing on the wire yet)";
      v.foot.innerHTML = f.note;
    });
    v.controls.append(seg(["Whole view", "Targeted patch"], 0, (i) => s.setFrames(patchFrames(i))));
    v.stage.append(el("div", { class: "pb-grid" }, board, wire));
  }

  if (document.querySelector("#predict-pointer")) {
    predict("#predict-pointer", {
      prompt: "Sam's pointer rests on row 3's Advance button (order #3, placed). He clicks once. Brew Board re-renders the whole board, sorted by status. What is under his pointer now?",
      choices: ["#3's button, now showing \"making\"", "#2's button (Grace)", "Nothing: the list was rebuilt, so the button lost its hover"],
      answer: 1,
      explain: "#3 sorts above the placed orders, so it moves up to row 2 and Grace's #2 slides down into row 3. The morph keeps each &lt;li&gt; (same node, same listeners) and moves it; the hover goes to whatever is under the pointer now. Step the figure above in \"Whole view\" mode to see it.",
    });
  }

  // ---------------------------------------------------------------- out-of-order search
  // Toy latency from lesson08: max(30, 450 − 100 × length). "la" → 250 ms, "lat" → 150 ms.
  const lat = (q) => Math.max(30, 450 - 100 * q.length);
  const T_LA = 0, T_LAT = 30;
  const ARR_LA = T_LA + lat("la"), ARR_LAT = T_LAT + lat("lat");

  function raceFrames(mode) {
    const url1 = ["/search-url?q=la", "/search", "/search-seq?q=la"][mode];
    const url2 = ["/search-url?q=lat", "/search", "/search-seq?q=lat"][mode];
    const key = (u) => `GET ${u}`;
    const F = [];
    F.push({ t: T_LA, reqs: [{ q: "la", url: url1, state: "flight" }], page: "(empty)",
      note: `t=${T_LA} ms. Ada has typed "la". ${mode === 2 ? "<code>$seq</code> becomes 1. " : ""}Datastar sends <code>${key(url1)}</code>. Its abort-map key is the method, <code>GET</code>, and the URL string exactly as written in the action, <code>${url1}</code>.` });
    if (mode === 1) {
      F.push({ t: T_LAT, reqs: [{ q: "la", url: url1, state: "aborted" }, { q: "lat", url: url2, state: "flight" }], page: "(empty)",
        note: `t=${T_LAT} ms. "lat". Same method and URL string, <code>GET /search</code>, so <code>controllers.get(url)?.abort()</code> cancels the "la" request before the new one starts. The query rides in the <code>datastar</code> parameter, which isn't part of the key.` });
    } else {
      F.push({ t: T_LAT, reqs: [{ q: "la", url: url1, state: "flight" }, { q: "lat", url: url2, state: "flight" }], page: "(empty)",
        note: `t=${T_LAT} ms. "lat". The URL string differs, so it's a different abort-map key: both requests are now in flight.${mode === 2 ? " <code>$seq</code> is 2; the server records 2 as the newest for this tab." : ""}` });
    }
    F.push({ t: ARR_LAT, reqs: [{ q: "la", url: url1, state: mode === 1 ? "aborted" : "flight" }, { q: "lat", url: url2, state: "done" }], page: 'results for "lat"',
      note: `t=${ARR_LAT} ms. The fast "lat" answer arrives and is morphed in.` });
    const laEnd = ["applied", "aborted", "ignored"][mode];
    F.push({ t: ARR_LA, reqs: [{ q: "la", url: url1, state: laEnd }, { q: "lat", url: url2, state: mode === 0 ? "overwritten" : "done" }],
      page: mode === 0 ? 'results for "la"' : 'results for "lat"', bad: mode === 0,
      note: [
        `t=${ARR_LA} ms. The slow "la" answer arrives last and is applied last. Ada typed "lat" but sees results for "la", and nothing will correct it until she types again.`,
        `t=${ARR_LA} ms. The server finishes "la" (aborting is client-side; the handler ran anyway), but the connection is gone, so nothing reaches the page.`,
        `t=${ARR_LA} ms. The server sees seq 1 &lt; newest 2 for this tab and answers <code>204</code>. Datastar applies nothing for a 204.`,
      ][mode] });
    return F;
  }

  if (document.querySelector("#viz-race")) {
    const v = frame("#viz-race", "Two searches, one slow", "\"la\" takes 250 ms, \"lat\" 150 ms");
    const lanes = el("div", { class: "rc-lanes" });
    const page = el("div", { class: "rc-page" });
    const s = stepper(v.controls, raceFrames(0), (f) => {
      clear(lanes);
      f.reqs.forEach((r) => lanes.append(el("div", { class: "rc-req " + r.state },
        el("code", { text: "GET " + r.url }), el("span", { class: "rc-state", text: {
          flight: "in flight", done: "applied", overwritten: "applied, then overwritten", applied: "applied last (stale!)", aborted: "aborted by the browser", ignored: "204, ignored" }[r.state] }))));
      page.className = "rc-page" + (f.bad ? " bad" : "");
      page.textContent = "#results: " + f.page;
      v.foot.innerHTML = f.note;
    });
    v.controls.append(seg(["URL per query", "One URL", "Sequence number"], 0, (i) => s.setFrames(raceFrames(i))));
    v.stage.append(lanes, page);
  }
})();
