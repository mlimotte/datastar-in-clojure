EE.addWild("03", [
 {
  "id": "03-datastar-signal-tracking",
  "engine": "Datastar",
  "repo": "starfederation/datastar",
  "sha": "1efcdc3cb336ec3e9139491604e770e0329657bc",
  "path": "library/src/engine/signals.ts",
  "lang": "typescript",
  "title": "<code>signals.ts</code>: a read links, a write propagates",
  "prompt": "Changing milk from oat to almond recomputes <code>$estimate</code> but re-runs no <code>data-text</code>. Which line returns the answer that stops it?",
  "notes": "<p><code>signalOper</code> is both getter and setter. Called with no argument, it returns the value. If something is being evaluated at that moment (<code>activeSub</code>), it also calls <code>link(s, activeSub)</code>, and that read <em>is</em> the dependency. There is no declared dependency list. A <code>data-computed</code> or <code>data-text</code> depends on exactly the signals its expression read the last time it ran. Called with a value, it compares with <code>!==</code>, marks the signal dirty and propagates to its subscribers. Unless a batch is open, it then runs the queued effects immediately.</p><p><code>updateComputed</code> runs the getter with the computed itself as <code>activeSub</code>. <code>startPeeking(c)</code> pushes <code>c</code>, despite the name. The getter runs between <code>startTracking</code> and <code>endTracking</code>, and <code>endTracking</code> unlinks any dependency the getter didn't read this time, so a branch not taken stops subscribing. The return value, <code>oldValue !== newValue</code>, answers the prompt. Oat and almond both add 60, so the estimate stays 560 and the function returns false. <code>computedOper</code> then skips <code>shallowPropagate</code>. A pending effect such as a <code>data-text</code> runs only if <code>checkDirty</code> finds a dependency that really changed (lines 183&ndash;187 and 466), and <code>checkDirty</code> asks this same function.</p>",
  "segments": [
   {
    "start": 153,
    "end": 163,
    "code": "const updateComputed = (c: AlienComputed): boolean => {\n  startPeeking(c)\n  startTracking(c)\n  try {\n    const oldValue = c.value_\n    return oldValue !== (c.value_ = c.getter_(oldValue))\n  } finally {\n    stopPeeking()\n    endTracking(c)\n  }\n}"
   },
   {
    "start": 214,
    "end": 242,
    "code": "const signalOper = <T>(s: AlienSignal<T>, ...value: [T]): T | boolean => {\n  if (value.length) {\n    if (s.value_ !== (s.value_ = value[0])) {\n      s.flags_ = 17 as ReactiveFlags.Mutable | ReactiveFlags.Dirty\n      const subs = s.subs_\n      if (subs) {\n        propagate(subs)\n        if (!batchDepth) {\n          flush()\n        }\n      }\n      return true\n    }\n    return false\n  }\n  const currentValue = s.value_\n  if (s.flags_ & (16 satisfies ReactiveFlags.Dirty)) {\n    if (updateSignal(s, currentValue)) {\n      const subs_ = s.subs_\n      if (subs_) {\n        shallowPropagate(subs_)\n      }\n    }\n  }\n  if (activeSub) {\n    link(s, activeSub)\n  }\n  return currentValue\n}"
   }
  ]
 },
 {
  "id": "03-datastar-filtered",
  "engine": "Datastar",
  "repo": "starfederation/datastar",
  "sha": "1efcdc3cb336ec3e9139491604e770e0329657bc",
  "path": "library/src/engine/signals.ts",
  "lang": "typescript",
  "title": "<code>filtered</code>: how the <code>_</code> rule is applied, path by path",
  "prompt": "The default exclude in <code>fetch.ts</code> is <code>/(^|\\.)_/</code>. Is a nested signal <code>draft._open</code> sent? Which line decides, and why does the regex need the <code>\\.</code> branch?",
  "notes": "<p><code>filtered</code> walks the signal tree and tests each <em>leaf path</em> (dot-joined, like <code>draft.size</code>) against include and exclude. Then it rebuilds an object from the paths that pass. Its own default exclude is <code>/(?!)/</code>, a regex that matches nothing. The <code>_</code> rule comes from the caller, the fetch action (<code>exclude = /(^|\\.)_/</code>, <code>fetch.ts</code> line 40). The <code>(^|\\.)</code> matches an underscore at the start of <em>any</em> path segment, so <code>_loading</code>, <code>draft._open</code> and <code>_ui.anything</code> all stay in the browser. <code>my_var</code> is sent, because its underscore doesn't start a segment.</p><p>Two consequences for the body our <code>read-signals</code> parses. Computed signals are leaves like any other: the store's proxy returns their current value, so <code>$estimate</code> is sent unless its name starts with <code>_</code>. The server should ignore it and recompute, as part 3's <code>quote-response</code> does. And an object whose children are all excluded disappears, because only leaves are collected. A single request can override both options: lesson 07's bridge uses <code>@post('/native/device', {filterSignals: {include: /^pushToken$/}})</code> to send one signal.</p>",
  "segments": [
   {
    "start": 760,
    "end": 782,
    "code": "export const filtered = (\n  { include = /.*/, exclude = /(?!)/ }: SignalFilterOptions = {},\n  obj: JSONPatch = root,\n): Record<string, any> => {\n  const includeRe = toRegExp(include)\n  const excludeRe = toRegExp(exclude)\n  const paths: Paths = []\n\n  const walk = (node: JSONPatch, prefix = '') => {\n    for (const key in node) {\n      const path = prefix + key\n      if (isPojo(node[key])) {\n        walk(node[key], `${path}.`)\n      } else if (includeRe.test(path) && !excludeRe.test(path)) {\n        paths.push([path, getPath(path)])\n      }\n    }\n  }\n\n  walk(obj)\n\n  return pathToObj(paths)\n}"
   }
  ]
 },
 {
  "id": "03-go-sdk-read-signals",
  "engine": "Datastar Go SDK",
  "repo": "starfederation/datastar-go",
  "sha": "60dc10ebdaad3207d71e4bd8c1f158e65bb4acb0",
  "path": "datastar/signals.go",
  "lang": "go",
  "title": "Go SDK <code>ReadSignals</code>: the same two sources, into a struct",
  "prompt": "Our <code>read-signals</code> returns <code>{}</code> when there is nothing to read. What does <code>ReadSignals</code> do with a GET that has no <code>datastar</code> parameter?",
  "notes": "<p>The method split is ours exactly: GET and DELETE read the <code>datastar</code> query parameter, and every other method reads the body. A GET with no parameter returns <code>nil</code> and leaves the target untouched, the struct version of our <code>{}</code>. The difference is the target. <code>json.Unmarshal</code> fills a struct that the caller supplies, so only the fields the handler declares are kept, and their types are checked on the way in. Ours turns whatever arrives into a map with keyword keys, and each handler picks the keys it wants (part 3's <code>-&gt;draft</code> does that).</p><p>Read the error text for a failed body read: \"are you sure you created the SSE ***AFTER*** the ReadSignals?\". The <code>net/http</code> documentation warns that once a handler writes the response, later reads of the request body may fail, and <code>NewSSE</code> flushes the headers immediately. So in Go, read the signals first and open the stream second. A Ring handler reads its body before it returns a response map, so our handlers don't meet this ordering rule.</p>",
  "segments": [
   {
    "start": 94,
    "end": 120,
    "code": "func ReadSignals(r *http.Request, signals any) error {\n\tvar dsInput []byte\n\n\tif r.Method == \"GET\" || r.Method == \"DELETE\" {\n\t\tdsJSON := r.URL.Query().Get(DatastarKey)\n\t\tif dsJSON == \"\" {\n\t\t\treturn nil\n\t\t} else {\n\t\t\tdsInput = []byte(dsJSON)\n\t\t}\n\t} else {\n\t\tbuf := bytebufferpool.Get()\n\t\tdefer bytebufferpool.Put(buf)\n\t\tif _, err := buf.ReadFrom(r.Body); err != nil {\n\t\t\tif err == http.ErrBodyReadAfterClose {\n\t\t\t\treturn fmt.Errorf(\"body already closed, are you sure you created the SSE ***AFTER*** the ReadSignals? %w\", err)\n\t\t\t}\n\t\t\treturn fmt.Errorf(\"failed to read body: %w\", err)\n\t\t}\n\t\tdsInput = buf.Bytes()\n\t}\n\n\tif err := json.Unmarshal(dsInput, signals); err != nil {\n\t\treturn fmt.Errorf(\"failed to unmarshal: %w\", err)\n\t}\n\treturn nil\n}"
   }
  ]
 },
 {
  "id": "03-clojure-sdk-signals",
  "engine": "Datastar Clojure SDK",
  "repo": "starfederation/datastar-clojure",
  "sha": "b08e6877293413412fe8a9dc979c08899687a4f4",
  "path": "libraries/sdk/src/main/starfederation/datastar/clojure/api/signals.clj",
  "lang": "clojure",
  "title": "The SDK's signal helpers: JSON text in, JSON text out",
  "prompt": "Our <code>read-signals</code> returns a Clojure map, and our <code>patch-signals</code> accepts one. What do the SDK's <code>get-signals</code> and <code>-&gt;patch-signals</code> take and return instead?",
  "notes": "<p>In both directions the SDK stops at JSON text. <code>get-signals</code> picks the source by method, the same split as ours and the Go SDK's, and returns it unparsed. For GET and DELETE that is a string from <code>:query-params</code>, which needs params middleware to exist. For other methods it is the body <code>InputStream</code>. The docstring says \"Bring your own json parsing\", so the SDK depends on no JSON library. <code>-&gt;patch-signals</code> takes signals that are already JSON text and splits the text into one <code>signals</code> data line per line (the <code>comment</code> shows a two-line input becoming two data lines). It writes <code>onlyIfMissing true</code> only when that isn't the default.</p><p>Ours parses with cheshire into keyword keys. Our <code>patch-signals</code> accepts a map or a string and encodes a map itself, so part 3's <code>quote-response</code> can pass a Clojure map straight in. The cost is a dependency. The SDK's own <code>examples/hello-httpkit</code> supplies the missing step as <code>(-&gt; req d*/get-signals read-json)</code>, using charred.</p>",
  "segments": [
   {
    "start": 12,
    "end": 29,
    "code": "(defn add-only-if-missing? [v]\n  (common/add-boolean-option? consts/default-patch-signals-only-if-missing v))\n\n(defn ->patch-signals [signals opts]\n  (let [oim (common/only-if-missing opts)]\n    (u/transient-> []\n      (cond->\n        (and oim (add-only-if-missing? oim))\n        (common/add-opt-line! consts/only-if-missing-dataline-literal oim)\n\n        (u/not-empty-string? signals)\n        (common/add-data-lines! consts/signals-dataline-literal signals)))))\n\n\n\n(comment\n  (= (->patch-signals \"{'some': \\n 'json'}\" {})\n     [\"signals {'some': \""
   },
   {
    "start": 47,
    "end": 63,
    "code": "(defn get-signals\n  \"Extract datastar signals from a ring request map.\n\n  - For GET and DELETE requests, returns the signals json string from\n    `[:query-params consts/datastar-key]`. You need to use some middleware\n    that adds the `:query-params` key to the request for this function to\n    work properly.\n  - For all other HTTP methods, returns the request `:body` as an\n    `InputStream`.\n\n  (Bring your own json parsing)\"\n  [request]\n  (case (:request-method request)\n    (:get :delete)\n    (get-in request [:query-params consts/datastar-key])\n\n    (:body request)))"
   }
  ]
 }
]);
