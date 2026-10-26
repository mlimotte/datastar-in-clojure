# Datastar in Clojure

Learn to build hypermedia apps with [Datastar](https://data-star.dev/) (v1.0.4) and Clojure, without
an SDK. Seven parts build toward **Brew Board**, a coffee counter with a live barista board, a
server-rendered drop-down, and a thin native shell that replaces a React Native app.

Built on [learning-kit](https://github.com/dpsutton/learning-kit): interactive posts, a Clojure
lesson per part, an exercise track, recall cards, and excerpts from real-world source.

| Part | Lesson | Demo |
|---|---|---|
| 1 HTML over the wire | `clojure/src/lesson01/hypermedia.clj` | `clojure -M -m lesson01.hypermedia` |
| 2 The event stream | `clojure/src/lesson02/event_stream.clj` | `clojure -M -m lesson02.event-stream` |
| 3 Signals | `clojure/src/lesson03/signals.clj` | `clojure -M -m lesson03.signals` |
| 4 When to use a signal | `clojure/src/lesson04/where_state_lives.clj` | `clojure -M -m lesson04.where-state-lives` |
| 5 Shaping the backend | `clojure/src/lesson05/routes.clj` | `clojure -M -m lesson05.routes` |
| 6 One stream, many viewers | `clojure/src/lesson06/live.clj` | `clojure -M -m lesson06.live` |
| 7 Replacing the React Native app (optional) | `clojure/src/lesson07/native_shell.clj` | `clojure -M -m lesson07.native-shell` |
| 8 Field guide (optional, no exercises) | `clojure/src/lesson08/field_guide.clj` | `clojure -M -m lesson08.field-guide` |

Run the demos from `clojure/`. Each lesson also has `(start! 8080)` / `(stop!)` for the REPL.

**Exercises** live in `clojure/ex/exercises/lessonNN/`. REPL: `cd clojure && clj -A:ex-repl`, then
`(require 'exercises.lesson01.hypermedia-test :reload-all)` and
`(clojure.test/run-tests 'exercises.lesson01.hypermedia-test)`. Or from the project root: `lk test 01`.
In an IDE (e.g. IntelliJ/Cursive), open `clojure/deps.edn` as the project and start the REPL with
`-A:dev`, which puts both the exercise track (`ex/`) and the reference tests (`test/`) on the classpath.

**Capstone:** `cd clojure && clojure -M -m brewboard.main`, then open
http://localhost:8080/ (customer) and http://localhost:8080/board (barista) side by side.
Reference native shells are in `native/` (see `native/README.md` for what was checked).

```bash
lk serve        # build the site and open http://localhost:8000
lk exercises    # regenerate the exercise track after editing EXERCISE markers
lk test 03      # one part's exercise tests
lk ci           # everything CI checks
```

`DESIGN.md` is the contract the code and posts were built against, including what we learned
about Datastar along the way.
