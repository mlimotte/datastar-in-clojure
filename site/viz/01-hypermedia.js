// Part 1 figures: the click round trip, step by step; and what each response type does.
(function () {
  "use strict";
  const { el, clear, frame, stepper, seg, predict } = EE;

  const OLD = "Nobody has said hello yet.";
  const NEW = "Hello from the server! Visit number 1.";
  const html = (t, changed) => el("div", { class: "rt-dom" },
    el("div", null, '<div id="greeting">', el("span", { class: changed ? "changed" : null, text: t }), "</div>"),
    el("div", null, `<button data-on:click="@get('/hello')">Say hello (SSE)</button>`));

  // ---------- figure A: the round trip ----------
  function frames(variant) {
    const sse = variant === 0;
    const req = "GET /hello?datastar=%7B%7D HTTP/1.1\nAccept: text/event-stream, text/html, application/json\nDatastar-Request: true";
    const body = sse
      ? 'event: datastar-patch-elements\ndata: elements <div id="greeting"><strong>Hello from the server!</strong> Visit number 1.</div>\n\n'
      : '<div id="greeting"><strong>Hello from the server!</strong> Visit number 1.</div>';
    const resp = `HTTP/1.1 200 OK\nContent-Type: ${sse ? "text/event-stream" : "text/html"}${sse ? "\nCache-Control: no-cache" : ""}\n\n${body}`;
    const f = [];
    const add = (where, wire, dom, note) => f.push({ where, wire, dom, note });
    add("browser", "", [OLD, false], "The page has loaded. Datastar has scanned the DOM and attached a click listener for data-on:click.");
    add("browser", "@get('/hello')  →  __action(\"get\", evt, '/hello')", [OLD, false],
      "Click. Datastar evaluates the attribute's expression. The @get action was rewritten to a call into the fetch plugin.");
    add("browser", "filtered(signals) → {}\nJSON.stringify → \"{}\"", [OLD, false],
      "The fetch plugin collects the signals it may send. There are none yet, so the JSON is {}.");
    add("network", req, [OLD, false],
      "GET has no body, so the JSON goes in the query parameter datastar. {} URL-encodes to %7B%7D. Two headers identify the request.");
    add("server", "(case [request-method uri] … [:get \"/hello\"] …)\n(hello-response (swap! visits inc)) → {:status 200 :headers … :body …}", [OLD, false],
      "The server matches [:get \"/hello\"], counts the visit, and calls hello-response. It returns a map; http-kit writes it.");
    add("network", resp, [OLD, false],
      sse ? "One event: a type line, one data line per line of HTML, then a blank line. The connection closes after the body."
          : "Plain HTML. Datastar will treat the whole body as one patch-elements event.");
    if (sse) {
      add("browser", 'line "event: datastar-patch-elements"  → event = datastar-patch-elements\nline "data: elements <div id=…>"     → data += elements <div id=…>\nline ""                            → dispatch',
        [OLD, false], "fetch hands over bytes. Datastar's parser splits them into lines; a blank line ends the event and dispatches it.");
      add("browser", 'split each data line at the first space:\n{ elements: "<div id=\\"greeting\\"><strong>Hello from the server!</strong> Visit number 1.</div>" }',
        [OLD, false], "The type starts with datastar, so it is applied. Each data line is key + space + value; lines with the same key are joined with \\n.");
    } else {
      add("browser", 'Content-Type includes text/html →\n{ elements: <body>, selector: header datastar-selector (none), mode: header datastar-mode (none) }',
        [OLD, false], "No stream to parse. The body becomes the elements argument; options would come from datastar-* response headers.");
    }
    add("browser", 'parse HTML → top-level <div id="greeting">\nno selector, mode outer → document.getElementById("greeting")\nmorph(old, new)',
      [OLD, false], "No selector was given, so the target is found by the new element's id. The morph compares old and new.");
    add("browser", "", [NEW, true], "The morph changed the div's children to match; the div itself and the buttons, with their listeners, were not replaced.");
    return f;
  }

  const A = frame("#viz-roundtrip", "One click, one patch", "browser → server → browser");
  const lanes = ["browser", "network", "server"];
  const laneTags = lanes.map((l) => el("span", { class: "tag " + (l === "server" ? "teal" : l === "network" ? "violet" : "accent"), text: l }));
  const wire = el("pre", { class: "rt-wire" });
  const page = el("div", { class: "rt-page" });
  A.stage.append(el("div", { class: "rt-lane" }, laneTags),
    el("div", { class: "rt-grid" },
      el("div", { class: "rt-box" }, el("p", { class: "rt-label", text: "what is in flight / being computed" }), wire),
      el("div", null, el("p", { class: "rt-label", text: "the page's DOM" }), page)));
  const sA = stepper(A.controls, frames(0), (f) => {
    laneTags.forEach((t, i) => t.classList.toggle("on", lanes[i] === f.where));
    wire.textContent = f.wire || "—";
    clear(page).append(html(f.dom[0], f.dom[1]));
    A.foot.textContent = f.note;
  });
  A.controls.append(seg(["SSE response", "text/html response"], 0, (i) => sA.setFrames(frames(i))));

  // ---------- figure B: what each response does ----------
  const CASES = [
    { label: "SSE", status: 200, ct: "text/event-stream",
      body: 'event: datastar-patch-elements\ndata: elements <div id="greeting"><strong>Hello from the server!</strong> Visit number 1.</div>\n',
      out: [NEW, true], log: [["ok", "applied 1 patch-elements event"]] },
    { label: "text/html", status: 200, ct: "text/html",
      body: '<div id="greeting"><strong>Hello from the server!</strong> Visit number 1.</div>',
      out: [NEW, true], log: [["ok", "body treated as one patch-elements event"]] },
    { label: "JSON", status: 200, ct: "application/json",
      body: '{"greeted": true}',
      out: [OLD, false], log: [["ok", "body treated as one patch-signals event: $greeted = true"], ["", "no element changed (no data-text reads $greeted yet)"]] },
    { label: "204", status: 204, ct: "(none)", body: "",
      out: [OLD, false], log: [["", "status is not 200 and is 204: no retry, nothing applied"]] },
    { label: "typo id", status: 200, ct: "text/event-stream",
      body: 'event: datastar-patch-elements\ndata: elements <div id="greting"><strong>Hello from the server!</strong> Visit number 1.</div>\n',
      out: [OLD, false], log: [["warn", "console.warn: PatchElementsNoTargetsFound { element: { id: \"greting\" } }"], ["", "no exception, no retry; the page is unchanged"]] },
    { label: "500", status: 500, ct: "text/html", body: "<p>Internal error</p>",
      out: [OLD, false], log: [["warn", "datastar-fetch event: error, status 500"], ["", "body ignored: only a 200 is applied"]] },
  ];
  const pm = el("div");
  document.querySelector("#viz-content-type").before(pm);
  const B = frame("#viz-content-type", "Same click, different answers", "pick what the server returns");
  const respPre = el("pre", { class: "rt-wire" });
  const pageB = el("div", { class: "rt-page" });
  const logB = el("div", { class: "ct-out" });
  B.stage.append(el("div", { class: "rt-grid" },
    el("div", { class: "rt-box" }, el("p", { class: "rt-label", text: "response" }), respPre),
    el("div", null, el("p", { class: "rt-label", text: "page after" }), pageB, el("p", { class: "rt-label", text: "browser" }), logB)));
  function showCase(i) {
    const c = CASES[i];
    respPre.textContent = `HTTP/1.1 ${c.status}\nContent-Type: ${c.ct}\n\n${c.body}`;
    clear(pageB).append(html(c.out[0], c.out[1]));
    clear(logB).append(...c.log.map(([k, t]) => el("div", { class: k, text: (k === "warn" ? "⚠ " : k === "ok" ? "✓ " : "· ") + t })));
    B.foot.textContent = i === 4 ? "A patch with no selector targets the element whose id matches. No match: a warning in the console, and that's all." : "";
  }
  const segB = seg(CASES.map((c) => c.label), 0, showCase);
  B.controls.append(segB);
  showCase(0);

  const pr = predict(pm, {
    prompt: "The handler has a typo: it sends <code>&lt;div id=\"greting\"&gt;</code> (one <i>e</i>). There is no element with that id. What does the browser do?",
    choices: ["Throws an error and retries the request", "Appends the div to <code>&lt;body&gt;</code>", "Nothing visible; logs a console warning", "Replaces the whole page"],
    answer: 2,
    explain: "Without a selector, the target is <code>getElementById</code> of the new element's id. When that returns null, <code>patchElements.ts</code> calls <code>console.warn</code> and moves on. Silent failures like this are why the lesson's tests check ids in the HTML they produce.",
    onLock() { segB.querySelectorAll("button")[4].click(); pr.reveal(); },
  });
})();
