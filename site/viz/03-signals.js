// Part 3 figures: a signal graph that shows what re-runs on each write (same rules as
// engine/signals.ts: writes compare with !==, deps are re-collected per run, an effect behind a
// computed re-runs only if the computed's value changed); and one "Ask the server" round trip.
(function () {
  "use strict";
  const { el, svg, clear, frame, stepper, predict, BREW } = EE;
  const MENU = {
    base: Object.fromEntries(BREW.MENU.map((d) => [d.id, d.base])),
    size: BREW.SIZE_DELTA, milk: BREW.MILK_DELTA,
  };

  const SIGNALS = ["drink", "size", "milk", "qty", "_prices", "quote", "error", "_loading"];
  const INITIAL = { drink: "latte", size: "medium", milk: "oat", qty: 1, _prices: MENU, quote: 0, error: "", _loading: false };

  // The computed and the effects, as functions of a reader. Each records what it read.
  // estimate-expr from lesson 3: ($_prices.base[$drink] + ($_prices.size[$size] || 0) + ($_prices.milk[$milk] || 0)) * $qty
  const PRICE = (r) => (r("_prices").base[r("drink")] + (r("_prices").size[r("size")] || 0) + (r("_prices").milk[r("milk")] || 0)) * r("qty");
  const EFFECTS = [
    { id: "text:estimate", label: "data-text ($estimate)", fn: (r) => "$" + (r("price") / 100).toFixed(2) },
    { id: "class:busy", label: "data-class:busy", fn: (r) => r("_loading") },
    { id: "attr:disabled", label: "data-attr:disabled", fn: (r) => r("_loading") },
    { id: "show:quote", label: "data-show (quote)", fn: (r) => r("quote") > 0 },
    { id: "text:quote", label: "data-text ($quote)", fn: (r) => "$" + (r("quote") / 100).toFixed(2) },
    { id: "show:error", label: "data-show (error)", fn: (r) => r("error") !== "" },
    { id: "text:error", label: "data-text ($error)", fn: (r) => r("error") },
  ];

  function evaluate(state) {
    const priceDeps = new Set();
    const price = PRICE((n) => { priceDeps.add(n); return state[n]; });
    const effs = EFFECTS.map((e) => {
      const deps = new Set();
      const value = e.fn((n) => { deps.add(n); return n === "price" ? price : state[n]; });
      return { ...e, deps, value };
    });
    return { price, priceDeps, effs };
  }

  const WRITES = [
    ["qty", 2, "qty → 2"], ["milk", "almond", "milk → almond"], ["drink", "mocha", "drink → mocha"],
    ["_loading", true, "_loading → true"], ["quote", 1000, "quote → 1000 (server)"], ["error", "Sold out", "error → 'Sold out'"],
    ["qty", 2, "qty → 2 again"],
  ];

  // ---------- figure A ----------
  const A = frame("#viz-graph", "Who re-runs?", "click a write; the graph keeps its state until Reset");
  const W = 660, H = 300;
  const sv = svg("svg", { class: "sg-svg", viewBox: `0 0 ${W} ${H}` });
  const ui = el("div", { class: "sg-ui" });
  const payload = el("pre", { class: "sg-pre" });
  A.stage.append(el("div", { class: "sg-grid" },
    el("div", null, sv, el("div", { class: "sg-legend" }, el("span", { class: "w", text: "written" }), el("span", { class: "r", text: "re-ran / recomputed" }), el("span", { class: "p", text: "_private: never sent" }))),
    el("div", null, el("p", { class: "sg-label", text: "what the page shows" }), ui,
      el("p", { class: "sg-label", text: "@get('/quote') would send" }), payload)));

  const pos = {};
  SIGNALS.forEach((s, i) => (pos[s] = { x: 10, y: 14 + i * 35, w: 150 }));
  pos.price = { x: 230, y: 14 + 1.5 * 35, w: 150 };
  EFFECTS.forEach((e, i) => (pos[e.id] = { x: 470, y: 14 + i * 40, w: 180 }));
  const put = (node, ...kids) => clear(node).append(...kids.filter(Boolean));
  const fmtv = (v) => (typeof v === "object" ? "{…}" : JSON.stringify(v));

  let state, ev, last;
  function draw() {
    clear(sv);
    const edges = [];
    for (const d of ev.priceDeps) edges.push([d, "price"]);
    for (const e of ev.effs) for (const d of e.deps) edges.push([d, e.id]);
    for (const [a, b] of edges) {
      const p = pos[a], q = pos[b];
      const x1 = p.x + p.w, y1 = p.y + 13, x2 = q.x, y2 = q.y + 13, mx = (x1 + x2) / 2;
      const hot = last && (last.rerun.has(b) || (b === "price" && last.priceRan)) && (a === last.name || (a === "price" && last.priceChanged));
      sv.append(svg("path", { d: `M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`, class: hot ? "hot" : null }));
    }
    const node = (id, label, value, cls) => {
      const p = pos[id];
      sv.append(svg("g", { class: cls }, svg("rect", { x: p.x, y: p.y, width: p.w, height: 26, rx: 5 }),
        svg("text", { x: p.x + 7, y: p.y + 17, text: label }),
        svg("text", { x: p.x + p.w - 6, y: p.y + 17, "text-anchor": "end", class: "val", text: value })));
    };
    for (const s of SIGNALS) node(s, "$" + s, fmtv(state[s]), [last && last.name === s && last.changed ? "written" : "", s.startsWith("_") ? "priv" : ""].join(" "));
    node("price", "computed $estimate", String(ev.price), last && last.priceRan ? "rerun" : "");
    for (const e of ev.effs) node(e.id, e.label, "", last ? (last.rerun.has(e.id) ? "rerun" : "skip") : "");
    sv.append(svg("text", { x: 10, y: H - 2, class: "lbl", text: "SIGNALS" }), svg("text", { x: 230, y: H - 2, class: "lbl", text: "COMPUTED" }), svg("text", { x: 470, y: H - 2, class: "lbl", text: "EFFECTS (BINDINGS)" }));

    const get = (id) => ev.effs.find((e) => e.id === id).value;
    put(ui, 
      el("div", null, `Draft: ${state.qty} × ${state.drink}, ${state.size}, milk ${state.milk || "none"}`),
      el("div", { class: get("class:busy") ? "busy" : null }, "Browser estimate: ", el("b", { text: get("text:estimate") })),
      get("show:quote") ? el("div", { text: `Server quote: ${get("text:quote")}` }) : null,
      get("show:error") ? el("div", { class: "err", text: get("text:error") }) : null,
      el("div", null, el("button", { disabled: get("attr:disabled") || null, text: "Ask the server" })));
    const sent = { ...Object.fromEntries(SIGNALS.filter((s) => !s.startsWith("_")).map((s) => [s, state[s]])), estimate: ev.price };
    payload.textContent = JSON.stringify(sent);
  }
  function reset() { state = { ...INITIAL }; ev = evaluate(state); last = null; draw(); A.foot.textContent = "One medium oat latte. Every binding has run once and recorded what it read (the edges)."; }
  function write(name, v) {
    const before = ev;
    if (state[name] === v) { last = { name, changed: false, rerun: new Set(), priceRan: false }; draw(); A.foot.textContent = `$${name} is already ${JSON.stringify(v)}. The write compares with !== and notifies nobody.`; return; }
    state = { ...state, [name]: v };
    const after = evaluate(state);
    const priceRan = before.priceDeps.has(name);
    const priceChanged = priceRan && after.price !== before.price;
    const rerun = new Set(before.effs.filter((e) => e.deps.has(name) || (priceChanged && e.deps.has("price"))).map((e) => e.id));
    ev = after; last = { name, changed: true, rerun, priceRan, priceChanged };
    draw();
    let note = `Wrote $${name}. `;
    if (priceRan) note += priceChanged ? `$estimate recomputed: ${before.price} → ${after.price}. ` : `$estimate recomputed but is still ${after.price}, so nothing behind it re-runs. `;
    note += rerun.size ? `${rerun.size} binding${rerun.size > 1 ? "s" : ""} re-ran.` : "No binding re-ran.";
    A.foot.textContent = note;
  }
  A.controls.append(el("button", { text: "⟲ Reset", onclick: reset }),
    ...WRITES.map(([n, v, label]) => el("button", { text: label, onclick: () => write(n, v) })));
  reset();

  // ---------- predict: what is sent ----------
  predict("#predict-sent", {
    prompt: "The draft has signals <code>drink size milk qty quote error</code>, private <code>_prices</code> and <code>_loading</code>, and computed <code>estimate</code>. Which does <code>@get('/quote')</code> send?",
    choices: ["Only the four bound to inputs: drink, size, milk, qty", "Everything except <code>_prices</code> and <code>_loading</code>, including <code>estimate</code>", "Everything, including <code>_prices</code>", "Everything except <code>estimate</code>, because computed signals are derived"],
    answer: 1,
    explain: "The default filter excludes paths matching <code>/(^|\\.)_/</code> and nothing else. Being bound to an input doesn't matter, and a computed signal is serialized like any other (its current value).",
  });

  // ---------- figure B: one Check price round trip ----------
  const B = frame("#viz-quote", "Ask the server, qty 2", "signals out, signals back");
  const sigBox = el("div", { class: "qr-sig" });
  const wire = el("pre", { class: "sg-pre" });
  const uiB = el("div", { class: "sg-ui" });
  B.stage.append(el("div", { class: "qr-cols" },
    el("div", null, el("p", { class: "sg-label", text: "signals" }), sigBox, el("p", { class: "sg-label", text: "page" }), uiB),
    el("div", null, el("p", { class: "sg-label", text: "wire / server" }), wire)));
  const s0 = { drink: "latte", size: "medium", milk: "oat", qty: 2, quote: 0, error: "", estimate: 1120, _loading: false };
  const payloadJson = JSON.stringify({ drink: "latte", size: "medium", milk: "oat", qty: 2, quote: 0, error: "", estimate: 1120 });
  const cents = BREW.priceCents({ drink: "latte", size: "medium", milk: "oat" });
  const F = [];
  const add = (sig, changed, w, note) => F.push({ sig: { ...sig }, changed, w, note });
  let s = { ...s0 };
  add(s, [], "", "The user set qty to 2. The estimate already says $11.20, computed in the browser. Nothing has been sent.");
  s = { ...s, _loading: true };
  add(s, ["_loading"], "fetch STARTED (from the button)", "The click runs @get. The fetch plugin fires STARTED for the button; data-indicator on that same button sets $_loading, and data-attr:disabled and data-class:busy re-run.");
  add(s, [], `filtered(include /.*/, exclude /(^|\\.)_/) →\n${payloadJson}`, "Signals are serialized. _loading and _prices are filtered out; the computed estimate is included.");
  add(s, [], `GET /quote?datastar=${encodeURIComponent(payloadJson)}`, "GET has no body, so the JSON is URL-encoded into the datastar query parameter.");
  add(s, [], `(read-signals req)\n→ {:drink "latte" :size "medium" :milk "oat" :qty 2\n   :quote 0 :error "" :estimate 1120}\n(->draft signals)\n→ {:drink :latte :size :medium :milk :oat :qty 2}`, "The server parses the parameter with cheshire into a keyword map, then keeps only the draft. It ignores :estimate: the browser's arithmetic is not evidence.");
  add(s, [], `(draft-error draft) → nil\n(price-cents draft) → 450 + 50 + 60 = ${cents}\n(* ${cents} 2) → ${cents * 2}`, "The draft is valid, so the server prices it from its own menu.");
  add(s, [], `event: datastar-patch-signals\ndata: signals {"quote":${cents * 2},"error":""}\n`, "One event, signals only. No HTML.");
  s = { ...s, quote: cents * 2 };
  add(s, ["quote"], "merge patch: quote 0 → 1120 (changed)\n           error \"\" → \"\" (same: no notify)", "quote changed, so the quote line's data-show and data-text re-run. error was written with the value it already had, so its bindings don't.");
  s = { ...s, _loading: false };
  add(s, ["_loading"], "EOF → fetch FINISHED", "The stream ends. FINISHED drops the indicator's count to 0, $_loading is false, and the button is enabled again.");
  stepper(B.controls, F, (f) => {
    clear(sigBox).append(...Object.entries(f.sig).map(([k, v]) => el("div", { class: f.changed.includes(k) ? "ch" : null, text: `$${k} = ${JSON.stringify(v)}` })));
    wire.textContent = f.w || "—";
    put(uiB, el("div", null, "Browser estimate: ", el("b", { text: "$" + (f.sig.estimate / 100).toFixed(2) })),
      f.sig.quote > 0 ? el("div", null, "Server quote: ", el("b", { text: "$" + (f.sig.quote / 100).toFixed(2) })) : null,
      el("button", { disabled: f.sig._loading || null, text: "Ask the server" }));
    B.foot.textContent = f.note;
  });
})();
