// Part 4 figures: a state-placement sorter, and the server-rendered drop-down, step by step,
// with the exact responses lesson04's drink-options-response produces.
(function () {
  "use strict";
  const { el, clear, frame, stepper, seg, predict, BREW } = EE;

  // ---------- figure A: sorter ----------
  const HOMES = ["server", "server-rendered HTML", "signal", "_signal"];
  const ITEMS = [
    ["Ada's cart lines", [0], "Must survive a reload and be checked against stock. The browser sees the cart only as HTML."],
    ["The list of milks offered for the chosen drink", [1], "Derived from menu and stock. It's markup; the selected milk is the signal."],
    ["The quantity Ada is typing", [2], "Uncommitted input. It travels with \"Add to cart\", where the server validates it."],
    ["Whether the \"customize\" panel is expanded", [3], "Pure presentation in this tab. The server has no use for it, so keep it out of every payload."],
    ["That oat milk is sold out", [0], "Shared between every customer and the barista. Customers see it as HTML (a disabled option)."],
    ["The text in a drink search box", [2], "Input the server needs to filter with, so it must not start with _."],
    ["Whether a request is in flight", [3], "data-indicator:_loading: transient, local, never sent."],
    ["Order #12's status, \"making\"", [0], "Sam changes it; Ada must see it. Shared state."],
    ["A quick price estimate while configuring", [2, 3], "A data-computed for display. Sent or not (lesson 3 sends $estimate; _estimate would not be), the server never trusts it."],
    ["The customer name for the order", [2], "Input, sent with \"Place order\"."],
  ];
  const A = frame("#viz-sorter", "Where does it live?", "pick a home for each; the verdict appears as you go");
  const score = el("span", { class: "so-score" });
  A.controls.append(score, el("button", { text: "⟲ Reset", onclick: () => build() }));
  function build() {
    const picks = new Array(ITEMS.length).fill(null);
    const list = el("div", { class: "so-list" });
    const update = () => {
      const done = picks.filter((p) => p !== null).length;
      const right = picks.filter((p, i) => p !== null && ITEMS[i][1].includes(p)).length;
      score.textContent = `${right} / ${done} right, ${ITEMS.length - done} to go`;
    };
    ITEMS.forEach(([q, ans, why], i) => {
      const whyEl = el("div", { class: "so-why", hidden: true });
      const item = el("div", { class: "so-item" }, el("div", { class: "so-q", text: q }));
      const btns = HOMES.map((h, j) => el("button", { text: h, onclick: () => {
        picks[i] = j;
        btns.forEach((b, k) => b.classList.toggle("on", k === j));
        const ok = ans.includes(j);
        item.classList.toggle("right", ok); item.classList.toggle("wrong", !ok);
        whyEl.hidden = false;
        whyEl.textContent = (ok ? "✓ " : `✗ ${ans.map((a) => HOMES[a]).join(" or ")}. `) + why;
        update();
      } }));
      item.append(el("div", { class: "so-opts" }, btns), whyEl);
      list.append(item);
    });
    clear(A.stage).append(list);
    A.foot.textContent = "Rules in order: shared or durable → server; derived from server data → server-rendered HTML; uncommitted input → signal; presentation only → _signal.";
    update();
  }
  build();

  // ---------- figure B: the drop-down ----------
  const NAMES = Object.fromEntries(BREW.MENU.map((d) => [d.id, d.name]));
  const label = (k) => k.charAt(0).toUpperCase() + k.slice(1);
  // Same markup as lesson04's drink-options-view rendered by hiccup (attributes sorted).
  function optionsHtml(drink, soldOut, { size, milk }) {
    const d = BREW.byId[drink];
    const opt = (v, sel, dis, text) => `<option${dis ? ' disabled=""' : ""}${sel ? ' selected=""' : ""} value="${v}">${text}</option>`;
    let h = `<div id="drink-options"><label>Size <select data-bind:size="" id="size">` +
      d.sizes.map((s) => opt(s, s === size, false, label(s))).join("") + `</select></label>`;
    h += d.milks.length
      ? `<label> Milk <select data-bind:milk="" id="milk">` + d.milks.map((m) => opt(m, m === milk, soldOut.includes(m), label(m) + (soldOut.includes(m) ? " (sold out)" : ""))).join("") + `</select></label>`
      : `<p class="muted">No milk</p>`;
    return h + `</div>`;
  }
  function validChoice(drink, soldOut, { size, milk }) {
    const d = BREW.byId[drink], inStock = d.milks.filter((m) => !soldOut.includes(m));
    return { size: d.sizes.includes(size) ? size : d.sizes[0], milk: inStock.includes(milk) ? milk : inStock[0] ?? null };
  }
  // Responses captured from (drink-options-response signals sold-out) in lesson04.
  const CAPTURED = {
    espresso: ['<div id="drink-options"><label>Size <select data-bind:size="" id="size"><option selected="" value="single">Single</option><option value="double">Double</option></select></label><p class="muted">No milk</p></div>', '{"size":"single","milk":""}'],
    cappuccino: ['<div id="drink-options"><label>Size <select data-bind:size="" id="size"><option selected="" value="small">Small</option><option value="medium">Medium</option></select></label><label> Milk <select data-bind:milk="" id="milk"><option selected="" value="whole">Whole</option><option value="oat">Oat</option></select></label></div>', '{"size":"small","milk":"whole"}'],
    chai: ['<div id="drink-options"><label>Size <select data-bind:size="" id="size"><option selected="" value="medium">Medium</option><option value="large">Large</option></select></label><label> Milk <select data-bind:milk="" id="milk"><option selected="" value="whole">Whole</option><option disabled="" value="oat">Oat (sold out)</option><option value="almond">Almond</option></select></label></div>', '{"milk":"whole"}'],
    mocha: ['<div id="drink-options"><label>Size <select data-bind:size="" id="size"><option value="small">Small</option><option selected="" value="medium">Medium</option><option value="large">Large</option></select></label><label> Milk <select data-bind:milk="" id="milk"><option value="whole">Whole</option><option selected="" value="oat">Oat</option><option value="almond">Almond</option></select></label></div>', "{}"],
  };
  const SCENARIOS = [
    { label: "Latte → Espresso", from: { drink: "latte", size: "medium", milk: "oat" }, to: "espresso", soldOut: [], before: [],
      who: "Ada has a medium oat latte and picks Espresso." },
    { label: "Latte → Cappuccino", from: { drink: "latte", size: "large", milk: "almond" }, to: "cappuccino", soldOut: [], before: [],
      who: "Linus has a large almond latte and picks Cappuccino." },
    { label: "→ Chai, oat sold out", from: { drink: "latte", size: "medium", milk: "oat" }, to: "chai", soldOut: ["oat"], before: [],
      who: "Grace's page loaded before Sam marked oat sold out. She still has oat selected, and picks Chai Latte." },
    { label: "Latte → Mocha", from: { drink: "latte", size: "medium", milk: "oat" }, to: "mocha", soldOut: [], before: [],
      who: "Ada picks Mocha, which allows medium and oat." },
  ];

  function framesFor(sc) {
    const sig0 = { drink: sc.from.drink, size: sc.from.size, milk: sc.from.milk, qty: 1, error: "" };
    const choice = validChoice(sc.to, sc.soldOut, sc.from);
    const [elHtml, sigJson] = CAPTURED[sc.to];
    const mine = optionsHtml(sc.to, sc.soldOut, choice);
    if (mine !== elHtml) console.error("drop-down figure: JS markup differs from lesson04 output", sc.to);
    const patch = JSON.parse(sigJson);
    const payload = JSON.stringify({ ...sig0, drink: sc.to });
    const F = [];
    const add = (o) => F.push(o);
    const base = { drink: sc.from.drink, opts: optionsHtml(sc.from.drink, sc.before, sc.from), sig: sig0, ch: [], wire: "", flash: false };
    add({ ...base, note: sc.who + " Before the change: the options for " + NAMES[sc.from.drink] + " are HTML the server rendered at page load." });
    const s1 = { ...sig0, drink: sc.to };
    add({ ...base, drink: sc.to, sig: s1, ch: ["drink"], note: "change fires. data-bind:drink (first in source order) sets $drink; then data-on:change runs @get('/drink-options')." });
    add({ ...base, drink: sc.to, sig: s1, wire: `GET /drink-options?datastar=…\n${payload}`, note: "Every non-_ signal goes along: the new drink, and the old size and milk, which may no longer fit." });
    const changedTxt = Object.keys(patch).length ? Object.entries(patch).map(([k, v]) => `:${k} ${JSON.stringify(v)}`).join(" ") : "";
    add({ ...base, drink: sc.to, sig: s1,
      wire: `(valid-choice :${sc.to} #{${sc.soldOut.map((m) => ":" + m).join(" ")}} {:size :${sc.from.size} :milk :${sc.from.milk}})\n→ {:size :${choice.size} :milk ${choice.milk ? ":" + choice.milk : "nil"}}\nchanged → {${changedTxt}}`,
      note: "The server keeps each value that is still allowed and in stock, and otherwise takes the first allowed one." });
    add({ ...base, drink: sc.to, sig: s1, opts: elHtml, flash: true, wire: BREW.patchElements(elHtml), note: "Event 1: #drink-options, matched by id and morphed. The chosen options carry selected=\"\"." });
    const s2 = { ...s1, ...patch };
    add({ ...base, drink: sc.to, sig: s2, opts: elHtml, ch: Object.keys(patch), wire: BREW.patchSignals(sigJson),
      note: Object.keys(patch).length ? `Event 2: only the signals that no longer fit${"milk" in patch && patch.milk === "" ? ". No milk is \"\", not null: null would delete $milk." : "."}` : "Event 2: nothing needed changing, so the patch is {} and does nothing." });
    add({ ...base, drink: sc.to, sig: s2, opts: elHtml, note: `Done. The next "Add to cart" sends ${JSON.stringify({ drink: s2.drink, size: s2.size, milk: s2.milk })}, which matches what the selects show.` });
    return F;
  }

  const pm = el("div");
  document.querySelector("#predict-milk").replaceWith(pm);
  const B = frame("#viz-dropdown", "The server-rendered drop-down", "exact responses from drink-options-response");
  const ui = el("div", { class: "dd-ui" });
  const sigBox = el("div", { class: "dd-sig" });
  const wire = el("pre", { class: "dd-wire" });
  B.stage.append(el("div", { class: "dd-grid" },
    el("div", null, el("p", { class: "dd-label", text: "the page" }), ui, el("p", { class: "dd-label", text: "signals" }), sigBox),
    el("div", null, el("p", { class: "dd-label", text: "wire / server" }), wire)));
  const st = stepper(B.controls, framesFor(SCENARIOS[0]), (f) => {
    const drinkSel = el("select", { disabled: true }, BREW.MENU.map((d) => el("option", { value: d.id, text: d.name, selected: d.id === f.drink || null })));
    const opts = el("div", { html: f.opts, class: f.flash ? "flash" : null });
    opts.querySelectorAll("select").forEach((s) => (s.disabled = true));
    clear(ui).append(el("label", null, "Drink ", drinkSel), opts);
    clear(sigBox).append(...Object.entries(f.sig).map(([k, v]) => el("div", { class: f.ch.includes(k) ? "ch" : null, text: `$${k} = ${JSON.stringify(v)}` })));
    wire.textContent = f.wire || "—";
    B.foot.textContent = f.note;
  });
  const scSeg = seg(SCENARIOS.map((s) => s.label), 0, (i) => st.setFrames(framesFor(SCENARIOS[i])));
  B.controls.append(scSeg);

  const pr = predict(pm, {
    prompt: "Ada has a medium oat latte and switches the drink to Espresso, which takes no milk. After the response, what is <code>$milk</code>?",
    choices: ["<code>\"oat\"</code>: unchanged", "<code>null</code>", "<code>\"\"</code>: an empty string", "It no longer exists"],
    answer: 2,
    explain: "The server sends <code>{\"size\":\"single\",\"milk\":\"\"}</code>. <code>null</code> would have removed the signal (merge-patch semantics), dropping <code>milk</code> from every later request. The figure below now shows this case at its last step.",
    onLock() { scSeg.querySelectorAll("button")[0].click(); st.go(5); pr.reveal(); },
  });
})();
