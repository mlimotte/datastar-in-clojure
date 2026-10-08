EE.addWild("08", [
 {
  "id": "08-datastar-abort",
  "engine": "Datastar",
  "repo": "starfederation/datastar",
  "sha": "1efcdc3cb336ec3e9139491604e770e0329657bc",
  "path": "library/src/plugins/actions/fetch.ts",
  "lang": "typescript",
  "title": "fetch.ts: one AbortController per method and URL",
  "prompt": "What exactly is the key that decides whether a new <code>@get</code> aborts an older one? Would <code>@get('/search?q=la')</code> and <code>@get('/search?q=lat')</code> share a key?",
  "notes": "<p>The map is module-level: <code>abortControllers</code> → method → <code>url</code>, where <code>url</code> is the string you passed to the action, before Datastar appends the <code>datastar</code> query parameter. So two elements that both run <code>@get('/search')</code> cancel each other, and two different query strings never do. That is why this part's \"one URL, query in signals\" option fixes out-of-order results and the <code>?q=</code> option doesn't.</p><p>The default is <code>requestCancellation: 'auto'</code>. Passing <code>'disabled'</code> skips the map; passing your own <code>AbortController</code> uses it as the request's controller. Aborting is client-side only: the server still runs the handler to completion, which is why lesson 8's sequence-number guard exists for the cases where you want the server to notice.</p>",
  "segments": [
   {
    "start": 21,
    "end": 22,
    "code": "// Map of abort controllers keyed by method and URL\nconst abortControllers = new Map<string, Map<string, AbortController>>()"
   },
   {
    "start": 51,
    "end": 63,
    "code": "      const controller =\n        requestCancellation instanceof AbortController\n          ? requestCancellation\n          : new AbortController()\n      const cleanupName = `@${name}`\n      const cancelOnDisconnect = requestCancellation === 'cleanup'\n      let requestCleanup: (() => void) | undefined\n      if (requestCancellation === 'auto' || cancelOnDisconnect) {\n        const controllers = abortControllers.get(method) ?? new Map()\n        controllers.get(url)?.abort()\n        controllers.set(url, controller)\n        abortControllers.set(method, controllers)\n      }"
   }
  ]
 },
 {
  "id": "08-datastar-indicator",
  "engine": "Datastar",
  "repo": "starfederation/datastar",
  "sha": "1efcdc3cb336ec3e9139491604e770e0329657bc",
  "path": "library/src/plugins/attributes/indicator.ts",
  "lang": "typescript",
  "title": "indicator.ts: a counter driven by fetch events",
  "prompt": "Why does a network failure still turn the indicator off? Find where <code>FINISHED</code> is dispatched in <code>fetch.ts</code>.",
  "notes": "<p>The indicator is a count of in-flight fetches whose <code>el</code> is this element, driven by the <code>datastar-fetch</code> events. <code>fetch.ts</code> dispatches <code>FINISHED</code> in a <code>finally</code> block, so the count comes down after success, an error status, or a dropped connection. A hand-managed <code> = true; @post(…)</code> flag that the server clears never comes down when the response never arrives.</p><p>The <code>elt !== el</code> check is the catch: a <code>data-indicator</code> on a wrapper does not see fetches started by a button inside it. Put it on the element whose <code>data-on</code> runs the action.</p>",
  "segments": [
   {
    "start": 15,
    "end": 37,
    "code": "  apply({ el, key, mods, value }) {\n    const signalName = key != null ? modifyCasing(key, mods) : value\n    let activeFetches = 0\n\n    mergePaths([[signalName, false]])\n\n    const watcher = ((event: CustomEvent<DatastarFetchEvent>) => {\n      const { type, el: elt } = event.detail\n      if (elt !== el) {\n        return\n      }\n      switch (type) {\n        case STARTED:\n          activeFetches++\n          mergePaths([[signalName, true]])\n          break\n        case FINISHED:\n          activeFetches = Math.max(0, activeFetches - 1)\n          mergePaths([[signalName, activeFetches > 0]])\n          break\n      }\n    }) as EventListener\n    DOCUMENT.addEventListener(DATASTAR_FETCH_EVENT, watcher)"
   }
  ]
 },
 {
  "id": "08-htmx-sync",
  "engine": "htmx",
  "repo": "bigskysoftware/htmx",
  "sha": "fa978b24e75fb03c137bf2cdae4fef0e711cf8a1",
  "path": "src/htmx.js",
  "lang": "javascript",
  "title": "hx-sync: htmx synchronizes per element, not per URL",
  "prompt": "With no <code>hx-sync</code> at all, what happens when an element that already has a request in flight triggers another? Follow <code>queueStrategy</code> below this excerpt.",
  "notes": "<p>htmx makes the element the unit of synchronization. <code>hx-sync=\"this:replace\"</code> aborts the current request and sends the new one; <code>drop</code> ignores the new one; <code>queue first|last|all</code> runs them in turn. Without <code>hx-sync</code>, a second request from a busy element is queued (the default queue strategy is <code>last</code>), so one input's responses can't arrive out of order.</p><p>Datastar chose the URL as the unit and cancellation as the only built-in policy. That's simpler and covers the common case, but it means the URL you write decides ordering: put a changing query in the path or query string and you've opted out. When Datastar's rule doesn't fit, the options are the ones in this part: one URL, a sequence number, or debouncing to make overlap rare.</p>",
  "segments": [
   {
    "start": 4380,
    "end": 4411,
    "code": "    let syncElt = elt\n    let syncStrategy = getClosestAttributeValue(elt, 'hx-sync')\n    let queueStrategy = null\n    let abortable = false\n    if (syncStrategy) {\n      const syncStrings = syncStrategy.split(':')\n      const selector = syncStrings[0].trim()\n      if (selector === 'this') {\n        syncElt = findThisElement(elt, 'hx-sync')\n      } else {\n        syncElt = asElement(querySelectorExt(elt, selector))\n      }\n      // default to the drop strategy\n      syncStrategy = (syncStrings[1] || 'drop').trim()\n      eltData = getInternalData(syncElt)\n      if (syncStrategy === 'drop' && eltData.xhr && eltData.abortable !== true) {\n        maybeCall(resolve)\n        return promise\n      } else if (syncStrategy === 'abort') {\n        if (eltData.xhr) {\n          maybeCall(resolve)\n          return promise\n        } else {\n          abortable = true\n        }\n      } else if (syncStrategy === 'replace') {\n        triggerEvent(syncElt, 'htmx:abort') // abort the current request and continue\n      } else if (syncStrategy.indexOf('queue') === 0) {\n        const queueStrArray = syncStrategy.split(' ')\n        queueStrategy = (queueStrArray[1] || 'last').trim()\n      }\n    }"
   }
  ]
 },
 {
  "id": "08-htmx-history",
  "engine": "htmx",
  "repo": "bigskysoftware/htmx",
  "sha": "fa978b24e75fb03c137bf2cdae4fef0e711cf8a1",
  "path": "src/htmx.js",
  "lang": "javascript",
  "title": "hx-push-url: htmx owns the history stack",
  "prompt": "On Back, where does htmx get the page from first, and what does it do when that misses? (Read <code>restoreHistory</code> and <code>loadHistoryFromServer</code>.)",
  "notes": "<p>htmx builds history in: <code>hx-push-url</code> calls <code>history.pushState</code>, a snapshot of the history element is saved to <code>sessionStorage</code>, and its <code>onpopstate</code> handler restores the snapshot or, on a miss, re-fetches the URL with an <code>HX-History-Restore-Request</code> header (or reloads, if <code>refreshOnHistoryMiss</code> is set).</p><p>Datastar has none of this: there is no <code>pushState</code> or <code>popstate</code> in <code>library/src</code>. The trade is deliberate surface area. This part's options are full page loads (a <code>&lt;form method=\"get\"&gt;</code> gets the URL and Back for free), or a few lines of glue that mirror a server-stamped query string into <code>pushState</code> plus a <code>data-on:popstate__window</code> that re-reads <code>location.search</code>. There is no snapshot cache in that approach: Back always asks the server, which is what a server-owns-the-view app wants anyway.</p>",
  "segments": [
   {
    "start": 3329,
    "end": 3341,
    "code": "  function pushUrlIntoHistory(path) {\n  // remove the cache buster parameter, if any\n    if (htmx.config.getCacheBusterParam) {\n      path = path.replace(/org\\.htmx\\.cache-buster=[^&]*&?/, '')\n      if (endsWith(path, '&') || endsWith(path, '?')) {\n        path = path.slice(0, -1)\n      }\n    }\n    if (htmx.config.historyEnabled) {\n      history.pushState({ htmx: true }, '', path)\n    }\n    setCurrentPathForHistory(path)\n  }"
   },
   {
    "start": 5183,
    "end": 5199,
    "code": "    const originalPopstate = window.onpopstate ? window.onpopstate.bind(window) : null\n    /** @type {(ev: PopStateEvent) => any} */\n    window.onpopstate = function(event) {\n      if (event.state && event.state.htmx) {\n        restoreHistory()\n        forEach(restoredElts, function(elt) {\n          triggerEvent(elt, 'htmx:restored', {\n            document: getDocument(),\n            triggerEvent\n          })\n        })\n      } else {\n        if (originalPopstate) {\n          originalPopstate(event)\n        }\n      }\n    }"
   }
  ]
 },
 {
  "id": "08-idiomorph-move",
  "engine": "idiomorph",
  "repo": "bigskysoftware/idiomorph",
  "sha": "b33cbfcec44efb59b366caf9db1fcfe63662e002",
  "path": "src/idiomorph.js",
  "lang": "javascript",
  "title": "morphChildren: a re-sorted row is moved, not rebuilt",
  "prompt": "When the board re-sorts and order #3 moves up one row, is its <code>&lt;li&gt;</code> the same DOM node afterwards?",
  "notes": "<p>An element whose id is in the persistent-id set is moved to its new position (<code>moveBeforeById</code>, which uses the DOM's <code>moveBefore</code> where the browser has it and <code>insertBefore</code> otherwise) and then morphed. Datastar's <code>patchElements.ts</code> carries the same logic in its port: it looks the node up with <code>getElementById</code> and calls its own <code>moveBefore</code>.</p><p>So a whole-view re-render keeps the row's node, listeners and state, but not its place on screen. Identity is preserved; position is not. That is the cost this part names: under a whole-view render the server's sort order wins, and whatever was under the user's pointer can change. A targeted patch keeps the row where it was and lets the sort go stale instead.</p>",
  "segments": [
   {
    "start": 307,
    "end": 324,
    "code": "        // if the matching node is elsewhere in the original content\n        if (is.element(newChild)) {\n          // we can pretend the id is non-null because the next `.has` line will reject it if not\n          const newChildId = /** @type {String} */ (\n            newChild.getAttribute(\"id\")\n          );\n          if (ctx.persistentIds.has(newChildId)) {\n            // move it and all its children here and morph\n            const movedChild = moveBeforeById(\n              oldParent,\n              newChildId,\n              insertionPoint,\n              ctx,\n            );\n            morphNode(movedChild, newChild, ctx);\n            insertionPoint = movedChild.nextSibling;\n            continue;\n          }"
   }
  ]
 }
]);
