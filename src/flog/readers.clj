(ns flog.readers
  (:require [flog
             [appenders :as appenders]
             [loggers :as loggers]]))

(def edn-readers
  {'flog.appenders.StdOut            appenders/map->StdOut
   'logger/StdOut                    appenders/map->StdOut

   'flog.appenders.StdErr            appenders/map->StdErr
   'logger/StdErr                    appenders/map->StdErr

   'flog.appenders.PassThroughWriter appenders/map->PassThroughWriter
   'logger/PassThroughWriter         appenders/map->PassThroughWriter

   'flog.appenders.AtomOut           appenders/map->AtomOut
   'logger/AtomOut                   appenders/map->AtomOut

   'flog.appenders.HttpPost          appenders/map->HttpPost
   'logger/HttpPost                  appenders/map->HttpPost

   'flog.loggers.Branching           loggers/map->Branching
   'logger/Branching                 loggers/map->Branching

   'flog.loggers.Locking             loggers/map->Locking
   'logger/Locking                   loggers/map->Locking

   'flog.loggers.OnAgent             loggers/map->OnAgent
   'logger/OnAgent                   loggers/map->OnAgent
   'flog.loggers.OnCBAgent           loggers/map->OnCBAgent
   'logger/OnCBAgent                 loggers/map->OnCBAgent

   'flog.loggers.Batching            loggers/map->Batching
   'logger/Batching                  loggers/map->Batching

   ;; the following don't need fully qualified reader tags
   ;; because the loggers rely on lower level constructors
   'logger/BatchingFile              loggers/map->BatchingFile
   'logger/BatchingConsole           loggers/map->BatchingConsole
   'logger/Executing                 loggers/map->Executing
   'logger/LockingConsole            loggers/map->LockingConsole
   'logger/AsyncConsole              loggers/map->AsyncConsole
   'logger/LockingFile               loggers/map->LockingFile
   'logger/Mapping                   loggers/map->Mapping
   'logger/Filtering                 loggers/map->Filtering
   'logger/Removing                  loggers/map->Removing
   'logger/Partitioning              loggers/map->Partitioning
   'logger/FilteringLevels           loggers/map->FilteringLevels
   'logger/RemovingLevels            loggers/map->RemovingLevels
   'logger/FormattingEvent           loggers/map->FormattingEvent
   'logger/CLformattingEvent         loggers/map->CLformattingEvent

   'flog.loggers.RollingFileInterval loggers/map->RollingFileInterval
   'logger/RollingFileInterval       loggers/map->RollingFileInterval
   'flog.loggers.RollingFileSize     loggers/map->RollingFileSize
   'logger/RollingFileSize           loggers/map->RollingFileSize

   'flog.loggers.XLogger             loggers/map->XLogger
   'logger/XLogger                   loggers/map->XLogger
   }

  )
(def ^:dynamic *readers*
  edn-readers)

(defmacro with-readers
  "Merges the current value of *readers*
   with <rdrs>, and binds it back to *readers*
   before executing <body>."
  [rdrs & body]
  `(binding [*readers* (merge *readers* ~rdrs)]
     ~@body))
