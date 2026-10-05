EE.addWild("06", [
 {
  "id": "06-datastar-morph-input",
  "engine": "Datastar",
  "repo": "starfederation/datastar",
  "sha": "1efcdc3cb336ec3e9139491604e770e0329657bc",
  "path": "library/src/plugins/watchers/patchElements.ts",
  "lang": "typescript",
  "title": "<code>morphNode</code>: inputs compare the <code>value</code> attribute, not what the user typed",
  "prompt": "Ada has typed \"Ad\" into the name box when Sam's advance re-renders <code>#app</code>. Which comparison leaves her text alone, and what would make the morph overwrite it?",
  "notes": "<p>For an input, the morph reads <code>getAttribute('value')</code> on the old (live) element and on the new (parsed) one. It writes the live <code>.value</code> only if those two attributes differ. What Ada typed is in the live property, not the attribute. Brew Board renders the input with no <code>value</code> attribute, so both reads return <code>null</code> and nothing is written. If you render <code>value=\"…\"</code> from server state, a change to that state overwrites whatever is being typed, whether the input has focus or not. <code>checked</code> and <code>disabled</code> use the presence comparison from part 4's excerpt. File inputs are skipped. When the value or <code>checked</code> does change, the morph fires <code>datastar-prop-change</code> afterward (lines 682–687) so that <code>data-bind</code> updates the signal.</p><p>Focus survives for a different reason: the input is the same DOM node before and after, because the morph changes nodes in place instead of replacing them. idiomorph adds a safety net for the cases where the focused element is replaced anyway (<code>saveAndRestoreFocus</code>, <code>restoreFocus: true</code> by default).</p>",
  "segments": [
   {
    "start": 627,
    "end": 646,
    "code": "    let shouldDispatchPropChangeEvent = false\n    if (\n      oldElt instanceof HTMLInput &&\n      newElt instanceof HTMLInput &&\n      newElt.type !== 'file'\n    ) {\n      // Modify the value only if the new element’s value attribute is different from the old element’s value attribute\n      const newValue = newElt.getAttribute('value')\n      if (\n        oldElt.getAttribute('value') !== newValue &&\n        !preserveAttrs.includes('value')\n      ) {\n        oldElt.value = newValue ?? ''\n        shouldDispatchPropChangeEvent = true\n      }\n      // Update checked and disabled properties\n      shouldDispatchPropChangeEvent =\n        updateElementProp(oldElt, newElt, 'checked') ||\n        shouldDispatchPropChangeEvent\n      updateElementProp(oldElt, newElt, 'disabled')"
   }
  ]
 },
 {
  "id": "06-datastar-reconnect",
  "engine": "Datastar",
  "repo": "starfederation/datastar",
  "sha": "1efcdc3cb336ec3e9139491604e770e0329657bc",
  "path": "library/src/plugins/actions/fetch.ts",
  "lang": "typescript",
  "title": "<code>fetch.ts</code>: retrying a held-open <code>@get</code>",
  "prompt": "Which line guarantees that a reconnect sends the <em>current</em> signals? Which line is the only reason a stream the server closed cleanly opens again?",
  "notes": "<p><code>rebuildAndRetry</code> calls <code>buildFetchEventSourceInit</code> again, the function from part 1's excerpt. Every retry re-reads the signals and re-encodes the URL or the body; nothing is replayed from the first request. <code>retryRequest</code> is the backoff. It waits <code>retryInterval</code>, then multiplies the interval by <code>retryScaler</code> (2), up to <code>retryMaxWait</code> (30 s), for at most <code>retryMaxCount</code> (10) tries. After that it dispatches <code>retries-failed</code> and gives up. A successful 200 resets the count and the interval (lines 607–609).</p><p>The last segment runs after the body has been read to the end without an error, which means the server closed the stream on purpose. Only <code>retry: 'always'</code> reconnects in that case. That's why Brew Board opens its streams with <code>@get('/customer/stream', {retry: 'always'})</code>. A graceful server restart ends every stream cleanly, and under the default the pages would stay disconnected. Our <code>on-open</code> sends the whole view, so a reconnect needs no event ids and no replay. If we did send ids, Datastar would return the last one in a <code>last-event-id</code> header on retry (lines 659–666).</p>",
  "segments": [
   {
    "start": 497,
    "end": 504,
    "code": "    const rebuildAndRetry = () => {\n      const currentFetchInit = buildFetchEventSourceInit()\n      if (!currentFetchInit) return\n\n      input = currentFetchInit.input_\n      rest.body = currentFetchInit.body\n      create()\n    }"
   },
   {
    "start": 532,
    "end": 548,
    "code": "    let retries = 0\n    let baseRetryInterval = retryInterval\n\n    const retryRequest = () => {\n      if (retries < retryMaxCount) {\n        dispatchFetch(RETRYING, el, {})\n        clearTimeout(retryTimer)\n        retryTimer = setTimeout(rebuildAndRetry, retryInterval)\n        retries++\n        // Prepare the interval for the next retry (exponential backoff)\n        retryInterval = Math.min(retryInterval * retryScaler, retryMaxWait)\n      } else {\n        dispatchFetch(RETRIES_FAILED, el, {})\n        dispose()\n        reject('Max retries reached.')\n      }\n    }"
   },
   {
    "start": 676,
    "end": 679,
    "code": "        if (retry === 'always' && !isRedirectStatus) {\n          retryRequest()\n          return\n        }"
   }
  ]
 },
 {
  "id": "06-idiomorph-input",
  "engine": "idiomorph",
  "repo": "bigskysoftware/idiomorph",
  "sha": "b33cbfcec44efb59b366caf9db1fcfe63662e002",
  "path": "src/idiomorph.js",
  "lang": "javascript",
  "title": "idiomorph <code>syncInputValue</code>: the server's value wins unless you opt out",
  "prompt": "Run Ada's \"Ad\" through this function instead. The server's new HTML has no <code>value</code> attribute. What happens to her text, and which option would have kept it?",
  "notes": "<p>idiomorph compares the <em>live</em> values, <code>newElement.value</code> and <code>oldElement.value</code>. Worse for our case: if the new input has no <code>value</code> attribute, it clears the old one (<code>oldElement.value = \"\"</code>, lines 741–745). The same whole-view re-render that leaves \"Ad\" alone under Datastar would empty the box under idiomorph's defaults. The opt-out is <code>ignoreActiveValue</code>, which is not among idiomorph's defaults. With it set, <code>ignoreAttribute</code> skips <code>value</code> updates on the focused element only. A half-filled field the user has tabbed away from is still overwritten.</p><p>idiomorph's README lists Datastar as embedding a TypeScript port of it. The matching algorithm in <code>patchElements.ts</code> still follows idiomorph closely: id sets, soft matches, a hidden \"pantry\" for moved nodes. Input handling is where the port differs. Comparing attributes makes \"the server renders the view, the browser owns what's being typed\" the default rather than an option, and that is what makes part 6's send-the-whole-view design safe for forms.</p>",
  "segments": [
   {
    "start": 728,
    "end": 751,
    "code": "    function syncInputValue(oldElement, newElement, ctx) {\n      if (\n        is.inputElement(oldElement) &&\n        is.inputElement(newElement) &&\n        newElement.type !== \"file\"\n      ) {\n        let newValue = newElement.value;\n        let oldValue = oldElement.value;\n\n        // sync boolean attributes\n        syncBooleanAttribute(oldElement, newElement, \"checked\", ctx);\n        syncBooleanAttribute(oldElement, newElement, \"disabled\", ctx);\n\n        if (!newElement.hasAttribute(\"value\")) {\n          if (!ignoreAttribute(\"value\", oldElement, \"remove\", ctx)) {\n            oldElement.value = \"\";\n            oldElement.removeAttribute(\"value\");\n          }\n        } else if (oldValue !== newValue) {\n          if (!ignoreAttribute(\"value\", oldElement, \"update\", ctx)) {\n            oldElement.setAttribute(\"value\", newValue);\n            oldElement.value = newValue;\n          }\n        }"
   },
   {
    "start": 821,
    "end": 833,
    "code": "    function ignoreAttribute(attr, element, updateType, ctx) {\n      if (\n        attr === \"value\" &&\n        ctx.ignoreActiveValue &&\n        element === ctx.doc.activeElement\n      ) {\n        return true;\n      }\n      return (\n        ctx.callbacks.beforeAttributeUpdated(attr, element, updateType) ===\n        false\n      );\n    }"
   }
  ]
 },
 {
  "id": "06-clojure-sdk-http-kit",
  "engine": "Datastar Clojure SDK",
  "repo": "starfederation/datastar-clojure",
  "sha": "b08e6877293413412fe8a9dc979c08899687a4f4",
  "path": "libraries/sdk-http-kit/src/main/starfederation/datastar/clojure/adapter/http_kit.clj",
  "lang": "clojure",
  "title": "The SDK's http-kit adapter: <code>as-channel</code> behind <code>-&gt;sse-response</code>",
  "prompt": "Our <code>stream-handler</code> calls <code>hk/as-channel</code> directly. What does the SDK do in <code>:on-close</code> that ours doesn't, and why are the promises dereferenced with a timeout of 0?",
  "notes": "<p>The skeleton is lesson 06's: <code>as-channel</code>, headers sent first in <code>:on-open</code> (<code>send-base-sse-response!</code>), the user's callback given something to send with, and a close callback. The SDK wraps the channel in a generator. Every event goes through one <code>send!</code> function chosen by the write profile (plain, buffered, gzip), and the generator takes a lock for each event. Ours passes the hub a plain function of one string.</p><p>On close, the SDK calls <code>close-sse!</code> with two thunks. The first calls the zero-argument <code>send!</code>, which closes the profile's IO resources (a gzip stream, for example). The second calls your <code>on-close</code>. Each runs once, and exceptions from either are collected. Both values come from <code>(deref p 0 nil)</code>: if the connection closes before <code>:on-open</code> has delivered them, they are <code>nil</code>. The steps are then skipped instead of blocking an http-kit thread. All of this depends on http-kit firing <code>:on-close</code> when the browser goes away. The adapter's own <code>deps.edn</code> pins http-kit 2.9.0-beta3, the same beta line this course uses.</p>",
  "segments": [
   {
    "start": 56,
    "end": 77,
    "code": "  [ring-request opts]\n  {:pre [(ac/on-open opts)]}\n  (let [on-open-cb (ac/on-open opts)\n        on-close-cb (ac/on-close opts)\n        future-send! (promise)\n        future-gen (promise)]\n    (hk-server/as-channel ring-request\n      {:on-open\n       (fn [ch]\n         (impl/send-base-sse-response! ch ring-request opts)\n         (let [send! (impl/->send! ch opts)\n               sse-gen (impl/->sse-gen ch send! opts)]\n           (deliver future-gen sse-gen)\n           (deliver future-send! send!)\n           (on-open-cb sse-gen)))\n\n       :on-close\n       (fn [_ status]\n         (ac/close-sse!\n          #(when-let [send! (deref future-send! 0 nil)] (send!))\n          #(when on-close-cb\n             (on-close-cb (deref future-gen 0 nil) status))))})))"
   }
  ]
 }
]);
