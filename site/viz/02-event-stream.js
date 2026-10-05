// Part 2 figures: an event builder that applies patch modes to a toy page, and the
// browser's chunk → line → message → patch pipeline.
(function () {
  "use strict";
  const { el, clear, frame, stepper, seg, predict, BREW } = EE;

  const PAGE = '<ul id="orders"><li id="order-1">Ada · Latte</li><li id="order-2">Grace · Mocha</li></ul><div id="cart">Cart: empty</div>';
  const MODES = ["outer", "inner", "replace", "prepend", "append", "before", "after", "remove"];
  const PRESETS = [
    { label: "Replace #cart", mode: "outer", selector: "", html: '<div id="cart">Cart: 1 × Latte</div>' },
    { label: "Append an order", mode: "append", selector: "#orders", html: '<li id="order-3">Linus · Chai Latte</li>' },
    { label: "Remove an order", mode: "remove", selector: "#order-1", html: "" },
    { label: "Remove all li", mode: "remove", selector: "#orders li", html: "" },
    { label: "Inner, no selector", mode: "inner", selector: "", html: '<div id="cart">Cart: 2 × Mocha</div>' },
  ];

  // Apply one patch-elements to a sandbox, following patchElements.ts's rules (morph shown as a plain swap).
  function apply(sandbox, { mode, selector, html }) {
    const tpl = document.createElement("template");
    tpl.innerHTML = html;
    const mark = (frag) => { for (const n of frag.querySelectorAll ? frag.querySelectorAll("*") : []) n.dataset.new = "1"; for (const n of frag.children || []) n.dataset.new = "1"; return frag; };
    if (!selector && mode !== "outer" && mode !== "replace")
      return { err: "throws PatchElementsExpectedSelector: mode " + mode + " needs a selector. Nothing is patched." };
    if (!selector) {
      const warns = [];
      for (const child of [...tpl.content.children]) {
        const target = child.id && sandbox.querySelector("#" + CSS.escape(child.id));
        if (!target) { warns.push(`console.warn PatchElementsNoTargetsFound { id: "${child.id}" }`); continue; }
        target.replaceWith(mark(child.cloneNode(true)));
      }
      return { warn: warns.join("\n") || null, note: mode === "outer" ? "outer: matched by id and morphed (only differences change)" : "replace: matched by id and swapped; nothing of the old element survives" };
    }
    let targets;
    try { targets = [...sandbox.querySelectorAll(selector)]; } catch { return { err: "invalid selector" }; }
    if (!targets.length) return { warn: `console.warn PatchElementsNoTargetsFound { selector: "${selector}" }` };
    for (const t of targets) {
      const frag = mark(tpl.content.cloneNode(true));
      switch (mode) {
        case "remove": t.remove(); break;
        case "outer": case "replace": t.replaceWith(frag); break;
        case "inner": t.replaceChildren(frag); break;
        case "prepend": t.prepend(frag); break;
        case "append": t.append(frag); break;
        case "before": t.before(frag); break;
        case "after": t.after(frag); break;
      }
    }
    return { note: `${targets.length} target${targets.length > 1 ? "s" : ""} matched ${selector}` };
  }

  function domLines(node, depth = 0, out = []) {
    for (const c of node.children) {
      const attrs = c.id ? ` id="${c.id}"` : "";
      const isNew = c.dataset.new === "1";
      const kids = c.children.length;
      if (!kids) out.push([depth, `<${c.localName}${attrs}>${c.textContent}</${c.localName}>`, isNew]);
      else { out.push([depth, `<${c.localName}${attrs}>`, isNew]); domLines(c, depth + 1, out); out.push([depth, `</${c.localName}>`, isNew]); }
    }
    return out;
  }

  // ---------- figure A: the builder ----------
  const pm = el("div");
  document.querySelector("#predict-inner").replaceWith(pm);
  const A = frame("#viz-builder", "Build a patch-elements event", "edit the options; the page on the right is a toy");
  const modeSel = el("select", null, MODES.map((m) => el("option", { value: m, text: m })));
  const selIn = el("input", { type: "text", placeholder: "(none: match by id)" });
  const vt = el("input", { type: "checkbox" });
  const idIn = el("input", { type: "text", placeholder: "(none)" });
  const retryIn = el("input", { type: "text", placeholder: "1000" });
  const htmlIn = el("textarea");
  const wire = el("pre", { class: "eb-wire" });
  const dom = el("div", { class: "eb-dom" });
  const resultLine = el("div");
  A.stage.append(el("div", { class: "eb-grid" },
    el("div", { class: "eb-form" },
      "mode", modeSel, "selector", selIn, "view transition", el("span", null, vt),
      "id", idIn, "retry (ms)", retryIn, "elements", htmlIn),
    el("div", null, el("p", { class: "eb-label", text: "wire text" }), wire,
      el("p", { class: "eb-label", text: "toy page after the patch" }), dom)));
  function render() {
    const o = { mode: modeSel.value, selector: selIn.value.trim(), useViewTransition: vt.checked,
      id: idIn.value.trim() || undefined, retry: Number(retryIn.value) || undefined };
    wire.textContent = BREW.patchElements(o.mode === "remove" ? "" : htmlIn.value, o);
    const sandbox = el("div", { html: PAGE });
    const r = apply(sandbox, { mode: o.mode, selector: o.selector, html: o.mode === "remove" ? "" : htmlIn.value });
    clear(dom);
    if (r.err) dom.append(el("div", { class: "err", text: "✗ " + r.err }));
    else {
      for (const [d, t, n] of domLines(sandbox)) dom.append(el("div", { class: n ? "new" : "kept", text: "  ".repeat(d) + t }));
      if (r.warn) dom.append(el("div", { class: "err", text: "⚠ " + r.warn }));
    }
    A.foot.textContent = r.err ? "No selector, and the mode is not outer or replace: there is nothing to find the target with." : (r.note || "") + (r.warn ? "" : ". New elements are highlighted.");
  }
  function load(p) { modeSel.value = p.mode; selIn.value = p.selector; htmlIn.value = p.html; vt.checked = false; idIn.value = ""; retryIn.value = ""; render(); }
  [modeSel, selIn, vt, idIn, retryIn, htmlIn].forEach((x) => x.addEventListener("input", render));
  const presets = seg(PRESETS.map((p) => p.label), 0, (i) => load(PRESETS[i]));
  A.controls.append(presets);
  load(PRESETS[0]);

  const pr = predict(pm, {
    prompt: "An event has <code>data: mode inner</code> and <code>data: elements &lt;div id=\"cart\"&gt;…&lt;/div&gt;</code>, and no selector. The page has a <code>#cart</code>. What happens?",
    choices: ["<code>#cart</code>'s children are replaced", "<code>#cart</code> is replaced, as with outer", "Datastar throws <code>PatchElementsExpectedSelector</code>; nothing changes", "The div is appended to <code>&lt;body&gt;</code>"],
    answer: 2,
    explain: "Only <code>outer</code> and <code>replace</code> look targets up by the payload's ids. Every other mode needs a selector, and <code>patchElements.ts</code> throws before touching the DOM. The figure below is now showing that case.",
    onLock() { presets.querySelectorAll("button")[4].click(); pr.reveal(); },
  });

  // ---------- figure B: chunks → lines → messages → patches ----------
  const STREAM = 'event: datastar-patch-elements\ndata: elements <div id="cart">\ndata: elements   1 × Latte\ndata: elements </div>\n\nevent: datastar-patch-signals\ndata: signals {"quote":560}\n\n';
  const CUTS = [9, 38, 47, 75, 121, 150, 160, STREAM.length];
  function parseFrames() {
    const fr = [];
    let buf = "", msg = { event: "", data: "" }, page = { cart: "Cart: empty", quote: "—" };
    const dispatched = [];
    let prev = 0;
    fr.push({ ci: -1, buf: "", msg: { ...msg }, disp: [], page: { ...page }, note: "Nothing has arrived. The response headers said text/event-stream, so Datastar starts reading the body." });
    CUTS.forEach((cut, ci) => {
      buf += STREAM.slice(prev, cut); prev = cut;
      const lines = [];
      let nl;
      while ((nl = buf.indexOf("\n")) >= 0) { lines.push(buf.slice(0, nl)); buf = buf.slice(nl + 1); }
      const notes = [];
      for (const line of lines) {
        if (line === "") {
          const args = {};
          for (const l of msg.data.split("\n")) { const i = l.indexOf(" "); (args[l.slice(0, i)] ||= []).push(l.slice(i + 1)); }
          const joined = Object.fromEntries(Object.entries(args).map(([k, v]) => [k, v.join("\n")]));
          if (msg.event === "datastar-patch-elements") page.cart = joined.elements.replace(/<[^>]+>/g, "").trim().replace(/\s+/g, " ");
          if (msg.event === "datastar-patch-signals") page.quote = JSON.parse(joined.signals).quote;
          dispatched.push(msg.event + "\n" + JSON.stringify(joined, null, 1));
          notes.push(`blank line → dispatch ${msg.event}`);
          msg = { event: "", data: "" };
        } else {
          const c = line.indexOf(":");
          const field = line.slice(0, c), value = line.slice(c + (line[c + 1] === " " ? 2 : 1));
          if (field === "event") msg.event = value;
          if (field === "data") msg.data = msg.data ? msg.data + "\n" + value : value;
        }
      }
      if (!notes.length) notes.push(lines.length ? `${lines.length} complete line${lines.length > 1 ? "s" : ""} parsed into the current message; no blank line yet` : "no complete line yet: the bytes wait in the buffer");
      fr.push({ ci, buf, msg: { ...msg }, disp: dispatched.slice(), page: { ...page }, note: `Chunk ${ci + 1} (${cut - (CUTS[ci - 1] || 0)} bytes): ` + notes.join("; ") + "." });
    });
    return fr;
  }
  const B = frame("#viz-parse", "Reading a stream that arrives in pieces", "chunk boundaries fall anywhere");
  const chunksRow = el("div", { class: "ps-chunks" });
  const chunkEls = CUTS.map((c, i) => el("span", { class: "ps-chunk", text: JSON.stringify(STREAM.slice(CUTS[i - 1] || 0, c)).slice(1, -1) }));
  chunksRow.append(...chunkEls);
  const bufPre = el("pre"), msgPre = el("pre"), dispPre = el("pre");
  const pageView = el("div", { class: "eb-dom" });
  B.stage.append(chunksRow, el("div", { class: "ps-cols" },
    el("div", { class: "ps-col" }, el("p", { class: "eb-label", text: "unfinished line in buffer" }), bufPre),
    el("div", { class: "ps-col" }, el("p", { class: "eb-label", text: "current message" }), msgPre),
    el("div", { class: "ps-col" }, el("p", { class: "eb-label", text: "dispatched (args by key)" }), dispPre)),
    el("p", { class: "eb-label", text: "page" }), pageView);
  stepper(B.controls, parseFrames(), (f) => {
    chunkEls.forEach((c, i) => { c.classList.toggle("cur", i === f.ci); c.classList.toggle("done", i < f.ci); });
    bufPre.textContent = JSON.stringify(f.buf);
    msgPre.textContent = `event: ${JSON.stringify(f.msg.event)}\ndata:  ${JSON.stringify(f.msg.data)}`;
    dispPre.textContent = f.disp.join("\n\n") || "—";
    clear(pageView).append(el("div", { text: `#cart: ${f.page.cart}` }), el("div", { text: `$quote: ${f.page.quote}` }));
    B.foot.textContent = f.note;
  });
})();
