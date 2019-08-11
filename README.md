# flog

A logging library that doesn't flog you! ;)

## Fundamentals
`flog` was heavily motivated/inspired by [this](https://juxt.pro/blog/posts/logging.html) article. As such, `flog` builds upon a single abstraction - the `ILogger` - pretty much exactly as described in the `Learn from the Java lesson` section of said article. 

```clj
(defprotocol ILogger
  (getLevel [this])
  (log [this event]
       [this writer event])) ;; optimisation arity
```

### logger VS appender
In `flog` loggers are hierarchical - in fact they form an acyclic graph. There is little to no difference between loggers and appenders - they both implement the same `ILogger` protocol, but by convention I like to refer to terminal/leaf loggers as `appenders`. These are the loggers that will do the actual writing. `flog` comes with 4 of appenders:

- **std-out**             (writes to \*out\*)
- **pass-through-writer** (writes to the given open writer) 
- **with-open-writer**    (opens/writes/closes a new writer)
- **on-atom**             (`conj`'es to the given atom)

These can be composed with other loggers to form more meaningful loggers. For instance, logging to std-out is not thread-safe - you probably want some locking  around it. Thankfully, there is a `with-locking` logger (or an `on-agent` one if locking is not how you roll).

Here are some examples: 

- **branching**     (delegates to a set of loggers)
- **with-locking**  (delegates to a logger after acquiring a lock)
- **batching**      (buffers events when the lock cannot be acquired and delegates to a logger with the full buffer after acquiring a lock)
- **batching-file** (the composition of `batching` and `pass-through-writer`)
- **on-agent**      (delegates to a logger via `send-off`)
- **executing**     (delegates to a logger via `.submitTask()`)
- **filtering-levels** (filters events matching the set of levels provided)
- **formatting-event** (adds a `:formatted` entry to each event - via `clojure.ore/format`)
- **cl-formatting-event** (adds a `:cl-formatted` entry to each event - via `clojure.pprint/cl-format`)  
- **rolling-file-size** (delegates to a logger after renaming the provided if it has reached/exceeded a specific size)

## Logging levels
All loggers can be associated with a `level`. However, doing that is discouraged as it gives the illusion that a composition of loggers can have a different level at each step. This is true ONLY for the **branching** logger, which wraps a list/set of loggers (as opposed to a single one), and thus allows the forming of graph(s). All other (high-level) loggers wrap a single logger, and therefore form chain(s) (as opposed to graphs). In chains of loggers, only the level of the start node matters. However, you can configure that level on any of the children nodes (including the leaf appender) and let it bubble up. I recommend either the first or the last node, depending on how you like read  the composition. `branching` loggers created manually MUST have an explicit level.

## Structured logs 
`flog`, at its core, produces events (maps), as opposed to Strings. It is up to the logger to transform/format the incoming event in whatever way it wants (see `loggers/formatting-event` for one such logger). Observe the following:

```clj
(binding [flog.core/*root* (locking-console "TRACE")]
 (tracef "Root logger initialised per profile:%s%s"
               (str \newline)
               {:A :B}))
=> ;; a map is logged
{:thread "nRepl-session-e40565ff-5a91-4554-b847-7e7626d10bc0", 
 :host "dimitris-hackintosh/127.0.1.1", 
 :issued-at "2019-08-11T16:22:43.365111", 
 :msg "Root logger initialised per profile:\n{:A :B}", 
 :level "TRACE", 
 :file "...", 
:line 21}
```
The above is obviously not great for console output, but that's easily fixable:

```clj
(binding [flog.core/*root*
          (->> (locking-console "TRACE")
               (mapping :formatted)
               (formatting-event
                 "%-30s %-25s [%-5s] %s"
                 (juxt :issued-at :thread :level :msg)))]
  (tracef "Root logger initialised per profile:%s%s"
          (str \newline)
          {:A :B}))
=> a String is logged
2019-08-11T18:38:37.858649     nRepl-session-b12bbcc9-0d01-44f3-b4f2-4eb68980e4fa [TRACE] Root logger initialised per profile:
{:A :B}
```

## Representation
There are two ways to build up a root logger (which is required before any logging can occur). It can be done programmatically, or by specification. The latter allows defining the spec externally (e.g. a file). 

 ### Programmatic
 Constructing the root logger programmatically, requires that you manually construct the top-level `branching` logger (out of your set of loggers).
 
```clj
;; a simple root-logger that delegates to two other loggers
(-> (loggers/branching "DEBUG" 
      #{(loggers/locking-console "DEBUG")
        (loggers/locking-file "WARN" (io/file "/tmp/review.log"))})
    (with-meta {:nss {"foo.bar.*" {:mdc {:service "barman"}
                                         :level "INFO"}}}))
```
### By specification
The same root-logger as in the previous section, can be described with the following EDN structure:

```clj
{:level "DEBUG"
 :loggers #{ #logger/LockingConsole{:level "DEBUG"} 
             #logger/LockingFile{:level "WARN" :file "/tmp/review.log"}}
 :nss {"foo.bar.*" {:mdc {:service "barman"}
                    :level "INFO"}}}}
``` 
#### Profiles

To complete the specification, the system needs to know when to use a particular root-logger. This is done via profiles (arbitrary keywords). 

```clj
{:dev  dev-root-logger
 :test test-root-logger
 :prod prod-root-logger}
```
Combining the above with the EDN structure in the previous section gives us a complete logging configuration file ready for use.

See `example.config` for a more concrete example. 

## Initialisation 
There are two cases here. If the root-logger (or its spec), is known at compile-time, use `init-with-root!` or `init-with-config!`. Otherwise, you have to use the no-arg arity of `init-with-config!` which will read two System properties - `flogging.profiles` (path to the file containing all the profile specs) and `flogging.profile` (which profile to choose). 

It should be noted here, that knowing your root logger (or its spec) at compile time provides significant benefits with respect to performance (more on this later). The trade-off is, of course, that you have to re-compile/re-distribute in order to make a change to it. 


## Integration (clojure.tools.logging)
NO integration with `clojure.tools.logging` is provided. It is not terribly hard to add it, but it goes against all the design principles of this library. In fact, half through the implementation I felt the flogging (pun intended), and so I stopped and scrapped everything!


## Transducer friendly
You can make an `ILogger` out of a transducer, using `loggers/xlogger` In fact, multiple higher-level loggers are written on top of it (e.g. `mapping`, `filtering`, `removing` etc).

## Compile-time elision
Applicable only to cases where the root logger (or its spec) is known at compile time. In such cases, there are two opportunities to elide calls that wouldn't fire at runtime anyway. 

The first one is obviously the root logger's level (e.g. there is no point emitting any `report`/`trace` log calls, if the root logger is configured at `DEBUG` level).

The second one is the namespace-levels, and the logic is similar (i.e. there is no point emitting any `report`/`trace`/`debug` log calls, if the namespace is configured at `INFO` level).

Even if not completely eliding a call, there is still value to having your root logger be a compile-time constant, as the the emitted code will be far leaner (less questions to ask at runtime). 

## Usage in a project
Again, there are two cases here depending on whether the root logger is known at compile-time. If it's not, then all you need is to call `(core/init-from-config!)` as the first call in your `-main`. 

If the logger is indeed known at compile time, then my recommendation is to create a brand new namespace in your project (e.g. `my.awesome.project.logging.clj`). In there, define your config, and call `(init-from-config! config)` as a top-level form. Go back to the namespace that `-main` is in, and require that newly created namespace before anything else. You can now compile your project. 


## License

Copyright © 2019 Dimitrios Piliouras

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
