// Part 5 figures: a route-table explorer, two predicts, and a fragment-per-action vs command+stream sequence.
(function () {
  "use strict";
  const { el, clear, frame, stepper, seg, table, predict } = EE;

  // The signals on Ada's customer page at the moment she acts (part 4's draft + name + local UI).
  const PAGE_SIGNALS = { drink: "latte", size: "medium", milk: "oat", qty: 1, customer: "Ada", _open: false, _loading: false };
  const sendable = Object.keys(PAGE_SIGNALS).filter((k) => !/(^|\.)_/.test(k));
  const sentJson = JSON.stringify(Object.fromEntries(sendable.map((k) => [k, PAGE_SIGNALS[k]])));

  const ROUTES = [
    { m: "GET", p: "/", h: "customer-page", guard: false },
    { m: "GET", p: "/board", h: "board-page", guard: false },
    { m: "GET", p: "/drink-options", h: "drink-options", guard: false },
    { m: "POST", p: "/cart/items", h: "add-item", guard: true },
    { m: "DELETE", p: "/cart/items/:idx", h: "remove-item", guard: true },
    { m: "POST", p: "/orders", h: "place-order", guard: true },
    { m: "POST", p: "/orders/:id/advance", h: "advance-order", guard: true },
    { m: "POST", p: "/stock/:milk/toggle", h: "toggle-stock", guard: true },
  ];

  function match(method, path) {
    for (const r of ROUTES) {
      const re = new RegExp("^" + r.p.replace(/:([a-z]+)/g, "(?<$1>[^/]+)") + "$");
      const m = path.match(re);
      if (m && r.m === method) return { route: r, params: m.groups || {} };
    }
    return null;
  }

  const enc = encodeURIComponent(sentJson);
  const REQS = [
    { label: "GET / (first visit)", method: "GET", path: "/", headers: ["Accept: text/html"], body: null,
      reads: { cookie: "none → wrap-sid makes one" }, used: [],
      kind: "page", cmd: null,
      resp: "200 OK\nContent-Type: text/html\nSet-Cookie: sid=3f1c…; Path=/; SameSite=Lax\n\n<!DOCTYPE html><html>…<script type=\"module\" src=\"…datastar@v1.0.4/bundles/datastar.js\"></script>…" },
    { label: "@get('/drink-options')", method: "GET", path: "/drink-options", query: "datastar=" + enc,
      headers: ["Datastar-Request: true", "Cookie: sid=3f1c…"], body: null,
      reads: { cookie: "not needed" }, used: ["drink", "size", "milk"], kind: "query", cmd: null,
      resp: "200 OK\nContent-Type: text/event-stream\n\nevent: datastar-patch-elements\ndata: elements <div id=\"drink-options\">…size and milk selects for latte…</div>\n" },
    { label: "@post('/cart/items')", method: "POST", path: "/cart/items", headers: ["Datastar-Request: true", "Content-Type: application/json", "Cookie: sid=3f1c…"],
      body: sentJson, reads: { cookie: "sid → Ada's cart" }, used: ["drink", "size", "milk", "qty"], kind: "command",
      cmd: '{:type :add-to-cart :sid "3f1c…"\n :line {:drink :latte :size :medium :milk :oat :qty 1}}',
      resp: "200 OK\nContent-Type: text/event-stream\n\nevent: datastar-patch-elements\ndata: elements <section id=\"cart\">… 1 × Medium Oat Latte  $5.60 …</section>\n" },
    { label: "@delete('/cart/items/0')", method: "DELETE", path: "/cart/items/0", query: "datastar=" + enc,
      headers: ["Datastar-Request: true", "Cookie: sid=3f1c…"], body: null,
      reads: { cookie: "sid → Ada's cart" }, used: [], kind: "command",
      cmd: '{:type :remove-from-cart :sid "3f1c…" :idx 0}',
      resp: "200 OK\nContent-Type: text/event-stream\n\nevent: datastar-patch-elements\ndata: elements <section id=\"cart\">… Empty. …</section>\n" },
    { label: "@post('/orders')", method: "POST", path: "/orders", headers: ["Datastar-Request: true", "Content-Type: application/json", "Cookie: sid=3f1c…"],
      body: sentJson, reads: { cookie: "sid → Ada's cart" }, used: ["customer"], kind: "command",
      cmd: '{:type :place-order :sid "3f1c…" :customer "Ada"}',
      resp: "200 OK\nContent-Type: text/event-stream\n\nevent: datastar-patch-elements\ndata: elements <section id=\"cart\">… Empty. …</section>\n\nevent: datastar-patch-elements\ndata: elements <section id=\"my-orders\">… #1 for Ada: placed …</section>\n\nevent: datastar-patch-signals\ndata: signals {\"customer\":\"\"}\n" },
    { label: "@post('/orders/1/advance') (Sam)", method: "POST", path: "/orders/1/advance", headers: ["Datastar-Request: true", "Content-Type: application/json"],
      body: "{}", reads: { cookie: "not needed" }, used: [], kind: "command",
      cmd: "{:type :advance :id 1}",
      resp: "200 OK\nContent-Type: text/event-stream\n\nevent: datastar-patch-elements\ndata: elements <section id=\"board-orders\">… #1 Ada (making) …</section>\n" },
    { label: "@post('/stock/oat/toggle') (Sam)", method: "POST", path: "/stock/oat/toggle", headers: ["Datastar-Request: true", "Content-Type: application/json"],
      body: "{}", reads: { cookie: "not needed" }, used: [], kind: "command",
      cmd: "{:type :toggle-stock :milk :oat}",
      resp: "200 OK\nContent-Type: text/event-stream\n\nevent: datastar-patch-elements\ndata: elements <section id=\"stock\">… oat: sold out …</section>\n" },
    { label: "POST /orders from evil.example (form)", method: "POST", path: "/orders", headers: ["Origin: https://evil.example", "Content-Type: application/x-www-form-urlencoded", "Cookie: sid=3f1c… (only if not SameSite)"],
      body: "customer=Mallory", reads: {}, used: [], kind: "forged", cmd: null,
      resp: "403 Forbidden\nContent-Type: text/plain\n\nDatastar requests only" },
  ];

  const v = frame("#viz-routes", "Route explorer", "pick a request: what arrives, what matches, what is read, what goes back");
  const grid = el("div", { class: "rt-grid" });
  const list = el("div", { class: "rt-reqs" });
  const detail = el("div");
  grid.append(list, detail);
  v.stage.append(grid);
  const buttons = REQS.map((r, i) => el("button", { text: r.label, onclick: () => show(i) }));
  list.append(...buttons);

  function show(i) {
    const r = REQS[i];
    buttons.forEach((b, j) => b.classList.toggle("on", i === j));
    clear(detail);
    const line = `${r.method} ${r.path}${r.query ? "?" + (r.query.length > 70 ? r.query.slice(0, 70) + "…" : r.query) : ""} HTTP/1.1`;
    detail.append(el("div", { class: "rt-label", text: "Request" }),
      el("pre", { class: "rt-wire", text: [line, ...r.headers].join("\n") + (r.body ? "\n\n" + r.body : "") }));
    if (r.query) detail.append(el("p", { class: "viz-sub", text: "datastar= decodes to " + sentJson }));

    const m = match(r.method, r.path);
    detail.append(el("div", { class: "rt-label", text: "Route and middleware" }));
    const chain = el("div", { class: "rt-chain" });
    chain.append(el("span", { class: "pass", text: "wrap-params" }), el("span", { class: "arrow", text: "→" }),
      el("span", { class: "pass", text: "wrap-cookies" }), el("span", { class: "arrow", text: "→" }),
      el("span", { class: "pass", text: "wrap-sid" }), el("span", { class: "arrow", text: "→" }));
    if (m) {
      chain.append(el("span", { class: "pass", text: `${m.route.m} ${m.route.p}` }));
      if (m.route.guard) {
        const ok = r.headers.some((h) => h.startsWith("Datastar-Request: true"));
        chain.append(el("span", { class: "arrow", text: "→" }), el("span", { class: ok ? "pass" : "fail", text: "wrap-datastar-request " + (ok ? "✓" : "✗ 403") }));
        if (ok) chain.append(el("span", { class: "arrow", text: "→" }), el("span", { class: "pass", text: m.route.h }));
      } else {
        chain.append(el("span", { class: "arrow", text: "→" }), el("span", { class: "pass", text: m.route.h }), el("span", { class: "arrow", text: "(no guard: " + (r.kind === "page" ? "page" : "query") + ")" }));
      }
    }
    detail.append(chain);
    if (m && Object.keys(m.params).length) detail.append(el("p", { class: "viz-sub", text: "path params: " + JSON.stringify(m.params) }));

    if (r.kind === "query" || r.kind === "command") {
      detail.append(el("div", { class: "rt-label", text: "Signals that arrived (teal = the handler reads it)" }));
      const sig = el("div", { class: "rt-sig" });
      sendable.forEach((k) => sig.append(el("span", { class: r.used.includes(k) ? "used" : "", text: k })));
      detail.append(sig);
      if (r.reads.cookie) detail.append(el("p", { class: "viz-sub", text: "cookie: " + r.reads.cookie }));
    }
    if (r.cmd) detail.append(el("div", { class: "rt-label", text: "Command for (swap! state handle-command cmd)" }), el("pre", { class: "rt-wire", text: r.cmd }));
    detail.append(el("div", { class: "rt-label", text: "Response" }), el("pre", { class: "rt-wire", text: r.resp }));

    v.foot.textContent = {
      page: "A page GET: no Datastar header, no signals, nothing fancy. It returns the whole document and sets the sid cookie, like it's 2005.",
      query: "A query: GET, unguarded, changes nothing. Signals ride along in ?datastar=, and the handler reads three of the five that arrived. The other two just came for the scenery.",
      command: "A command: guarded, turned into a command map, applied with swap!, and answered with the patches it caused (this part's style; hold that thought for part 6).",
      forged: "A cross-site form can POST all it likes, but it cannot add Datastar-Request. And a cross-site fetch that adds it triggers a CORS preflight the server never approves. Nice try.",
    }[r.kind];
  }
  show(2);

  // ---------- predict: which signals arrive? ----------
  const p1 = predict("#predict-signals", {
    prompt: "Ada's page has signals <code>drink size milk qty customer _open _loading</code>. Her Order button runs <code>@post('/orders')</code>. Which ones actually make it into the request body?",
    choices: ["Only <code>customer</code>", "<code>drink size milk qty customer</code>", "All seven"],
    answer: 1,
    explain: "Datastar sends every signal except those with a <code>_</code>-prefixed path segment (default <code>exclude = /(^|\\.)_/</code> in fetch.ts). It has no idea which ones the server needs, and it doesn't try to guess; the handler picks.",
  });

  // ---------- sequence: fragment-per-action vs command + stream ----------
  function framesFor(mode) {
    const F = [];
    const ada0 = ["cart: 1 × Latte, medium, oat", "my orders: (none)"];
    const sam0 = ["board: (no orders)"];
    const push = (active, ada, srv, sam, msg) => F.push({ active, ada, srv, sam, msg });
    if (mode === 0) {
      push(null, ada0, ["orders: {}", "next-id 1"], sam0, "Both pages are open. Ada has a latte in her cart (the medium oat, naturally). Sam is watching /board.");
      push("ada", ada0, ["orders: {}"], sam0, "Ada clicks Order → @post('/orders') with Datastar-Request, the sid cookie and all non-_ signals.");
      push("srv", ada0, ["swap! handle-command :place-order", "<new>orders: {1 Ada placed}</new>"], sam0, "The server applies the command. State now holds order #1.");
      push("srv", ada0, ["command-response → 3 events", "#cart, #my-orders, signals"], sam0, "command-response picks the patches this command affects on the customer page.");
      push("ada", ["<new>cart: empty</new>", "<new>my orders: #1 placed</new>"], ["orders: {1 Ada placed}"], sam0, "200 text/event-stream. Ada's page morphs #cart and #my-orders. Correct, for Ada, anyway.");
      push("sam", ["cart: empty", "my orders: #1 placed"], ["orders: {1 Ada placed}"], ["<stale>board: (no orders)</stale>"], "Sam's board never heard about it. No request came from Sam's browser, so no response went there. It stays stale until Sam reloads, and Sam has no idea.");
    } else {
      push(null, ada0, ["orders: {}", "subscribers: Ada, Sam"], sam0, "Both pages opened a long-lived GET on load (data-init=\"@get('/customer/stream')\" and '/board/stream'). The server holds both connections.");
      push("ada", ada0, ["orders: {}"], sam0, "Ada clicks Order → @post('/orders'). Same request as before.");
      push("srv", ada0, ["swap! handle-command :place-order", "<new>orders: {1 Ada placed}</new>"], sam0, "Same pure command. But now the handler answers 204 No Content: it renders nothing at all. Wait for it.");
      push("srv", ada0, ["add-watch fires", "render view-for Ada, view-for Sam"], sam0, "The atom's watch renders each subscriber's whole view from the new state.");
      push("ada", ["<new>cart: empty</new>", "<new>my orders: #1 placed</new>"], ["orders: {1 Ada placed}"], sam0, "Ada's stream gets her whole #app; the morph changes only what differs.");
      push("sam", ["cart: empty", "my orders: #1 placed"], ["orders: {1 Ada placed}"], ["<new>board: #1 Ada placed</new>"], "Sam's stream gets the board's whole #app, and Sam sees the order without asking. And here's the beautiful part: no command knows any view exists.");
    }
    return F;
  }

  const v2 = frame("#viz-seq", "Who hears about a command?", "Ada places an order while Sam watches the board");
  const lanes = el("div", { class: "sq-lanes" });
  const mk = (t) => { const d = el("div", { class: "sq-lane" }, el("h4", { text: t })); const s = el("div", { class: "sq-screen" }); d.append(s); lanes.append(d); return [d, s]; };
  const [adaL, adaS] = mk("Ada's browser (/)");
  const [srvL, srvS] = mk("Server");
  const [samL, samS] = mk("Sam's browser (/board)");
  const msg = el("div", { class: "sq-msg" });
  v2.stage.append(lanes, msg);
  const fill = (node, lines) => {
    clear(node);
    lines.forEach((l) => {
      const m = l.match(/^<(new|stale)>(.*)<\/\1>$/);
      node.append(el("div", { class: m ? m[1] : "", text: m ? m[2] : l }));
    });
  };
  const s = stepper(v2.controls, framesFor(0), (f) => {
    adaL.classList.toggle("active", f.active === "ada");
    srvL.classList.toggle("active", f.active === "srv");
    samL.classList.toggle("active", f.active === "sam");
    fill(adaS, f.ada); fill(srvS, f.srv); fill(samS, f.sam);
    msg.textContent = f.msg;
  });
  v2.controls.append(seg(["Fragment per action (part 5)", "Command + stream (part 6)"], 0, (i) => s.setFrames(framesFor(i))));
  v2.foot.textContent = "Teal: changed on this step. Red: wrong, and nobody is coming to fix it.";

  const p2 = predict("#predict-board", {
    prompt: "Fragment-per-action style. Ada places order #1 while Sam has <code>/board</code> open in another browser. What does Sam's board show?",
    choices: ["Order #1, within a second", "Nothing new until Sam does something that reloads the board", "An error"],
    answer: 1,
    onLock() { s.setFrames(framesFor(0)); let k = 0; const t = setInterval(() => { s.go(++k); if (k >= 5) { clearInterval(t); p2.reveal(); } }, 450); },
    explain: "A response goes only to the browser that sent the request. Sam's browser sent nothing, so it got nothing. That's not a bug in your code; it's how HTTP works. Step through the figure below, then flip it to the part 6 mode and watch the problem evaporate.",
  });
})();
