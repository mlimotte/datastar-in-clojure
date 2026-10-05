EE.addWild("05", [
 {
  "id": "05-datastar-status",
  "engine": "Datastar",
  "repo": "starfederation/datastar",
  "sha": "1efcdc3cb336ec3e9139491604e770e0329657bc",
  "path": "library/src/plugins/actions/fetch.ts",
  "lang": "typescript",
  "title": "<code>fetch.ts</code>: what a command's status code does in the browser",
  "prompt": "A command fails validation. Should it answer 422 with an error patch, or 200? Find the line that decides whether the body is read at all.",
  "notes": "<p>Any status other than 200 ends the request before the body is examined. Lines 592–605 either retry or call <code>dispose()</code> and return. A 204 is never retried and does nothing, which is why lesson 06's commands can answer 204 and leave the change to the stream. A 400–599 status first dispatches a <code>datastar-fetch</code> event of type <code>error</code> carrying the status (<code>onopen_</code>, the first segment). It is retried only under <code>retry: 'error'</code> or <code>'always'</code>; the default, <code>'auto'</code>, doesn't retry it.</p><p>So a 422 with a carefully rendered error message patches nothing. Brew Board's user-facing validation errors are 200 responses that patch a message (<code>$_error</code>). The only real error status is the guard's 403 for a request without <code>Datastar-Request</code>, and no user is meant to see that one. Any other 2xx, such as a 201, is dropped the same way as an error.</p>",
  "segments": [
   {
    "start": 104,
    "end": 108,
    "code": "          onopen_: async (response: Response) => {\n            if (response.status >= 400) {\n              dispatchFetch(ERROR, el, { status: response.status.toString() })\n            }\n          },"
   },
   {
    "start": 587,
    "end": 605,
    "code": "        const status = response.status\n        const isNoContentStatus = status === 204\n        const isRedirectStatus = status >= 300 && status < 400\n        const isErrorStatus = status >= 400 && status < 600\n\n        if (status !== 200) {\n          if (\n            retry !== 'never' &&\n            !isNoContentStatus &&\n            !isRedirectStatus &&\n            (retry === 'always' || (retry === 'error' && isErrorStatus))\n          ) {\n            retryRequest()\n            return\n          }\n          dispose()\n          resolve()\n          return\n        }"
   }
  ]
 },
 {
  "id": "05-clojure-sdk-request",
  "engine": "Datastar Clojure SDK",
  "repo": "starfederation/datastar-clojure",
  "sha": "b08e6877293413412fe8a9dc979c08899687a4f4",
  "path": "libraries/sdk/src/main/starfederation/datastar/clojure/api.clj",
  "lang": "clojure",
  "title": "The SDK's <code>redirect!</code> and <code>datastar-request?</code>",
  "prompt": "Compare <code>datastar-request?</code> with our <code>wrap-datastar-request</code>. Then read the warning on <code>redirect!</code>: which function in lesson 07 guards against the same problem, and how?",
  "notes": "<p><code>datastar-request?</code> makes the same test as our guard. Ring lower-cases header names, and the value must be the string <code>\"true\"</code>. The SDK supplies the predicate and leaves the policy to you. We wrap the command routes, so a request without the header gets 403 before the handler runs.</p><p><code>redirect!</code> is how a command answers \"go to another page\" when the reply is an SSE stream: an HTTP redirect would be followed by <code>fetch</code>, not by the page. So it sends <code>execute-script</code> with <code>window.location.href = \"…\"</code> inside a <code>setTimeout</code>. The warning matters. The URL is pasted into a JavaScript string literal inside a <code>&lt;script&gt;</code> element, so a quote, a newline or <code>&lt;/script&gt;</code> in it breaks out. Lesson 07's <code>bridge-script</code> builds the same kind of script. It encodes the message with cheshire, which handles quotes and newlines, and then escapes <code>&lt;/</code>, so a customer named <code>&lt;/script&gt;</code> can't end the element early. A command that sends the user somewhere could also skip the script: a held-open stream, or a page the user loads with a normal link, puts the navigation in HTML.</p>",
  "segments": [
   {
    "start": 514,
    "end": 541,
    "code": "(defn redirect!\n  \"Redirect a page using a script.\n\n  Same behavior as [[execute-script!]].\n\n  > [!WARNING]\n  > `url` is interpolated verbatim into a JavaScript double-quoted string\n  > literal. The caller is responsible for ensuring `url` is safe to embed\n  > there (no unescaped `\\\"`, `\\\\`, `</script>`, newlines, or other\n  > characters that would break out of the literal). Never pass\n  > unsanitized user input directly.\n  \"\n  ([sse-gen url]\n   (redirect! sse-gen url {}))\n  ([sse-gen url opts]\n   (execute-script! sse-gen\n                    (str \"setTimeout(() => window.location.href =\\\"\" url \"\\\")\")\n                    opts)))\n\n\n;; -----------------------------------------------------------------------------\n;; Misc\n;; -----------------------------------------------------------------------------\n(defn datastar-request?\n  \"Test for the presence of the datastar header in a ring request. The presence\n  of the header means the request is issued from a datastar action.\"\n  [request]\n  (= \"true\" (get-in request [:headers \"datastar-request\"])))"
   }
  ]
 },
 {
  "id": "05-htmx-oob",
  "engine": "htmx",
  "repo": "bigskysoftware/htmx",
  "sha": "fa978b24e75fb03c137bf2cdae4fef0e711cf8a1",
  "path": "src/htmx.js",
  "lang": "javascript",
  "title": "htmx out-of-band swaps: one response, several targets",
  "prompt": "Our <code>command-response</code> for <code>:place-order</code> patches <code>#cart</code> and <code>#my-orders</code> and clears <code>$customer</code>, all in one response. How would an htmx endpoint reach the second element, and which of the three can it not express?",
  "notes": "<p>An htmx response has one primary target, which the client chooses. Anything else has to be marked in the response with <code>hx-swap-oob</code>. <code>findAndSwapOobElements</code> collects those elements, and <code>oobSwap</code> targets each one by its own id (<code>\"true\"</code> means <code>outerHTML</code> on <code>#id</code>) or by a <code>swapStyle:selector</code> pair. Datastar applies this \"the response says where it goes\" rule to <em>every</em> element; in htmx it is the exception.</p><p>The fragment-per-action style in part 5 has the same limit in both systems: the response reaches only the browser that sent the command. Ada's place-order patch updates Ada's page, and Sam's board learns nothing until something else talks to it. Part 6's fix is not a better response but a second channel, a held-open stream for each viewer. Our response also carries a signal patch (<code>{\"customer\": \"\"}</code>). htmx has no client store to patch, so it would clear the name by re-rendering the input with an empty value.</p>",
  "segments": [
   {
    "start": 1461,
    "end": 1477,
    "code": "  function oobSwap(oobValue, oobElement, settleInfo, rootNode) {\n    rootNode = rootNode || getDocument()\n    let selector = '#' + CSS.escape(getRawAttribute(oobElement, 'id'))\n    /** @type HtmxSwapStyle */\n    let swapStyle = 'outerHTML'\n    if (oobValue === 'true') {\n      // do nothing\n    } else if (oobValue.indexOf(':') > 0) {\n      swapStyle = oobValue.substring(0, oobValue.indexOf(':'))\n      selector = oobValue.substring(oobValue.indexOf(':') + 1)\n    } else {\n      swapStyle = oobValue\n    }\n    oobElement.removeAttribute('hx-swap-oob')\n    oobElement.removeAttribute('data-hx-swap-oob')\n\n    const targets = querySelectorAllExt(rootNode, selector, false)"
   },
   {
    "start": 1900,
    "end": 1913,
    "code": "  function findAndSwapOobElements(fragment, settleInfo, rootNode) {\n    var oobElts = findAll(fragment, '[hx-swap-oob], [data-hx-swap-oob]')\n    forEach(oobElts, function(oobElement) {\n      if (htmx.config.allowNestedOobSwaps || oobElement.parentElement === null) {\n        const oobValue = getAttributeValue(oobElement, 'hx-swap-oob')\n        if (oobValue != null) {\n          oobSwap(oobValue, oobElement, settleInfo, rootNode)\n        }\n      } else {\n        oobElement.removeAttribute('hx-swap-oob')\n        oobElement.removeAttribute('data-hx-swap-oob')\n      }\n    })\n    return oobElts.length > 0"
   }
  ]
 }
]);
