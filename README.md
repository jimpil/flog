# jimpil/flog

## What

A very thin wrapper around [Log4j2](https://logging.apache.org/log4j/2.x/index.html),
optimised for logging Clojure maps (via [MapMessage](https://logging.apache.org/log4j/2.x/manual/messages.html)).

## Why
Log4j2 puts strong emphasis on structured-logging. `clojure.tools.logging` does provide a layer of integration with it, 
but it has (traditionally) been geared towards logging Strings. It is virtually impossible, to send a MapMessage to 
`log4j2` via `clojure.tools.logging`, and have it be recognised as an actual MapMessage. That's because the bottom call 
to `.log` happens inside `clojure.tools.logging` (i.e. you don't control it, and therefore you can't type-hint it). 
This library completely sidesteps `clojure.tools.logging` - the only thing it uses from it is the `*logging-agent*` 
(in its async api).

## Where

[![Clojars Project](https://clojars.org/com.github.jimpil/flog/latest-version.svg)](https://clojars.org/com.github.jimpil/flog)

## How
There are two identical api-variants (`sync` VS `async`) to choose from. The async variant uses the same function as sync one,
but dispatches it on `clojure.tools.logging/*logging-agent*`(making sure it doesn't lose the context along the way).

```clj
;; pick one
(require '[flog.api.sync  :as l]
         '[flog.api.async :as l])
```

### Map VS String
We can log plain String like so:
```clj
(l/info "I am a log-message") ;; will emit a `SimpleMessage`
```
or a map like so:
```clj
;; you are encouraged to use `:log/message` as the key for the main log-message 
(l/info {:log/message "I am a log-message" :foo 1 :bar 2}) ;; will emit a `MapMessage`
```
or a map more conveniently like so:
```clj
(l/info "I am a log-message" :foo 1 :bar 2) ;; will emit the same `MapMessage` as above
```

All level-specific calls can also take a Throwable as the first arg in which case the following map will be merged 
into the rest of the provided args:

```clj
{:error/message (ex-message throwable)
 :error/data    (ex-data throwable)
 :error/cause   (ex-cause throwable)
 :error/class   (class throwable)}
```

You can find more examples in `test/flog/demo.clj`.

## Location info
As `Log4j2` puts it:
> Generating location information is an expensive operation and may impact performance. Use with caution.

This is particularly true when you leave it up to the layout (as it has to walk the stack).
If you do want location-info in your logs, it's much better to provide it at the `LogBuilder` level,
as explained [here](https://logging.apache.org/log4j/2.x/manual/logbuilder.html).

By default, `flog` will use a builder **w/o** location-info. However, you have the option of overriding that
(**at compile time**) via the `flog.builder/include-location-info?` system property.

FYI, the usefulness of location-info in Clojure programs is somewhat limited, when compared to Java ones  
(especially if going via `clojure.tools.logging`).
The `method` field will always be `.invoke`, and the `file`/`line` fields may not even be your own code
(i.e. `clojure.tools.logging`). Moreover, if dispatching on an agent (like `flog` does), the `thread` field
will always be some sort of `clojure-agent-send-off-pool-N`. With `flog`, you should at least expect correct
`file`/`line` info.

## ThreadContext (ex MDC/NDC)
Care has been taken to carry the `ThreadContext`, and any thread-local bindings when logging asynchronously
(see `flog.context/inherit-fn`).

## Configuration
The standard `Log4j2` rules apply. See [here](https://logging.apache.org/log4j/2.x/manual/configuration.html).
`flog` comes with a very basic `log4j2.xml` (the last choice in the aforementioned rules),
which is nothing more than a copy of the `DefaultConfiguration`, which includes printing the MDC.

## Macro expansions

### Synchronous

```clj
(walk/macroexpand-all '(sync.log/debug "Hi there" :a 1 :b 2))
=>
(flog.data/log* "Hi there"
 (.
  (.
   (flog.builder/ns-logger #object[clojure.lang.Namespace 0x49575f89 "flog.demo"])
   atLevel
   org.apache.logging.log4j.Level/DEBUG)
  withLocation)
 [:a 1 :b 2])
```
### Asynchronous

```clj
(walk/macroexpand-all '(async.log/debug "Hi there" :a 1 :b 2))
=>
(do
 (clojure.core/send-off
  clojure.tools.logging/*logging-agent*
  (let*
   [kvs__192__auto__
    (. org.apache.logging.log4j.ThreadContext getImmutableContext)
    vs__193__auto__
    (. (. org.apache.logging.log4j.ThreadContext getImmutableStack) asList)]
   (fn*
    ([___194__auto__]
     (let*
      [___181__auto__ (. org.apache.logging.log4j.CloseableThreadContext putAll kvs__192__auto__)]
      (try
       (do
        (let*
         [___175__auto__ (. org.apache.logging.log4j.CloseableThreadContext pushAll vs__193__auto__)]
         (try
          (do
           ;; synchronous call omitted as it can be seen above
          (finally (. ___175__auto__ clojure.core/close)))))
       (finally (. ___181__auto__ clojure.core/close))))))))
 nil)
```

## Async options
If you want async-logging you have 3 options:

1. Use `flog.api.async`, which sends off on an agent - (easy)
2. Use an async api of your own which submits to some ExecutorService that you control - (trivial) 
3. Let log4j do the [work](https://logging.apache.org/log4j/2.x/manual/async.html) - (requires extra config/deps)

## Requirements
`flog` expects the following two JARs on the classpath (preferably version 2.17.2, or higher):
- org.apache.logging.log4j/log4j-core
- org.apache.logging.log4j/log4j-api

## License

Copyright © 2021 Dimitrios Piliouras

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
