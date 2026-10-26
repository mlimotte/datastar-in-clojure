// Part 7 figures: a shell session stepper (native stack + wire/bridge log), a release predict, a deploy-vs-release sorter.
(function () {
  "use strict";
  const { el, clear, frame, stepper, predict } = EE;

  const CONFIG = [
    { re: /^\/$/, presentation: "root" },
    { re: /^\/orders\/\d+$/, presentation: "push" },
  ];
  const present = (path) => (CONFIG.find((r) => r.re.test(path)) || { presentation: "push" }).presentation;

  // Each frame: stack (array of {title, url, modal}), toast, dir (in/out/http), wire text, note.
  function frames() {
    const F = [];
    let stack = [];
    const snap = (dir, wire, note, toast) => F.push({ stack: stack.map((s) => ({ ...s })), dir, wire, note, toast });
    snap(null, "", "The app launches. The shell has no Brew Board screens of its own. Not one. It's an empty stage.");
    snap("http", "GET /native/path-config\n← {\"rules\": [{\"patterns\": [\"^/$\"], \"presentation\": \"root\"},\n    {\"patterns\": [\"^/orders/\\\\d+$\"], \"presentation\": \"push\"}]}", "First it downloads the navigation rules. A server deploy can change them, and App Review never hears about it.");
    stack = [{ title: "Brew Board", url: "/" }];
    snap("http", "GET /   User-Agent: … BrewShell/1\n← 200 text/html (no web header: shell-request? is true)\nGET /customer/stream   (data-init)\n← 200 text/event-stream, held open: whole #app", "Path / matches ^/$ → root. The page opens its live stream exactly as in a browser. It has no idea it's in an app.");
    snap("in", "shell → page:\nwindow.dispatchEvent(new CustomEvent('brew:native',\n  {detail: {type: 'push-token', token: 'a1b2…'}}))\npage: evt.detail.type === 'push-token' && ($pushToken = …,\n  @post('/native/device', {filterSignals: {include: /^pushToken$/}}))\n← 204   state: :devices {ada \"a1b2…\"}", "Native → web: the token becomes a signal and goes to the server like any other input. Nothing special about it at all.");
    snap("http", "@post('/cart/items')  → 204\n@post('/orders')      → 204\nstream ← whole #app: order #1 (placed)", "Ada orders. Commands answer 204; the stream sends her view, same as part 6.");
    const p1 = "/orders/1";
    stack.push({ title: "Order #1", url: p1 });
    snap("http", `tap <a href="${p1}">\nshell: decidePolicyFor → cancel in-page load\n"${p1}" matches ^/orders/\\d+$ → ${present(p1)}\nGET ${p1} in a new web view (a real route: one of Ada's orders)`, "Navigation is intercepted and presented natively: a pushed screen with a real native back button, the kind thumbs expect.");
    stack.pop();
    snap(null, "swipe from the left edge → back (native gesture)", "Going back is native, and the root web view is still sitting right there, stream and all.");
    snap("http", "Sam: @post('/orders/1/advance') ×2  → status :ready\nwatch: broadcast! → Ada's stream ← whole #app (ready)\nready-notifications(old, new)\n  → [{:sid ada :message \"Ada, your order #1 is ready.\"}]", "The pure diff of old and new state finds the orders that just became ready.");
    snap("out", "stream ← datastar-patch-elements, mode append, selector body:\n<script data-effect=\"el.remove()\">\n  window.BrewShell.post({\"type\":\"notify\",\"title\":\"Brew Board\",\n    \"body\":\"Ada, your order #1 is ready.\",\"haptic\":\"success\"})\n</script>\nshell: messageHandlers.brew receives it", "Web → native: notify! sends the bridge script down Ada's open stream. The shell fires a haptic and shows a banner. Bzzt.", "Brew Board: Ada, your order #1 is ready.");
    snap(null, "phone locked → page hidden → @get stream closed\nserver: on-close → unsubscribe!", "Ada pockets her phone. Her stream closes, as part 6 warned it would.");
    snap("push", "Sam: order #2 → :ready\nnotify! → no open stream for ada → returned\nwatch!: (push! \"a1b2…\" \"Ada, your order #2 is ready.\")\n  (toy: printed; a real server calls APNs/FCM)", "With no open stream, the notification falls back to a push to the token registered earlier. Remember that token? This is why.", "Brew Board: Ada, your order #2 is ready.");
    stack = [{ title: "Brew Board", url: "/" }];
    snap("http", "unlock → page visible → @get('/customer/stream') reopens\n← whole #app: order #2 (ready)", "The stream reopens and the first event is the current view. And every navigation rule used in this whole session came from the server.");
    return F;
  }

  const v = frame("#viz-shell", "A session in the shell", "native stack on the left; what crossed the wire or the bridge on the right");
  const wrap = el("div", { class: "sh-wrap" });
  const phone = el("div", { class: "sh-phone" });
  const bar = el("div", { class: "sh-bar" }, el("span", { text: "9:41" }), el("span", { text: "BrewShell/1" }));
  const stackEl = el("div", { class: "sh-stack" });
  phone.append(bar, stackEl);
  const side = el("div", { class: "sh-side" });
  const dirEl = el("div");
  const wire = el("pre", { class: "sh-wire" });
  side.append(el("div", { class: "sh-k", text: "This step" }), dirEl, wire);
  wrap.append(phone, side);
  v.stage.append(wrap);
  stepper(v.controls, frames(), (f) => {
    clear(stackEl);
    f.stack.forEach((s, i) => stackEl.append(el("div", { class: "sh-screen" + (i === f.stack.length - 1 ? " top" : "") + (s.modal ? " modal" : "") },
      el("div", { class: "t", text: (i > 0 && !s.modal ? "‹ " : "") + s.title + (s.modal ? "  (sheet)" : "") }), el("div", { class: "u", text: "web view: " + s.url }))));
    if (!f.stack.length) stackEl.append(el("div", { class: "sh-screen" }, el("div", { class: "u", text: "(launch screen)" })));
    phone.querySelectorAll(".sh-toast").forEach((t) => t.remove());
    if (f.toast) phone.append(el("div", { class: "sh-toast", text: "🔔 " + f.toast }));
    clear(dirEl);
    if (f.dir) dirEl.append(el("span", { class: "sh-dir " + (f.dir === "push" ? "http" : f.dir), text: { http: "HTTP", in: "native → web", out: "web → native", push: "server → APNs/FCM" }[f.dir] }));
    wire.textContent = f.wire || "—";
    v.foot.textContent = f.note;
  });

  // ---------- deploy vs release ----------
  const CHANGES = [
    { q: "A new “Order history” screen at <code>/orders/history</code>", rel: false, why: "A new route and some HTML. Unmatched paths are pushed, so the shell presents it without a single change." },
    { q: "Push order pages from a new <code>/orders/:id/receipt</code> link too", rel: false, why: "A new route plus one pattern in the served path-config." },
    { q: "Reword the order-status copy and restyle the cart", rel: false, why: "HTML and CSS from the server." },
    { q: "Haptic + banner when an order is ready (the shell already handles <code>\"notify\"</code>)", rel: false, why: "A bridge message the installed shell already understands, so the server just starts sending it." },
    { q: "Hide sold-out milks entirely instead of disabling them", rel: false, why: "<code>drink-options-view</code> on the server." },
    { q: "Scan a loyalty QR code with the camera", rel: true, why: "New native code (camera access, a new <code>\"scan-qr\"</code> message, a usage-description string). Gate it on the shell version in the User-Agent. (Told you that version number would matter.)" },
    { q: "Push notifications while the app is in the background", rel: true, why: "Registering for APNs/FCM and handling the token is native code and app configuration. Once that ships, though, the server decides what to send." },
    { q: "Replace the order screen with a fully native SwiftUI view", rel: true, why: "Native UI is native code. No way around it. (Hotwire Native's path config can route a path to a native screen, but the screen itself ships in the binary.)" },
    { q: "A new app icon", rel: true, why: "The icon is in the app bundle." },
  ];
  const releases = CHANGES.filter((c) => c.rel).length;

  const p = predict("#predict-release", {
    prompt: `Here are ${CHANGES.length} changes to Brew Board's phone app (listed in the figure below). How many of them need a new app-store release? Guess before you sort.`,
    number: { min: 0, max: CHANGES.length, step: 1, value: CHANGES.length },
    answer: releases, tolerance: 0,
    explain: "Only the ones that need new native code. Everything that is HTML, routes, path rules, or a bridge message the shell already knows ships with a server deploy. No App Review, no waiting. Sort them below.",
  });

  const v2 = frame("#viz-sorter", "Deploy or release?", "click your answer for each change");
  const score = el("span", { class: "viz-sub" });
  v2.controls.append(score);
  let answered = 0, right = 0;
  CHANGES.forEach((c) => {
    const why = el("div", { class: "so-why", hidden: true });
    const mk = (label, rel) => el("button", { text: label, onclick: (e) => {
      if (row.dataset.done) return;
      row.dataset.done = "1";
      const ok = rel === c.rel;
      answered++; if (ok) right++;
      e.target.classList.add("on", ok ? "good" : "bad");
      if (!ok) btns.querySelectorAll("button").forEach((b) => { if (b !== e.target) b.classList.add("on", "good"); });
      why.innerHTML = (c.rel ? "<b>Store release.</b> " : "<b>Server deploy.</b> ") + c.why;
      why.hidden = false;
      score.textContent = `${right} of ${answered} right`;
      v2.foot.textContent = answered === CHANGES.length ? `${releases} of ${CHANGES.length} need a release; all of them are new native code or bundle contents.` : "";
    } });
    const btns = el("div", { class: "so-btns" }, mk("Server deploy", false), mk("Store release", true));
    const row = el("div", { class: "so-row" }, el("div", { class: "so-q", html: c.q }), btns, why);
    v2.stage.append(row);
  });
  score.textContent = "0 answered";
})();
