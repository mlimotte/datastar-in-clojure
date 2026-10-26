// Part 6 figures: broadcast fan-out with real render + dedupe, a morph walk (outer vs replace), two predicts.
(function () {
  "use strict";
  const { el, clear, frame, stepper, seg, predict } = EE;

  // ---------- tiny Brew Board model + renderers (strings, so dedupe is real) ----------
  const DR = { latte: "Latte", mocha: "Mocha", chai: "Chai Latte", espresso: "Espresso" };
  const MILKS = ["whole", "oat", "almond"];
  const cap = (s) => s[0].toUpperCase() + s.slice(1);
  function initial() {
    return {
      soldOut: [],
      carts: { ada: [], grace: [] },
      // per-tab state, keyed by tab id; Ada has two tabs open
      tabs: { t1: { sid: "ada", drink: "latte" }, t2: { sid: "ada", drink: "latte" }, g1: { sid: "grace", drink: "mocha" } },
      orders: [{ id: 1, sid: "ada", customer: "Ada", status: "placed", line: "1 × Latte, medium, oat" },
               { id: 2, sid: "grace", customer: "Grace", status: "making", line: "2 × Espresso, double" }],
    };
  }
  const milkSelect = (st) => `<select id="milk">` + MILKS.map((m) => st.soldOut.includes(m)
    ? `<option value="${m}" disabled>${cap(m)} (sold out)</option>` : `<option value="${m}">${cap(m)}</option>`).join("") + `</select>`;
  function customerView(st, tab) {
    const { sid, drink } = st.tabs[tab];
    const cart = st.carts[sid].map((l) => `<li>${l.qty} × ${DR[l.drink]}, ${l.size}, ${l.milk}</li>`).join("");
    const mine = st.orders.filter((o) => o.sid === sid).map((o) => `<li id="order-${o.id}">#${o.id} ${o.line} (${o.status})</li>`).join("");
    const drinks = Object.keys(DR).map((d) => `<option value="${d}"${d === drink ? " selected" : ""}>${DR[d]}</option>`).join("");
    return `<main id="app"><section id="draft"><select id="drink">${drinks}</select><div id="drink-options"><select id="size">…</select>${milkSelect(st)}</div><input id="customer" data-bind:customer></section><section id="cart"><ul>${cart}</ul></section><ul id="my-orders">${mine}</ul></main>`;
  }
  function boardView(st) {
    const col = (s) => `<section id="col-${s}"><ul>` + st.orders.filter((o) => o.status === s).map((o) => `<li id="board-order-${o.id}">#${o.id} ${o.customer}: ${o.line}</li>`).join("") + `</ul></section>`;
    return `<main id="app"><section id="stock">` + MILKS.map((m) => `<button id="stock-${m}">${m}${st.soldOut.includes(m) ? " (sold out)" : ""}</button>`).join("") + `</section>${["placed", "making", "ready"].map(col).join("")}</main>`;
  }
  const SUBS = [
    { key: "t1", title: "Ada · tab 1", who: "{:kind :customer :sid ada :tabid t1}", view: (st) => customerView(st, "t1") },
    { key: "t2", title: "Ada · tab 2", who: "{:kind :customer :sid ada :tabid t2}", view: (st) => customerView(st, "t2") },
    { key: "g1", title: "Grace", who: "{:kind :customer :sid grace :tabid g1}", view: (st) => customerView(st, "g1") },
    { key: "sam", title: "Sam (board)", who: "{:kind :board}", view: boardView },
  ];
  const COMMANDS = [
    { label: "Sam advances #1", cmd: "{:type :advance :id 1}", apply: (st) => { st.orders[0].status = "making"; } },
    { label: "Grace adds a mocha", cmd: '{:type :add-to-cart :sid "grace" :tabid "g1" :line {:drink :mocha …}}', apply: (st) => { st.carts.grace.push({ drink: "mocha", size: "small", milk: "whole", qty: 1 }); } },
    { label: "Ada, tab 1: Chai", cmd: '{:type :choose-draft :sid "ada" :tabid "t1" :draft {:drink :chai …}}', apply: (st) => { st.tabs.t1.drink = "chai"; } },
    { label: "Sam: oat sold out", cmd: "{:type :toggle-stock :milk :oat}", apply: (st) => { st.soldOut.push("oat"); } },
  ];
  const evBytes = (html) => new TextEncoder().encode(`event: datastar-patch-elements\ndata: elements ${html}\n\n`).length;

  function fanFrames(ci) {
    const st = initial();
    const last = Object.fromEntries(SUBS.map((s) => [s.key, s.view(st)]));
    const F = [];
    const base = { active: null, status: {} };
    F.push({ ...base, top: "Four streams are open: Ada has two tabs (one sid cookie, two tab ids). Each subscriber’s :last is the HTML it was last sent.", note: "Pick a command, then step. Go on, pick one." });
    COMMANDS[ci].apply(st);
    F.push({ ...base, top: `POST → (swap! state handle-command ${COMMANDS[ci].cmd})\n→ 204 No Content\n→ add-watch fires broadcast!`, note: "The command handler is done. Finished. Clocked out. Everything below happens in the watch." });
    const status = {};
    let sent = 0, bytes = 0;
    for (const s of SUBS) {
      const html = s.view(st);
      const same = html === last[s.key];
      status[s.key] = same ? { cls: "skipped", text: "same html → skip" } : { cls: "sent", text: `changed → send ${evBytes(html).toLocaleString("en-US")} B` };
      if (!same) { sent++; bytes += evBytes(html); }
      F.push({ active: s.key, status: { ...status }, top: `(view-for ${s.who} state) → ${html.length.toLocaleString("en-US")} chars\n(= html :last) → ${same}`,
        note: same ? `${s.title}'s view renders byte-for-byte the same, so nothing is sent. Not a byte.` : `${s.title}'s view changed: one patch-elements event with the whole #app goes down that stream.` });
    }
    F.push({ active: null, status: { ...status }, top: `Sent ${sent} of ${SUBS.length} streams, ${bytes.toLocaleString("en-US")} bytes uncompressed.`, note: "Notice that no command named a view. Each subscriber's own render decided whether it was affected, which is exactly the point." });
    return F;
  }

  const v = frame("#viz-fanout", "broadcast!", "one command, four open streams (two are Ada's tabs), render each, skip the identical ones");
  const top = el("div", { class: "bc-top" });
  const grid = el("div", { class: "bc-grid" });
  const cards = {};
  SUBS.forEach((s) => {
    const st = el("div", { class: "st", text: "" });
    const c = el("div", { class: "bc-sub" }, el("h4", { text: s.title }), el("div", { class: "who", text: s.who }), st);
    cards[s.key] = { c, st };
    grid.append(c);
  });
  v.stage.append(top, grid);
  let ci = 0;
  const s = stepper(v.controls, fanFrames(0), (f) => {
    top.textContent = f.top;
    SUBS.forEach((sub) => {
      const { c, st } = cards[sub.key];
      const x = f.status[sub.key];
      c.className = "bc-sub" + (f.active === sub.key ? " active" : "") + (x ? " " + x.cls : "");
      st.textContent = x ? x.text : "waiting";
    });
    v.foot.textContent = f.note;
  });
  v.controls.append(seg(COMMANDS.map((c) => c.label), 0, (i) => { ci = i; s.setFrames(fanFrames(i)); }));

  // ---------- predict: selected after the option list changes ----------
  predict("#predict-selected", {
    prompt: "Suppose the server rendered <b>no</b> <code>selected</code> attributes. Ada has Latte, <b>Medium</b> (Latte sizes: Small, Medium, Large). She switches the drink to Chai Latte (sizes: Medium, Large). Medium is still valid, so no signal patch is sent; her stream re-renders <code>#drink-options</code>. So what does the size drop-down show? (This one's sneaky.)",
    choices: ["Medium", "Large", "Small", "Nothing selected"],
    answer: 1,
    explain: "The options have no ids, so the morph pairs them by position. Position 2 (Medium, selected) is rewritten into Large and keeps its selected property; position 1 becomes Medium, unselected. No option's <code>selected</code> attribute changed presence, so the morph flipped no property and fired no <code>datastar-prop-change</code>: <code>data-bind</code> doesn't re-sync, and <code>$size</code> stays <code>medium</code>. The page now quietly disagrees with itself. Rendering <code>selected</code> from the stored draft fixes it.",
  });

  // ---------- predict: two tabs, one cookie ----------
  predict("#predict-tabs", {
    prompt: "Suppose the draft were stored per <b>sid cookie</b>: <code>:drafts {sid {:drink :size :milk}}</code>. Ada has Brew Board open in two tabs, both on Latte, Small. In tab A she switches to Chai Latte (sizes: Medium, Large); the server fits her draft to Chai, Medium and re-renders every stream of her sid. What happens over in tab B?",
    choices: ["Nothing: tab B still shows Latte, Small", "Tab B shows Chai, Medium and $size is medium", "Tab B shows Chai, Medium but its $size is still small", "Tab B reloads"],
    answer: 2,
    explain: "Both tabs send the same cookie, so both streams render the one stored draft. Tab B's <code>#drink</code> option flips its <code>selected</code> attribute, the morph sets the property and fires <code>datastar-prop-change</code>, and <code>data-bind</code> copies <code>chai</code> into B's <code>$drink</code>. But B's first size option keeps its <code>selected</code> attribute (Small selected became Medium selected), so no property flips and <code>$size</code> stays <code>small</code>. B shows Chai, Medium; its next Add to cart sends size small and fails, and good luck explaining that to Ada. We reproduced exactly this in Chrome. Keying the draft by a tab id fixes it.",
  });

  // ---------- predict: focus ----------
  const pf = predict("#predict-focus", {
    prompt: "Ada has typed <code>Ad</code> into <code>&lt;input id=\"customer\" data-bind:customer&gt;</code> and the cursor is in the box. Sam advances an order and Ada's stream delivers her whole <code>#app</code> (default mode, outer). What happens to the box she's typing in?",
    choices: ["It keeps “Ad” and keeps focus", "It keeps “Ad” but loses focus", "It is emptied and loses focus"],
    answer: 0,
    explain: "The morph changes the existing input in place: same DOM node, so focus stays; and it only sets <code>value</code> when the <code>value</code> attribute differs between old and new (here both have none). Step through the figure below, then switch it to <code>replace</code> to get the third answer, the one you really don't want.",
  });

  // ---------- morph walk ----------
  // Old tree has a focused input with typed text; new tree = same view after "advance #1" + "oat sold out".
  const OLD = [
    [0, "main#app"],
    [1, "section#draft"],
    [2, "select#drink  (6 options)"],
    [2, "div#drink-options"],
    [3, "select#size  (3 options)"],
    [3, "select#milk"],
    [4, "option whole"],
    [4, "option oat"],
    [4, "option almond"],
    [2, "input#customer"],
    [1, "section#cart  (empty)"],
    [1, "ul#my-orders"],
    [2, "li#order-1  “#1 Latte (placed)”"],
  ];
  const NEW = [
    [0, "main#app"],
    [1, "section#draft"],
    [2, "select#drink  (6 options)"],
    [2, "div#drink-options"],
    [3, "select#size  (3 options)"],
    [3, "select#milk"],
    [4, "option whole"],
    [4, "option oat disabled  “Oat (sold out)”"],
    [4, "option almond"],
    [2, "input#customer"],
    [1, "section#cart  (empty)"],
    [1, "ul#my-orders"],
    [2, "li#order-1  “#1 Latte (making)”"],
  ];
  // per-step: index, marks for old tree, message
  const MORPH = [
    { i: null, m: {}, msg: "Old DOM on the left (input focused, “Ad” typed). New HTML from the stream on the right. Persistent ids: every id appears in both, same tag." },
    { i: 0, m: { 0: "cur" }, msg: "main#app: same id, same tag → morph in place. Attributes equal. Not isEqualNode (something inside differs) → walk children." },
    { i: 1, m: { 0: "kept", 1: "cur" }, msg: "section#draft: hard match by id set → same node. Its subtree differs (the oat option) → walk children." },
    { i: 2, m: { 0: "kept", 1: "kept", 2: "skip" }, msg: "select#drink: isEqualNode → whole subtree skipped. Nothing touched. The cheapest kind of work: none." },
    { i: 3, m: { 0: "kept", 1: "kept", 2: "skip", 3: "cur" }, msg: "div#drink-options differs below → walk. select#size equal → skipped." },
    { i: 7, m: { 0: "kept", 1: "kept", 2: "skip", 3: "kept", 4: "skip", 5: "kept", 6: "skip", 7: "chg" }, msg: "select#milk → option oat: soft match (same tag, no id). setAttribute('disabled'), text node “Oat” → “Oat (sold out)”. Same node." },
    { i: 9, m: { 0: "kept", 1: "kept", 2: "skip", 3: "kept", 4: "skip", 5: "kept", 6: "skip", 7: "chg", 8: "skip", 9: "cur" }, msg: "input#customer: input rule. value attribute old = null, new = null → leave .value alone. “Ad” and the focus stay." },
    { i: 10, m: { 0: "kept", 1: "kept", 2: "skip", 3: "kept", 4: "skip", 5: "kept", 6: "skip", 7: "chg", 8: "skip", 9: "kept", 10: "skip" }, msg: "section#cart: isEqualNode → skipped." },
    { i: 12, m: { 0: "kept", 1: "kept", 2: "skip", 3: "kept", 4: "skip", 5: "kept", 6: "skip", 7: "chg", 8: "skip", 9: "kept", 10: "skip", 11: "kept", 12: "chg" }, msg: "ul#my-orders → li#order-1: same node; its text node “placed” → “making”." },
    { i: null, final: true, m: { 0: "kept", 1: "kept", 2: "skip", 3: "kept", 4: "skip", 5: "kept", 6: "skip", 7: "chg", 8: "skip", 9: "kept", 10: "skip", 11: "kept", 12: "chg" }, msg: "Done. 0 nodes created, 0 removed, 1 attribute set, 2 text nodes changed. Focus and “Ad” intact. Ada never noticed a thing." },
  ];
  const REPLACE = [
    { i: null, m: {}, msg: "Same old DOM and new HTML, but the event says mode replace." },
    { i: 0, m: Object.fromEntries(OLD.map((_, k) => [k, "gone"])), msg: "replace → old main#app.replaceWith(new copy). Every old node leaves the document, including the focused input." },
    { i: null, final: true, replaced: true, m: Object.fromEntries(OLD.map((_, k) => [k, "gone"])), msg: "Correct HTML, but 13 new nodes. The input is new and empty; document.activeElement is <body>. Ada's “Ad” is gone. She will notice." },
  ];

  const v2 = frame("#viz-morph", "Morphing #app", "the old DOM is changed in place to match the new HTML");
  const cols = el("div", { class: "mo-cols" });
  const oldBox = el("div", { class: "mo-tree" });
  const newBox = el("div", { class: "mo-tree" });
  cols.append(el("div", { class: "mo-col" }, el("h4", { text: "Live DOM (old)" }), oldBox), el("div", { class: "mo-col" }, el("h4", { text: "New HTML from the stream" }), newBox));
  v2.stage.append(cols);
  function draw(f) {
    clear(oldBox); clear(newBox);
    if (f.replaced) {
      NEW.forEach(([d, t], k) => oldBox.append(el("div", { class: "new", text: "  ".repeat(d) + t + (k === 9 ? "   .value = \"\"" : "") })));
    } else {
      OLD.forEach(([d, t], k) => {
        const cls = [f.m[k] || "", k === 9 && !f.final ? "focus" : "", k === 9 && f.final ? "focus" : ""].join(" ");
        let label = "  ".repeat(d) + (f.final && k === 7 ? NEW[7][1] : f.final && k === 12 ? NEW[12][1] : t);
        if (k === 9) label += "   .value = \"Ad\"";
        oldBox.append(el("div", { class: cls, text: label }));
      });
    }
    NEW.forEach(([d, t], k) => newBox.append(el("div", { class: f.i === k ? "cur" : "", text: "  ".repeat(d) + t })));
    v2.foot.textContent = f.msg;
  }
  const s2 = stepper(v2.controls, MORPH, draw);
  v2.controls.append(seg(["outer (morph)", "replace"], 0, (i) => s2.setFrames(i ? REPLACE : MORPH)));

  // ---------- predict: reconnect ----------
  const pr = predict("#predict-reconnect", {
    prompt: "Ada's phone locks for 5 minutes. Meanwhile Sam advances her order to <code>ready</code>. Brew Board sends no event ids and keeps no replay buffer. What does Ada see when she unlocks? No peeking.",
    choices: ["Still “placed” until she reloads", "“ready”, within about a second", "“ready” only after 10 retries"],
    answer: 1,
    explain: "A hidden tab closes its <code>@get</code> stream (openWhenHidden is false for GET); becoming visible opens a new one. Our <code>on-open</code> sends the whole current view, so the first event is already the truth. No replay buffer, no event ids, no drama. The new stream's GET also carries her tab's signals as they are now, so the server re-adopts her draft before it renders.",
  });
})();
