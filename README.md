# jimpil/flog
Logging that doesn't feel like flogging

## What

A very thin wrapper around [Log4j2](https://logging.apache.org/log4j/2.x/index.html),
optimised for logging Clojure maps (via [MapMessage](https://logging.apache.org/log4j/2.x/manual/messages.html)).

## Why
Log4j2 is probably the only logging framework with such a strong emphasis on structured-logging.
`clojure.tools.logging` does provide a layer of integration with it, but it has (traditionally) been geared
towards logging Strings. It is virtually impossible, to send a MapMessage to `log4j2` via `clojure.tools.logging`,
and have it be recognised as an actual MapMessage. That's because the bottom call to `.log` happens inside
`clojure.tools.logging` (i.e. you don't control it, and therefore you can't type-hint it). This library completely
sidesteps `clojure.tools.logging` - the only thing it uses from it is the `*logging-agent*`(in its async api).

## Where
FIXME: add clojars badge

## How
There are two identical api-variants (`sync` VS `async`) to choose from. The async variant uses the sync one,
but has it dispatched on `clojure.tools.logging/*logging-agent*`(making sure it doesn't lose the context along the way).

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

All level-specific calls can also take a Throwable as the first arg. However, following it, you must either provide a map,
a String, or an even number of args (which will become a map). You can also provide just the `Throwable`, in which case
the following map will be logged:

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

## Requirements
`flog` expects the following two JARs on the classpath (preferably version 2.14.1, or higher):
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
