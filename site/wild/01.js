EE.addWild("01", [
 {
  "id": "01-datastar-fetch-request",
  "engine": "Datastar",
  "repo": "starfederation/datastar",
  "sha": "1efcdc3cb336ec3e9139491604e770e0329657bc",
  "path": "library/src/plugins/actions/fetch.ts",
  "lang": "typescript",
  "title": "<code>fetch.ts</code>: what every <code>@get</code> puts on the wire",
  "prompt": "Our <code>/hello</code> handler is reached by <code>@get('/hello')</code>. Which lines put its signals in the query string instead of the body, and what changes if the button says <code>@post</code>?",
  "notes": "<p>Every Datastar action sends the same two headers: <code>Accept: text/event-stream, text/html, application/json</code> (the three body types the response handler knows) and <code>Datastar-Request: true</code>. <code>Content-Type: application/json</code> is added only when the method can carry a body. <code>buildFetchEventSourceInit</code> then serializes the signals (<code>filtered({ include, exclude })</code>, or an explicit <code>payload</code>). <code>methodSupportsRequestBody</code> is false for GET and DELETE (lines 23&ndash;24), so for those the JSON goes into the <code>datastar</code> query parameter. That is the <code>datastar=%7B%7D</code> in part 1's request: the page declares no signals yet, so the JSON is <code>{}</code>. With <code>@post</code> the same JSON becomes the request body.</p><p>Two details here that later parts use. The serialization runs between <code>startPeeking()</code> and <code>stopPeeking()</code>, which reads signals without subscribing anything to them (part 3). And this function is called again on every retry (<code>rebuildAndRetry</code>), so a reconnecting stream sends the signals as they are at that moment, not as they were at the first click (part 6).</p>",
  "segments": [
   {
    "start": 81,
    "end": 88,
    "code": "        const headers: Record<string, any> = {\n          Accept: 'text/event-stream, text/html, application/json',\n          'Datastar-Request': true,\n        }\n        if (contentType === 'json' && methodSupportsRequestBody(method)) {\n          headers['Content-Type'] = 'application/json'\n        }\n        Object.assign(headers, userHeaders)"
   },
   {
    "start": 137,
    "end": 150,
    "code": "        const buildFetchEventSourceInit = () => {\n          const urlInstance = new URL(url, DOCUMENT.baseURI)\n          const queryParams = new URLSearchParams(urlInstance.search)\n          if (contentType === 'json') {\n            startPeeking()\n            const requestPayload =\n              payload !== undefined ? payload : filtered({ include, exclude })\n            stopPeeking()\n            const body = JSON.stringify(requestPayload)\n            if (methodSupportsRequestBody(method)) {\n              req.body = body\n            } else {\n              queryParams.set('datastar', body)\n            }"
   }
  ]
 },
 {
  "id": "01-htmx-headers",
  "engine": "htmx",
  "repo": "bigskysoftware/htmx",
  "sha": "fa978b24e75fb03c137bf2cdae4fef0e711cf8a1",
  "path": "src/htmx.js",
  "lang": "javascript",
  "title": "htmx <code>getHeaders</code>: the client tells the server where the answer will go",
  "prompt": "Datastar sends no header that names a target element. Which htmx header carries one, and where in our part 1 response does that information live instead?",
  "notes": "<p>htmx sends <code>HX-Request: true</code>, the same job as <code>Datastar-Request: true</code>. It also sends the id and name of the element that fired (<code>HX-Trigger</code>, <code>HX-Trigger-Name</code>), the id of the element the response will be swapped into (<code>HX-Target</code>), and the page URL. In htmx the <em>client</em> decides where the response goes (<code>hx-target</code> and <code>hx-swap</code> on the element), so the server may want to know.</p><p>In Datastar the response decides. Our <code>/hello</code> answer carries <code>&lt;p id=\"greeting\"&gt;</code>: the id in the HTML is the target, and a <code>selector</code> data line can name a different one. Nothing about the target has to travel up. The request bodies differ too. htmx sends form-encoded input values: the element's own, its form's for non-GET requests, and anything named in <code>hx-include</code> (<code>getInputValues</code>). Datastar sends every signal that doesn't start with <code>_</code>, as one JSON document.</p>",
  "segments": [
   {
    "start": 3750,
    "end": 3767,
    "code": "  function getHeaders(elt, target, prompt) {\n    /** @type HtmxHeaderSpecification */\n    const headers = {\n      'HX-Request': 'true',\n      'HX-Trigger': getRawAttribute(elt, 'id'),\n      'HX-Trigger-Name': getRawAttribute(elt, 'name'),\n      'HX-Target': getAttributeValue(target, 'id'),\n      'HX-Current-URL': location.href\n    }\n    getValuesForElement(elt, 'hx-headers', false, headers)\n    if (prompt !== undefined) {\n      headers['HX-Prompt'] = prompt\n    }\n    if (getInternalData(elt).boosted) {\n      headers['HX-Boosted'] = 'true'\n    }\n    return headers\n  }"
   }
  ]
 },
 {
  "id": "01-clojure-sdk-headers",
  "engine": "Datastar Clojure SDK",
  "repo": "starfederation/datastar-clojure",
  "sha": "b08e6877293413412fe8a9dc979c08899687a4f4",
  "path": "libraries/sdk/src/main/starfederation/datastar/clojure/api/sse.clj",
  "lang": "clojure",
  "title": "SDK <code>headers</code>: the SSE response headers, and when to add <code>Connection</code>",
  "prompt": "Our <code>hello-response</code> sends two headers. Which header does the SDK add that we don't, and for which values of <code>:protocol</code>?",
  "notes": "<p>The SDK starts from our two headers, <code>Cache-Control: no-cache</code> and <code>Content-Type: text/event-stream</code>, and merges user headers last so they can override. The third header is conditional. <code>add-keep-alive?</code> is true when the Ring request has no <code>:protocol</code>, or one that sorts before <code>\"HTTP/1.1\"</code> (the <code>comment</code> block under it tries 0.9, 1.0, 1.1 and 2). HTTP/1.1 connections are persistent by default, and HTTP/2 forbids connection-specific headers, so only HTTP/1.0 and older get <code>Connection: keep-alive</code>.</p><p>Implementations read this rule three ways. Datastar's <code>sdk/ADR.md</code> says to set the header for \"HTTP/1.1 only\". The Go SDK sets it whenever <code>r.ProtoMajor == 1</code>. This SDK sets it only below 1.1. Our lessons add it on held-open streams (from part 2) and leave it off one-shot responses, which end with the body. The SDK has no one-shot response like ours: <code>-&gt;sse-response</code> opens a stream that stays open until you close it, which is why its README example ends with <code>close-sse!</code>.</p>",
  "segments": [
   {
    "start": 13,
    "end": 21,
    "code": "(def base-SSE-headers\n  {\"Cache-Control\" \"no-cache\"\n   \"Content-Type\"  \"text/event-stream\"})\n\n\n(defn add-keep-alive? [ring-request]\n  (let [protocol (:protocol ring-request)]\n    (or (nil? protocol)\n        (neg? (compare protocol \"HTTP/1.1\")))))"
   },
   {
    "start": 45,
    "end": 51,
    "code": "  [ring-request & {:as opts}]\n  (-> (transient {})\n      (u/merge-transient! base-SSE-headers)\n      (cond->\n        (add-keep-alive? ring-request) (assoc! \"Connection\" \"keep-alive\",))\n      (u/merge-transient! (:headers opts))\n      persistent!))"
   }
  ]
 }
]);
