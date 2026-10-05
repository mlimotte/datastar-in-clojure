# DESIGN: Datastar in Clojure

The contract every agent builds against. If something here is wrong, say so in your report;
don't silently diverge.

## What this course is

How to *use* Datastar from Clojure, built up over seven parts toward one app, **Brew Board**.

- **Clojure only.** No Go track, no browser terminal. (`lk` skips Go when `go/go.mod` is absent.)
- **No Datastar SDK.** We never depend on `dev.data-star.clojure/*`. Part 2 writes the ~40-line SSE
  writer from the wire spec; every later lesson copies it.
- **Internals are prose, not exercises.** How the client parses SSE, how signals track
  dependencies, how the morph matches elements: explained in the posts where it matters for a usage
  decision. No toy signal graph or toy morph in code.
- **Toy, not production.** In-memory atom state, no auth, no persistence, no error recovery
  beyond what teaches something.

## Versions (checked 2026-10-05)

| Thing | Version | Notes |
|---|---|---|
| Datastar JS | v1.0.4 | `<script type="module" src="https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.4/bundles/datastar.js"></script>` (from the repo README) |
| http-kit | 2.9.0-beta4 | beta on purpose: 2.8.1 never fires `:on-close` when an SSE client disconnects (verified by the lessons agent), so hubs would leak dead viewers |
| reitit-ring | 0.11.0 | |
| ring-core | 1.15.5 | params + cookies middleware |
| hiccup | 2.0.0 | `hiccup2.core/html`, `hiccup.util/raw-string` |
| cheshire | 6.2.0 | all JSON |

Source of truth for Datastar behavior: the pinned clone at `/tmp/lk-sources/datastar` (tag v1.0.4,
commit 1efcdc3). Read files with `git -C /tmp/lk-sources/datastar show HEAD:<path>`. Key files:
`sdk/ADR.md` (wire spec), `sdk/test/get-cases/*` (golden SSE output),
`library/src/plugins/actions/fetch.ts` (requests, signal filtering, non-SSE responses, retry),
`library/src/plugins/watchers/patchElements.ts` (patch modes, morph),
`library/src/plugins/watchers/patchSignals.ts`, `library/src/engine/signals.ts`,
`library/src/plugins/attributes/*.ts`. **Every factual claim about Datastar in prose or code
comments is checked against these files.** The Clojure SDK clone at `/tmp/lk-sources/datastar-clojure`
is for contrast only (posts / in-the-wild), never a dependency.

## Facts already verified (cite, don't re-derive)

- Browser → server: signals travel as JSON. GET and DELETE: URL-encoded JSON in query param
  `datastar`. POST/PUT/PATCH: JSON request body (`contentType: 'json'` default). (`sdk/ADR.md`
  ReadSignals table; `fetch.ts` lines ~139–149.)
- Default signal filter on requests: `include = /.*/, exclude = /(^|\.)_/`, so any signal whose name
  (or any path segment) starts with `_` is never sent. (`fetch.ts` line 40.)
- Requests carry header `Datastar-Request: true` and
  `Accept: text/event-stream, text/html, application/json`. (`fetch.ts` ~82–83.)
- Responses: `text/event-stream` is parsed as SSE; `text/html` is treated as one
  `datastar-patch-elements` (options from `datastar-selector`, `datastar-mode`, … headers);
  `application/json` as one `datastar-patch-signals`; `text/javascript` is run as a script.
  A **204** is accepted and does nothing (no retry). (`fetch.ts` ~575–640.)
- SSE event format (ADR): `event: TYPE\n`, then `id: ID\n` if given, then `retry: MS\n` unless
  1000, then one `data: …\n` per line, then a blank line. Event types: `datastar-patch-elements`,
  `datastar-patch-signals`. Executing a script = a patch-elements event appending a `<script>` to
  `body` (with `data-effect="el.remove()"` when auto-removing).
- Patch-elements data lines, golden order (from `sdk/test/get-cases/*/output.txt`, which wins over
  the ADR prose if they differ): `selector`, `mode` (omit if `outer`), `useViewTransition true`
  (omit if false), `namespace` (omit if html), then one `elements <line>` per line of HTML.
  Modes: `outer` (default, morph), `inner` (morph), `replace`, `prepend`, `append`, `before`,
  `after`, `remove`. Without a selector, each top-level element needs an `id`.
- Patch-signals: `onlyIfMissing true` (omit if false), then `signals <line>` per JSON line.
  Semantics are RFC 7386 JSON Merge Patch: `null` removes a signal.
- Attribute keys are written `data-<plugin>:<key>` (e.g. `data-on:click`, `data-signals:qty`,
  `data-bind:drink`). Kebab keys become camelCase signal names by default (`modifyCasing`);
  verify any casing claim against `library/src/utils/text.ts` before printing it.

## Hiccup conventions

- Attribute keywords with a colon work: `{:data-on:click "@post('/cart/items')"}` renders
  `data-on:click="@post('/cart/items')"`. Use **single quotes** inside expressions.
- **Never use `true` as an attribute value.** Hiccup renders `{:data-bind:drink true}` as
  `data-bind:drink="data-bind:drink"`. For valueless attributes use `""`.
- Render with `(str (hiccup2.core/html …))`. A full page starts with `"<!DOCTYPE html>"`.

## The SSE writer (written in part 2, copied after)

Pure functions return **strings**. Nothing in the core writes to a socket.

```clojure
(send-event event-type data-lines {:keys [id retry]})            ;; → "event: …\n…\n\n"
(patch-elements html {:keys [selector mode use-view-transition namespace id retry]})
(patch-signals signals {:keys [only-if-missing id retry]})       ;; signals: map (cheshire) or JSON string
(remove-elements selector opts)                                  ;; mode remove
(execute-script js {:keys [auto-remove attributes id retry]})    ;; auto-remove defaults true
(sse-response & events)   ;; one-shot Ring response: {:status 200 :headers {...} :body (apply str events)}
(html-response html)      ;; plain text/html response; Datastar morphs by id
(read-signals req)        ;; → map with keyword keys, from :query-params "datastar" or the JSON body
```

`sse-response` headers: `Content-Type: text/event-stream`, `Cache-Control: no-cache`.
(http-kit speaks HTTP/1.1; add `Connection: keep-alive` only on held-open streams.)

**One-shot vs held open.** A one-shot response is an ordinary Ring response whose body is the
concatenated events: the client reads to EOF. A held-open stream uses http-kit's
`org.httpkit.server/as-channel` with `:on-open`/`:on-close`, sends headers once
(`send! ch {:status 200 :headers …} false`) and then event strings with `close?` false.

**Testability rule.** Handlers are plain `(fn [req] → ring-response)` and tested by calling them
with request maps. Held-open streams go through a **hub** that takes a `send-fn` (a function of one
string); tests pass a fn that conj's onto an atom. Only demos start a real server.

## Brew Board: the domain

### Menu (prices in cents)

| id | name | base | sizes | milks |
|---|---|---|---|---|
| `:espresso` | Espresso | 300 | `:single :double` | none |
| `:americano` | Americano | 350 | `:small :large` | none |
| `:latte` | Latte | 450 | `:small :medium :large` | `:whole :oat :almond` |
| `:cappuccino` | Cappuccino | 425 | `:small :medium` | `:whole :oat` |
| `:mocha` | Mocha | 500 | `:small :medium :large` | `:whole :oat :almond` |
| `:chai` | Chai Latte | 450 | `:medium :large` | `:whole :oat :almond` |

Size deltas: `:single 0 :double 100 :small 0 :medium 50 :large 100`.
Milk deltas: `:whole 0 :oat 60 :almond 60`. Sizes and milks are listed in the order shown;
"first valid" means first in that order.

`(price-cents {:drink :latte :size :medium :milk :oat})` = 450 + 50 + 60 = **560**.
A drink with no milks takes `:milk nil`. Line total = price × `:qty`.

### State (one atom)

```clojure
{:sold-out #{}                         ;; milks the barista has marked out, e.g. #{:oat}
 :carts    {"sid-abc" [{:drink :latte :size :medium :milk :oat :qty 1}]}
 :orders   {1 {:id 1 :sid "sid-abc" :customer "Ada" :status :placed
               :lines [{:drink :latte :size :medium :milk :oat :qty 1}]}}
 :next-id  2}
```

Status flow: `:placed → :making → :ready → :picked-up` (picked-up orders leave the board).
Sold-out applies to milks only. Toy people: customers **Ada, Grace, Linus**; barista **Sam**.

### Where each piece of state lives (the answer key for part 4)

| State | Home | Why |
|---|---|---|
| Drink being configured (`drink`, `size`, `milk`, `qty`) | signals | form input; sent with the add command |
| Customer name | signal `customer` | input; sent with place-order |
| Is the menu drop-down open, hover, local toggles | `_`-prefixed signal (e.g. `_open`) | UI-only, never sent |
| Displayed price of the draft | `data-computed` from signals **and** recomputed on the server | display convenience; server is the price authority |
| Cart lines | server, keyed by `sid` cookie | survives reload, multi-device, validated |
| Orders, statuses, sold-out milks | server | shared between people |
| The option lists in the size/milk drop-downs | server-rendered HTML | depend on menu + stock; never mirrored into signals |
| Request in flight | `data-indicator:_loading` | local, transient |

### Session

A `sid` cookie (random UUID string) set on the first page GET (`ring.middleware.cookies`).
Toy: no signing.

### Routes (the capstone; lessons introduce subsets)

| Method | Path | Kind | Reads | Responds |
|---|---|---|---|---|
| GET | `/` | page | cookie | full customer page HTML |
| GET | `/board` | page | – | full barista page HTML |
| GET | `/customer/stream` | live stream | cookie | held open; whole `#app` view on every change |
| GET | `/board/stream` | live stream | – | held open; whole `#app` view on every change |
| GET | `/drink-options` | query | signals `drink`, `size`, `milk` | patch `#drink-options` (the drop-down) + patch signals `size`/`milk` if now invalid |
| POST | `/cart/items` | command | signals draft | 204 (live) / patch of `#cart` (part 5) |
| DELETE | `/cart/items/:idx` | command | cookie | same |
| POST | `/orders` | command | signal `customer`, cookie | same |
| POST | `/orders/:id/advance` | command | – | same |
| POST | `/stock/:milk/toggle` | command | – | same |
| GET | `/native/path-config` | json | – | the shell's navigation rules (part 7) |
| POST | `/native/device` | command | signal `pushToken` | 204 |

Commands require header `Datastar-Request: true` (403 otherwise) as a cheap guard: a
cross-origin page cannot set a custom header without a CORS preflight. The posts must say this is
a partial CSRF defense that relies on SameSite cookies and no permissive CORS, not a full one.

### The server-rendered drop-down (part 4, used again in parts 6–7)

- `#draft` contains `<select id="drink" data-bind:drink="" data-on:change="@get('/drink-options')">`
  with the six drinks, and `<div id="drink-options">` holding two selects:
  `<select id="size" data-bind:size="">` and `<select id="milk" data-bind:milk="">`.
- The `<option>` lists are **only ever HTML from the server**: `(drink-options-view drink sold-out)`
  returns the hiccup for `#drink-options`. A drink with no milks renders no milk select (a
  `<p class="muted">No milk</p>` instead). Sold-out milks render as
  `[:option {:value "oat" :disabled ""} "Oat (sold out)"]`.
- `GET /drink-options` reads `drink`, `size`, `milk` from signals. It responds with **two events**:
  the patch of `#drink-options`, and a patch-signals `{"size": first-valid, "milk": first-valid}`
  containing only the keys whose current value is invalid for the new drink (sold-out counts as
  invalid). A milk-less drink patches `"milk": ""` (empty string, not `null`: `null` would
  remove the signal under merge-patch, and the select's `data-bind` should keep a string).
- In part 6+ the drop-down is part of the whole-view render, so when Sam marks oat milk sold out,
  every open customer page re-renders and oat shows as sold out with no extra code. That's the
  payoff the part-4 post plants.
- Stretch exercise (part 4, prose + optional code, not an EXERCISE marker): a searchable combobox.
  `$query` must reach the server, so it is *not* `_`-prefixed; the server filters and re-renders
  the list; `$_open` (UI-only) is.

### The native shell (part 7)

- The shell is a WKWebView (iOS) / WebView (Android) app that loads `https://<host>/` and appends
  `BrewShell/1` to the User-Agent. `(shell-request? req)` checks for it.
- Shell pages omit the web header/nav (the shell draws native navigation chrome).
- **Navigation rules are served, not compiled in:** `GET /native/path-config` returns JSON
  `{"rules": [{"patterns": ["^/$"], "presentation": "root"}, {"patterns": ["^/orders/\\d+$"], "presentation": "push"}, {"patterns": ["^/settings$"], "presentation": "modal"}]}`.
  (Inspired by Hotwire Native's path configuration; the in-the-wild excerpt shows theirs.)
- **Bridge, native → web:** the shell runs
  `window.dispatchEvent(new CustomEvent('brew:native', {detail: {...}}))`. The page listens with
  `data-on:brew:native__window`. **Verify** this attribute key form against `on.ts` /
  `modifyCasing` before using it; if the colon is a problem, use event name `brew-native`. Typical
  message: `{type: "push-token", token: "…"}` → sets `$pushToken` and `@post('/native/device')`.
- **Bridge, web → native:** the server sends `execute-script` calling
  `window.BrewShell?.post({type: "haptic", style: "success"})`. The page defines
  `window.BrewShell.post` to forward to `window.webkit.messageHandlers.brew.postMessage(msg)` (iOS)
  or `window.BrewAndroid.postMessage(JSON.stringify(msg))` (Android `@JavascriptInterface`), and
  to do nothing in a plain browser.
- **The payoff:** when an order turns `:ready`, the customer's stream sends the re-rendered view
  **plus** a bridge script (haptic + local notification "Ada, your latte is ready"). New screens
  ship by deploying the server; the shell binary does not change.
- `native/ios/BrewShell/*.swift` and `native/android/.../MainActivity.kt`: small reference shells
  (~100–200 lines each), not built in CI. `native/README.md` says so and what was checked locally.

## Parts, files, exercises

Every lesson is standalone: `clojure/src/lessonNN/<slug>.clj`, ns `lessonNN.<slug>` (underscores
in file names per Clojure), tests `clojure/test/lessonNN/<slug>_test.clj`. A lesson may split into
2–3 namespaces under `lessonNN/` if it helps reading (e.g. `lessonNN.sse` copied from part 2), but
never requires another lesson's namespace. Each has a `-main` that starts http-kit on port 0 (OS
picks), makes real requests with `java.net.http.HttpClient`, prints a narrated walkthrough
(requests, raw SSE text, what the browser would do), stops the server, and exits 0 within ~10s.
Each lesson also exposes `(start! port)` / `(stop!)` so the learner can open it in a browser at the
REPL, and its `-main` prints the URL hint.

Exercise ids are kebab-case, unique across the course. A region is a whole function body.

| Part | Namespace | Builds | Exercises |
|---|---|---|---|
| 01 | `lesson01.hypermedia` | page with Datastar script + button; `GET /hello` one-shot SSE patching `#greeting`; same thing as a `text/html` response | `sse-event`, `hello-response` |
| 02 | `lesson02.event-stream` | the full SSE writer; golden tests against all `sdk/test/get-cases` (copied into `clojure/test/lesson02/cases/` with a NOTICE line crediting starfederation/datastar, MIT); a held-open `/clock` stream via `as-channel` | `send-event`, `patch-elements`, `patch-signals`, `execute-script` |
| 03 | `lesson03.signals` | draft form with `data-signals`, `data-bind`, `data-computed:price` (client), `data-text`, `data-show`, `data-class`, `data-indicator:_loading`; `GET /quote` reads signals and patches back the authoritative price and an error message | `read-signals`, `price-cents`, `quote-response` |
| 04 | `lesson04.where-state-lives` | the drop-down (`drink-options-view`, `GET /drink-options`); server-owned cart keyed by `sid` (`POST /cart/items` validating the draft) | `drink-options-view`, `drink-options-response`, `add-to-cart` |
| 05 | `lesson05.routes` | reitit route table for all commands/queries above (minus streams and native); pure `handle-command`; `wrap-datastar-request` guard; each command responds with the patches it changed (fragment-per-action style) | `handle-command`, `wrap-datastar-request`, `command-response` |
| 06 | `lesson06.live` | hub (`subscribe!`, `unsubscribe!`, `broadcast!`), `add-watch` on the state atom → broadcast; per-subscriber `view-for` (customer sees own cart + own orders; board sees all); commands now return 204; a re-render that produces identical HTML for a subscriber is not resent | `view-for`, `broadcast`, `stream-lifecycle` (the on-open/on-close fns) |
| 07 | `lesson07.native-shell` | `shell-request?`, shell-aware layout, `/native/path-config`, `/native/device`, bridge script builder, ready-notification diff | `shell-request`, `bridge-script`, `ready-notifications` (diff old/new state → `[{:sid … :message …}]` for orders that just became `:ready`) |

`price-cents` is an exercise in part 3 only; later lessons copy the reference implementation.

### Capstone: Brew Board

`clojure/src/brewboard/` — namespaces `brewboard.sse`, `brewboard.menu`, `brewboard.state`
(atom, `handle-command`), `brewboard.views` (pure hiccup, whole-page and whole-`#app` renders for
customer and board, shell-aware), `brewboard.hub`, `brewboard.routes`, `brewboard.native`,
`brewboard.main` (`-main` starts on `PORT` env or 8080 and prints
`customer: http://localhost:8080/  board: http://localhost:8080/board`). Tests in
`clojure/test/brewboard/`. No EXERCISE markers in the capstone. Demo for CI:
`brewboard.demo` `-main`: starts on port 0, opens a customer stream and a board stream, runs a
scripted session (Ada adds a medium oat latte, places the order, Sam advances it to ready, Sam
marks oat sold out), prints each stream's events abridged, exits 0.

The capstone owns `native/` too.

## Posts

`site/posts/NN-<slug>.html`, `site/viz/NN-<slug>.js`, `site/cards/NN.js`. Pseudocode is used
sparingly here (this is a usage course): prefer real Clojure and real HTML in
`<pre><code class="language-clojure|html|http">`, with `<pre class="pseudo">` for wire-level
walkthroughs and algorithms the reader won't write (the morph, the client's SSE parse loop). The
`.code-ref` links lesson files and the `clojure -M -m lessonNN.<slug>` demo command (no terminal
link: there is no terminal). `.build-it` names the exercises and the command
`cd clojure && clojure -M:ex -d ex -n exercises.lessonNN.<slug>-test`.

Internals to sprinkle (each where it informs a usage decision):
- Part 1–2: how `fetch.ts` reads the stream (fetch + a line parser, not `EventSource`; so POST
  bodies and headers work) — verify in source.
- Part 3: how a computed/effect learns its dependencies (reads during evaluation) and why that
  means `data-computed` reruns only when a signal it read changes. Verify in `engine/signals.ts`.
- Part 5–6: how the morph matches by id and keeps focused inputs / input values, so sending the
  whole view is safe. Verify in `patchElements.ts`.
- Part 6: retry/reconnect behavior of a held-open GET (`retryInterval`, `retryMaxCount`, …
  defaults in `fetch.ts`), and what the server should send on reconnect (the whole view again).

Exercise ids match `[\w-]+` (no `!` or `?`): the id `broadcast` marks `broadcast!`, the id
`shell-request` marks `shell-request?`.

## Found while building (authoritative; overrides anything above)

- **Attribute order matters, and hiccup sorts attributes alphabetically.** Datastar applies an
  element's `data-*` attributes in source order (the `el.dataset` loop in `engine.ts`), so on one
  element `data-computed` would run before `data-signals`. Put `data-signals` on an ancestor
  (e.g. `div#app`), never on the same element as attributes that read those signals.
- **`data-indicator` only tracks fetches started from its own element** (`indicator.ts` compares
  `elt !== el`). Put it on the element that does the `@get`/`@post`. Declare the indicator signal
  (e.g. `_loading false`) up front if an ancestor's `data-class` reads it.
- **Spaces around `-` in expressions.** `$foo-bar` is the signal `foo-bar` (genRx rewrite in
  `engine.ts`), so `$qty-1` is a signal name. Write `$qty - 1`.
- **Hiccup escapes `'` as `&apos;`** in attributes. Browsers decode it, but view-source shows it.
- **The "golden order" claim above is wrong**: the golden files disagree with each other.
  Datastar's `compare-sse.sh` ignores the order *between* different data-line kinds and keeps the
  order *within* one kind. Our writer emits selector, mode, useViewTransition,
  viewTransitionSelector, namespace, elements.
- `drink-options-view` takes `[drink sold-out choice]`, where `choice` is `{:size … :milk …}`, so
  the chosen options render as `selected` and the DOM stays consistent through the morph.
- Lessons 01–04 route with `case` on `[method uri]`. Part 5 introduces reitit.
- Lesson 03's `quote` signal is in cents. The browser estimate reads `_prices`, which is never sent.
