EE.addWild("02", [
 {
  "id": "02-datastar-sse-reader",
  "engine": "Datastar",
  "repo": "starfederation/datastar",
  "sha": "1efcdc3cb336ec3e9139491604e770e0329657bc",
  "path": "library/src/plugins/actions/fetch.ts",
  "lang": "typescript",
  "title": "<code>fetch.ts</code>: lines into messages, data lines into arguments",
  "prompt": "Our <code>patch-elements</code> writes one <code>elements</code> data line per line of HTML. Find the two places that join those lines again, and say which one makes the order of <code>selector</code> and <code>mode</code> irrelevant.",
  "notes": "<p>The second segment is the middle stage of the reader (<code>getMessages</code>). Each complete line is split at its first colon, and one optional space after the colon is skipped (line 408). <code>data</code> values are joined with <code>\\n</code>. <code>id</code> and <code>retry</code> go to the retry logic as soon as they arrive, not at the end of the event. A blank line hands on the finished message and starts a new one.</p><p>The first segment turns a message into patch arguments. Only events whose name starts with <code>datastar</code> are used. Each data line is split at its first space into a key and a value. The values are grouped per key in a map, then each group is joined with <code>\\n</code>. So the eight <code>elements</code> lines of a multi-line patch come back as one HTML string, in order. <code>selector</code> and <code>mode</code> land in different map entries, so their order relative to each other is lost. That is why the golden files can disagree on that order and still all be correct.</p><p>A <code>retry:</code> line changes the reconnect interval for the request that receives it (the caller sets <code>baseRetryInterval = retryInterval = retry</code>). Our writer leaves out 1000 because 1000 is already the default.</p>",
  "segments": [
   {
    "start": 109,
    "end": 123,
    "code": "          onmessage_: (evt) => {\n            if (!evt.event_.startsWith('datastar')) return\n            const type = evt.event_\n            const argsRawLines: Record<string, string[]> = {}\n\n            for (const line of evt.data_.split('\\n')) {\n              const i = line.indexOf(' ')\n              const k = line.slice(0, i)\n              const v = line.slice(i + 1)\n              ;(argsRawLines[k] ||= []).push(v)\n            }\n\n            const argsRaw = Object.fromEntries(\n              Object.entries(argsRawLines).map(([k, v]) => [k, v.join('\\n')]),\n            )"
   },
   {
    "start": 403,
    "end": 431,
    "code": "    } else if (fieldLength > 0) {\n      // exclude comments and lines with no values\n      // line is of format \"<field>:<value>\" or \"<field>: <value>\"\n      // https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation\n      const field = decoder.decode(line.subarray(0, fieldLength))\n      const valueOffset = fieldLength + (line[fieldLength + 1] === 32 ? 2 : 1)\n      const value = decoder.decode(line.subarray(valueOffset))\n\n      switch (field) {\n        case 'data':\n          message.data_ = message.data_\n            ? `${message.data_}\\n${value}`\n            : value\n          break\n        case 'event':\n          message.event_ = value\n          break\n        case 'id':\n          onId((message.id_ = value))\n          break\n        case 'retry': {\n          const retry = +value\n          if (!Number.isNaN(retry)) {\n            // per spec, ignore non-integers\n            onRetry((message.retry_ = retry))\n          }\n          break\n        }\n      }"
   }
  ]
 },
 {
  "id": "02-go-sdk-send",
  "engine": "Datastar Go SDK",
  "repo": "starfederation/datastar-go",
  "sha": "60dc10ebdaad3207d71e4bd8c1f158e65bb4acb0",
  "path": "datastar/sse.go",
  "lang": "go",
  "title": "Go SDK <code>Send</code>: the same writer, with a lock and a flush",
  "prompt": "Our <code>send-event</code> leaves out <code>retry:</code> when it is 1000. What else does the Go SDK leave out that ours would write, and what does it write after the last data line?",
  "notes": "<p>The order is the same as ours: <code>event:</code>, then <code>id:</code> if set (lines 175&ndash;184, not shown), then <code>retry:</code>, then one <code>data:</code> line per item. Two rules differ. A retry of 0 or less is dropped (<code>Milliseconds() &gt; 0</code>), where ours would write <code>retry: 0</code>. And the event ends with <code>\"\\n\\n\"</code> after the last data line's own <code>\"\\n\"</code>, so each Go event is followed by two blank lines where ours has one. The second blank line makes the browser dispatch an empty message. <code>getMessages</code> passes it on, and Datastar's <code>onmessage_</code> drops it because its event name doesn't start with <code>datastar</code>.</p><p>The rest of <code>Send</code> is what a long-lived stream needs and our one-shot string doesn't. It returns an error if the request context is cancelled (line 144). It takes a mutex so that two goroutines can't interleave events. It builds each event in a pooled buffer. And it flushes after every event (<code>sse.rc.Flush()</code>, line 227) so the event leaves the server immediately. Our held-open streams hand http-kit's <code>send!</code> one whole event string per call.</p>",
  "segments": [
   {
    "start": 152,
    "end": 156,
    "code": "\tevt := serverSentEventData{\n\t\tType:          eventType,\n\t\tData:          dataLines,\n\t\tRetryDuration: DefaultSseRetryDuration,\n\t}"
   },
   {
    "start": 166,
    "end": 173,
    "code": "\t// write event type\n\tif err := errors.Join(\n\t\twriteJustError(buf, eventLinePrefix),\n\t\twriteJustError(buf, []byte(evt.Type)),\n\t\twriteJustError(buf, newLineBuf),\n\t); err != nil {\n\t\treturn fmt.Errorf(\"failed to write event type: %w\", err)\n\t}"
   },
   {
    "start": 186,
    "end": 213,
    "code": "\t// write retry if needed\n\tif evt.RetryDuration.Milliseconds() > 0 && evt.RetryDuration.Milliseconds() != DefaultSseRetryDuration.Milliseconds() {\n\t\tretry := int(evt.RetryDuration.Milliseconds())\n\t\tretryStr := strconv.Itoa(retry)\n\t\tif err := errors.Join(\n\t\t\twriteJustError(buf, retryLinePrefix),\n\t\t\twriteJustError(buf, []byte(retryStr)),\n\t\t\twriteJustError(buf, newLineBuf),\n\t\t); err != nil {\n\t\t\treturn fmt.Errorf(\"failed to write retry: %w\", err)\n\t\t}\n\t}\n\n\t// write data lines\n\tfor _, d := range evt.Data {\n\t\tif err := errors.Join(\n\t\t\twriteJustError(buf, dataLinePrefix),\n\t\t\twriteJustError(buf, []byte(d)),\n\t\t\twriteJustError(buf, newLineBuf),\n\t\t); err != nil {\n\t\t\treturn fmt.Errorf(\"failed to write data: %w\", err)\n\t\t}\n\t}\n\n\t// write double newlines to separate events\n\tif err := writeJustError(buf, doubleNewLineBuf); err != nil {\n\t\treturn fmt.Errorf(\"failed to write newline: %w\", err)\n\t}"
   }
  ]
 },
 {
  "id": "02-clojure-sdk-write-event",
  "engine": "Datastar Clojure SDK",
  "repo": "starfederation/datastar-clojure",
  "sha": "b08e6877293413412fe8a9dc979c08899687a4f4",
  "path": "libraries/sdk/src/main/starfederation/datastar/clojure/api/sse.clj",
  "lang": "clojure",
  "title": "<code>write-event!</code>: append to a buffer instead of returning a string",
  "prompt": "Our <code>send-event</code> returns a string. What does <code>write-event!</code> write to instead, and which predicate drops an empty id?",
  "notes": "<p>The shape is ours: event type, options, data lines, one terminating newline. <code>end-event</code> is a single <code>\"\\n\"</code>, so unlike the Go SDK there is exactly one blank line. The difference is the destination. <code>write-event!</code> appends to any <code>java.lang.Appendable</code>, so an adapter can write into a <code>StringBuilder</code>, a <code>Writer</code> or a compressing stream (the SDK's \"write profiles\") without building an intermediate string. Ours returns a string because one-shot responses concatenate events into a Ring body, and tests compare strings.</p><p>Options are cleaned before they get here (<code>rework-options</code>, lines 138&ndash;153). An empty-string id is dropped (<code>keep-event-id?</code>), and a retry that is 0, negative or the 1000 default is dropped (<code>keep-retry-duration?</code>). Ours drops only <code>nil</code> and 1000, so <code>{:id \"\"}</code> would write a bare <code>id: </code> line. Datastar treats an empty id as \"forget the last one\": it stops sending the <code>last-event-id</code> header on retries (<code>fetch.ts</code>, lines 659&ndash;666).</p>",
  "segments": [
   {
    "start": 104,
    "end": 127,
    "code": "(defn- append-data-lines! [buffer data-lines]\n  (doseq [l data-lines]\n    (append-line! buffer data-line-prefix l \"Failed to write data.\" :data-line)))\n\n;; -----------------------------------------------------------------------------\n;; Append end event\n(defn- append-end-event! [buffer]\n  (try\n    (append! buffer end-event)\n    (catch Exception e\n      (throw (ex-info \"Failed to write new lines.\"\n                      {}\n                      e)))))\n\n;; -----------------------------------------------------------------------------\n;; Public api\n(defn write-event!\n  \"Appends and event to an java.lang.Appendable buffer.\"\n  [appendable event-type data-lines opts]\n  (doto appendable\n    (append-event-type! event-type)\n    (append-opts! opts)\n    (append-data-lines! data-lines)\n    (append-end-event!)))"
   },
   {
    "start": 130,
    "end": 135,
    "code": "(def ^:private keep-event-id? u/not-empty-string?)\n\n(defn- keep-retry-duration? [d]\n  (and d\n       (> d 0)\n       (not= d consts/default-sse-retry-duration)))"
   }
  ]
 }
]);
