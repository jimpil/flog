# flog

A Clojure library designed to ... well, that part is up to you.

## Fundamentals
`flog` was heavily motivated/inspired by [this](https://juxt.pro/blog/posts/logging.html) article. As such, `flog` builds upon a single abstraction - the `ILogger` - pretty much exactly as described in the `Learn from the Java lesson` section of said article. 

```clj
(defprotocol ILogger
  (getLevel [this])
  (log [this event]
       [this writer event])) ;; optimisation arity
```

### logger VS appender


In `flog` loggers are hierarchical - in fact they form a graph. There is little to no difference between loggers and appenders - they both implement the `ILogger` protocol, but by convention I like to refer to terminal/leaf loggers as `appenders`. These are the loggers that will do the actual writing. `flog` comes with 4 of appenders:

- **std-out**             (writes to \*out\*)
- **pass-through-writer** (writes to the given open writer) 
- **with-open-writer**    (opens/writes/closes a new writer)
- **on-atom**             (conj'es to the given atom)

These can be composed with other loggers to form more meaningful loggers. For instance, loggingn to std-out is not thread-safe - you probably want some locking  around it. Thankfully, there is a `with-locking` logger (or an `on-agent` one if locking is not how you roll).

## Logging levels
All loggers can be associated with a `level`. However, doing that is discouraged as it gives the illusion that a chain of loggers can have a different level at each step. This is true ONLY for the **branching** logger, which wraps a list/set of loggers (as opposed to a single one), and thus allows the forming of graph(s). All other (high-level) loggers wrap a single logger, and therefore form chain(s) (as opposed to graphs). In chains of loggers, only the level of the start node matters. However, you can configure that level on any of the children nodes (including the leaf appender) and let it bubble up. I recommend either the first or the last node, depending on how you read it the composition. `branching` loggers created manually MUST have an explicit level.

## Functional roots
It is entirely possible to use `flog` fully functionally, passing logger(s) around to all logging calls. If that's entirely what you intend to do, then there is no need to understand how to statically initialise a global root logger from an external configuration. Simply use the macros in `flog.core` passing a `ILogger` as the first arg.

If you set up your project logging this way, it can only be configured programmatically, which at first may seem like a shortcoming. What you get in return though is compile-time ellision of logs that wouldn't fire at runtime (assuming that the root logger can be resolved at compile-time). That's pretty neat, and credits should go to `timbre` for exploring this first. 

## Static-initialisation \(global logger\)
It is often the case that the team which will decide how logging should behave \(especially on production\), is not the development team. In such cases, an external configuration file is provided, usually via a system property. `flog` supports such a file, read as EDN (should contain a single map). That map represents an implicit `branching` logger, and as such should contain a `:loggers` vector/set. 

## Integration (clojure.tools.logging)


## Transducer friendly
You can make an `ILogger` out of a transducer, using `loggers/xlogger` In fact, multiple higher-level loggers are written on top of it (e.g. `mapping`, `filtering`, `removing` etc).

## Usage
We are going to construct the following logging graph in two ways:


### Programmatic

### Configuration-based

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
