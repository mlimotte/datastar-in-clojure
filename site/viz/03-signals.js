// Part 3 figures: a signal graph that shows what re-runs on each write (same rules as
// engine/signals.ts: writes compare with !==, deps are re-collected per run, an effect behind a
// computed re-runs only if the computed's value changed); and one "Ask the server" round trip
// whose answer is HTML.
(function () {
  "use strict";
  const { el, svg, clear, frame, stepper, seg, predict, BREW } = EE;

  const SIGNALS = ["drink", "size", "milk", "qty", "_loading"];
  const INITIAL = { drink: "latte", size: "medium", milk: "oat", qty: 1, _loading: false };

  // The computeds and effects from lesson 3's page, as functions of a reader. Each records what it read.
  const COMPUTEDS = [
    { id: "_summary", fn: (r) => r("qty") + " × " + r("size") + (r("milk") ? " " + r("milk") : "") + " " + r("drink") },
    { id: "_unit", fn: (r) => (r("qty") == 1 ? "drink" : "drinks") },
  ];
  const EFFECTS = [
    { id: "text:unit", label: "data-text ($_unit)", fn: (r) => r("_unit") },
    { id: "text:summary", label: "data-text ($_summary)", fn: (r) => r("_summary") },
    { id: "class:busy", label: "data-class:busy", fn: (r) => r("_loading") },
    { id: "attr:disabled", label: "data-attr:disabled", fn: (r) => r("_loading") },
  ];

  function evaluate(state) {
    const comp = {};
    for (const c of COMPUTEDS) {
      const deps = new Set();
      comp[c.id] = { deps, value: c.fn((n) => { deps.add(n); return state[n]; }) };
    }
    const effs = EFFECTS.map((e) => {
      const deps = new Set();
      const value = e.fn((n) => { deps.add(n); return n in comp ? comp[n].value : state[n]; });
      return { ...e, deps, value };
    });
    return { comp, effs };
  }

  const WRITES = [
    ["qty", 2, "qty → 2"], ["qty", 3, "qty → 3"], ["milk", "almond", "milk → almond"],
    ["drink", "mocha", "drink → mocha"], ["_loading", true, "_loading → true"], ["qty", 3, "qty → 3 again"],
  ];

  // ---------- figure A ----------
  const A = frame("#viz-graph", "Who re-runs?", "click a write; the graph keeps its state until Reset");
  const W = 660, H = 230;
  const sv = svg("svg", { class: "sg-svg", viewBox: `0 0 ${W} ${H}` });
  const ui = el("div", { class: "sg-ui" });
  const payload = el("pre", { class: "sg-pre" });
  A.stage.append(el("div", { class: "sg-grid" },
    el("div", null, sv, el("div", { class: "sg-legend" }, el("span", { class: "w", text: "written" }), el("span", { class: "r", text: "re-ran / recomputed" }), el("span", { class: "p", text: "_private: never sent" }))),
    el("div", null, el("p", { class: "sg-label", text: "what the page shows" }), ui,
      el("p", { class: "sg-label", text: "@get('/quote') would send" }), payload)));

  const pos = {};
  SIGNALS.forEach((s, i) => (pos[s] = { x: 10, y: 14 + i * 38, w: 150 }));
  COMPUTEDS.forEach((c, i) => (pos[c.id] = { x: 225, y: 33 + i * 76, w: 200 }));
  EFFECTS.forEach((e, i) => (pos[e.id] = { x: 470, y: 14 + i * 46, w: 180 }));
  const put = (node, ...kids) => clear(node).append(...kids.filter(Boolean));
  const short = (v) => { const t = JSON.stringify(v); return t.length > 20 ? t.slice(0, 19) + "…\"" : t; };

  let state, ev, last;
  function draw() {
    clear(sv);
    const edges = [];
    for (const c of COMPUTEDS) for (const d of ev.comp[c.id].deps) edges.push([d, c.id]);
    for (const e of ev.effs) for (const d of e.deps) edges.push([d, e.id]);
    for (const [a, b] of edges) {
      const p = pos[a], q = pos[b];
      const x1 = p.x + p.w, y1 = p.y + 13, x2 = q.x, y2 = q.y + 13, mx = (x1 + x2) / 2;
      const hot = last && (last.ran.has(b)) && (a === last.name || last.changedComp.has(a));
      sv.append(svg("path", { d: `M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`, class: hot ? "hot" : null }));
    }
    const node = (id, label, value, cls) => {
      const p = pos[id];
      sv.append(svg("g", { class: cls }, svg("rect", { x: p.x, y: p.y, width: p.w, height: 26, rx: 5 }),
        svg("text", { x: p.x + 7, y: p.y + 17, text: label }),
        svg("text", { x: p.x + p.w - 6, y: p.y + 17, "text-anchor": "end", class: "val", text: value })));
    };
    for (const s of SIGNALS) node(s, "$" + s, short(state[s]), [last && last.name === s && last.changed ? "written" : "", s.startsWith("_") ? "priv" : ""].join(" "));
    for (const c of COMPUTEDS) node(c.id, "$" + c.id, short(ev.comp[c.id].value), ["priv", last && last.ran.has(c.id) ? "rerun" : ""].join(" "));
    for (const e of ev.effs) node(e.id, e.label, "", last ? (last.ran.has(e.id) ? "rerun" : "skip") : "");
    sv.append(svg("text", { x: 10, y: H - 2, class: "lbl", text: "SIGNALS" }), svg("text", { x: 225, y: H - 2, class: "lbl", text: "COMPUTED" }), svg("text", { x: 470, y: H - 2, class: "lbl", text: "EFFECTS (BINDINGS)" }));

    const get = (id) => ev.effs.find((e) => e.id === id).value;
    put(ui,
      el("div", null, el("b", { text: `Your ${get("text:unit")}` })),
      el("div", { class: get("class:busy") ? "busy" : null }, "Draft: ", get("text:summary")),
      el("div", null, el("button", { disabled: get("attr:disabled") || null, text: "Ask the server" })),
      el("div", { class: "muted", text: "Ask the server for a price." }));
    payload.textContent = JSON.stringify(Object.fromEntries(SIGNALS.filter((s) => !s.startsWith("_")).map((s) => [s, state[s]])));
  }
  function reset() { state = { ...INITIAL }; ev = evaluate(state); last = null; draw(); A.foot.textContent = "Our old friend, one medium oat latte. Every binding has run once and recorded what it read (the edges). Notice there's no price anywhere: that's the server's job, not ours."; }
  function write(name, v) {
    const before = ev;
    if (state[name] === v) { last = { name, changed: false, ran: new Set(), changedComp: new Set() }; draw(); A.foot.textContent = `$${name} is already ${JSON.stringify(v)}. The write compares with !== and notifies nobody. Nothing to see here.`; return; }
    state = { ...state, [name]: v };
    const after = evaluate(state);
    const ran = new Set(), changedComp = new Set(), notes = [];
    for (const c of COMPUTEDS) {
      if (!before.comp[c.id].deps.has(name)) continue;
      ran.add(c.id);
      const was = before.comp[c.id].value, now = after.comp[c.id].value;
      if (was !== now) { changedComp.add(c.id); notes.push(`$${c.id} recomputed: ${JSON.stringify(was)} → ${JSON.stringify(now)}.`); }
      else notes.push(`$${c.id} recomputed but is still ${JSON.stringify(now)}, so nothing behind it re-runs.`);
    }
    for (const e of before.effs) if (e.deps.has(name) || [...changedComp].some((c) => e.deps.has(c))) ran.add(e.id);
    ev = after; last = { name, changed: true, ran, changedComp };
    draw();
    const effCount = EFFECTS.filter((e) => ran.has(e.id)).length;
    A.foot.textContent = `Wrote $${name}. ${notes.join(" ")} ${effCount ? `${effCount} binding${effCount > 1 ? "s" : ""} re-ran.` : "No binding re-ran."}`;
  }
  A.controls.append(el("button", { text: "⟲ Reset", onclick: reset }),
    ...WRITES.map(([n, v, label]) => el("button", { text: label, onclick: () => write(n, v) })));
  reset();

  // ---------- predict: what is sent ----------
  predict("#predict-sent", {
    prompt: "Pop quiz. The page has signals <code>drink size milk qty</code>, a private <code>_loading</code>, and two computed signals, <code>_summary</code> and <code>_unit</code>. Which ones does <code>@get('/quote')</code> actually send?",
    choices: ["drink, size, milk, qty, because computed signals are never sent",
      "drink, size, milk, qty, because the computed ones happen to start with <code>_</code>",
      "drink, size, milk, qty, _summary, _unit",
      "Everything, including <code>_loading</code>"],
    answer: 1,
    explain: "The default filter excludes paths matching <code>/(^|\\.)_/</code> and nothing else. Computed signals get no special treatment: one is serialized like any other (its current value), so <code>data-computed:summary</code> without the underscore would tag along on every single request.",
  });

  // ---------- figure B: one "Ask the server" round trip ----------
  const B = frame("#viz-quote", "Ask the server", "signals out, HTML back");
  const sigBox = el("div", { class: "qr-sig" });
  const wire = el("pre", { class: "sg-pre" });
  const uiB = el("div", { class: "sg-ui" });
  B.stage.append(el("div", { class: "qr-cols" },
    el("div", null, el("p", { class: "sg-label", text: "signals" }), sigBox, el("p", { class: "sg-label", text: "page" }), uiB),
    el("div", null, el("p", { class: "sg-label", text: "wire / server" }), wire)));

  const dollars = (c) => "$" + Math.floor(c / 100) + "." + String(c % 100).padStart(2, "0");
  const CASES = [
    { label: "2 × medium oat latte", sig: { drink: "latte", size: "medium", milk: "oat", qty: 2 } },
    { label: "espresso, double, oat", sig: { drink: "espresso", size: "double", milk: "oat", qty: 1 } },
  ];
  function framesFor(ci) {
    const c = CASES[ci];
    const draft = c.sig;
    const summary = `${draft.qty} × ${draft.size} ${draft.milk} ${draft.drink}`;
    const json = JSON.stringify(draft);
    const takesMilk = BREW.byId[draft.drink].milks.length > 0;
    const err = takesMilk ? null : `${BREW.byId[draft.drink].name} does not take that milk.`;
    const cents = BREW.priceCents(draft);
    const quoteHtml = err ? `<p class="error" id="quote">${err}</p>` : `<p id="quote">Server price: <strong>${dollars(cents * draft.qty)}</strong></p>`;
    const F = [];
    const add = (sig, changed, w, quote, note) => F.push({ sig: { ...sig }, changed, w, quote, note });
    let s = { ...draft, _loading: false };
    const muted = { muted: true, text: "Ask the server for a price." };
    add(s, [], "", muted, `The draft reads "${summary}". The browser has no prices and shows none. Nothing has been sent.`);
    s = { ...s, _loading: true };
    add(s, ["_loading"], "fetch STARTED (from the button)", muted, "The click runs @get. The fetch plugin fires STARTED for the button; data-indicator on that same button sets $_loading, and data-attr:disabled and data-class:busy dutifully re-run.");
    add(s, [], `filtered(include /.*/, exclude /(^|\\.)_/) →\n${json}`, muted, "Signals are serialized. _loading, _summary and _unit get bounced at the door by the underscore.");
    add(s, [], `GET /quote?datastar=${encodeURIComponent(json)}`, muted, "GET has no body, so the JSON gets URL-encoded into the datastar query parameter, same as part 1.");
    add(s, [], `(read-signals req)\n→ ${"{"}:drink "${draft.drink}" :size "${draft.size}" :milk "${draft.milk}" :qty ${draft.qty}}\n(->draft signals)\n→ {:drink :${draft.drink} :size :${draft.size} :milk :${draft.milk} :qty ${draft.qty}}`, muted, "The server parses the parameter with cheshire into a keyword map (anything that isn't a JSON object would give {}, because we don't trust the browser), then turns the strings into keywords.");
    add(s, [], err ? `(draft-error draft)\n→ "${err}"` : `(draft-error draft) → nil\n(price-cents draft) → ${BREW.byId[draft.drink].base} + ${BREW.SIZE_DELTA[draft.size]} + ${BREW.MILK_DELTA[draft.milk]} = ${cents}\n(* ${cents} ${draft.qty}) → ${cents * draft.qty}`, muted,
      err ? "Only the server knows which drinks take milk. The static selects happily let the customer pick oat; validation says no." : "The draft is valid, so the server prices it from its own menu. This is the only place in the whole app where the pricing rule exists. One place. On purpose.");
    add(s, [], `event: datastar-patch-elements\ndata: elements ${quoteHtml}\n`, muted, "One event: an element with id quote. No signals in the answer, not a single one.");
    add(s, [], "morph #quote by id", { error: !!err, html: quoteHtml }, err ? "The browser morphs #quote into the error line. No signal holds the error, so there's nothing to clean up later: the next answer just replaces it." : "The browser morphs #quote. The wording and the currency format came from the server, and the page holds no copy of the price to go stale.");
    s = { ...s, _loading: false };
    add(s, ["_loading"], "EOF → fetch FINISHED", { error: !!err, html: quoteHtml }, "The stream ends. FINISHED drops the indicator's count to 0, $_loading is false, and the button is back in business.");
    return F;
  }
  const quoteEl = (q) => {
    if (q.muted) return el("div", { class: "muted", text: q.text });
    const d = document.createElement("div");
    d.className = q.error ? "err" : "";
    d.textContent = q.html.replace(/<[^>]+>/g, "");
    return d;
  };
  const st = stepper(B.controls, framesFor(0), (f) => {
    clear(sigBox).append(...Object.entries(f.sig).map(([k, v]) => el("div", { class: f.changed.includes(k) ? "ch" : null, text: `$${k} = ${JSON.stringify(v)}` })));
    wire.textContent = f.w || "—";
    put(uiB, el("div", null, el("button", { disabled: f.sig._loading || null, text: "Ask the server" })), quoteEl(f.quote));
    B.foot.textContent = f.note;
  });
  B.controls.append(seg(CASES.map((c) => c.label), 0, (i) => st.setFrames(framesFor(i))));
})();
