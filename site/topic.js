// Shared figure data: the Brew Board menu and pricing from DESIGN.md, plus small helpers that
// mimic the course's Clojure SSE writer so figures print the exact wire text.
(function () {
  "use strict";

  const SIZES = ["single", "double", "small", "medium", "large"];
  const MILKS = ["whole", "oat", "almond"];
  const SIZE_DELTA = { single: 0, double: 100, small: 0, medium: 50, large: 100 };
  const MILK_DELTA = { whole: 0, oat: 60, almond: 60 };
  const MENU = [
    { id: "espresso", name: "Espresso", base: 300, sizes: ["single", "double"], milks: [] },
    { id: "americano", name: "Americano", base: 350, sizes: ["small", "large"], milks: [] },
    { id: "latte", name: "Latte", base: 450, sizes: ["small", "medium", "large"], milks: ["whole", "oat", "almond"] },
    { id: "cappuccino", name: "Cappuccino", base: 425, sizes: ["small", "medium"], milks: ["whole", "oat"] },
    { id: "mocha", name: "Mocha", base: 500, sizes: ["small", "medium", "large"], milks: ["whole", "oat", "almond"] },
    { id: "chai", name: "Chai Latte", base: 450, sizes: ["medium", "large"], milks: ["whole", "oat", "almond"] },
  ];
  const byId = Object.fromEntries(MENU.map((d) => [d.id, d]));
  const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);

  // Same arithmetic as price-cents: base + size delta + milk delta (no milk adds 0).
  function priceCents({ drink, size, milk }) {
    const d = byId[drink];
    if (!d) return null;
    return d.base + (SIZE_DELTA[size] ?? 0) + (milk ? MILK_DELTA[milk] ?? 0 : 0);
  }
  const money = (cents) => "$" + (cents / 100).toFixed(2);

  // First choice that is allowed for this drink (and, for milks, not sold out).
  function firstValid(list, soldOut = []) {
    return list.find((x) => !soldOut.includes(x)) ?? "";
  }

  // The Clojure send-event / patch-elements / patch-signals, as JS, for figure text.
  function sendEvent(type, lines, { id, retry } = {}) {
    let s = `event: ${type}\n`;
    if (id) s += `id: ${id}\n`;
    if (retry && retry !== 1000) s += `retry: ${retry}\n`;
    for (const l of lines) s += `data: ${l}\n`;
    return s + "\n";
  }
  function patchElements(html, o = {}) {
    const lines = [];
    if (o.selector) lines.push(`selector ${o.selector}`);
    if (o.mode && o.mode !== "outer") lines.push(`mode ${o.mode}`);
    if (o.useViewTransition) lines.push("useViewTransition true");
    if (o.namespace && o.namespace !== "html") lines.push(`namespace ${o.namespace}`);
    if (html) for (const l of html.split("\n")) lines.push(`elements ${l}`);
    return sendEvent("datastar-patch-elements", lines, o);
  }
  function patchSignals(obj, o = {}) {
    const lines = [];
    if (o.onlyIfMissing) lines.push("onlyIfMissing true");
    const json = typeof obj === "string" ? obj : JSON.stringify(obj);
    for (const l of json.split("\n")) lines.push(`signals ${l}`);
    return sendEvent("datastar-patch-signals", lines, o);
  }

  EE.BREW = { MENU, byId, SIZES, MILKS, SIZE_DELTA, MILK_DELTA, priceCents, money, firstValid, cap,
    sendEvent, patchElements, patchSignals,
    customers: ["Ada", "Grace", "Linus"], barista: "Sam" };
})();
