(ns flog.data
  (:require [flog.util :as util]
            [flog.builder :as builder])
   (:import (org.apache.logging.log4j LogBuilder MarkerManager Marker)
            (org.apache.logging.log4j.message MapMessage)
            (java.util Map)
            (clojure.lang IPersistentMap)))

(defn- error-keys
  [throwable]
  {:error/message (ex-message throwable)
   :error/data    (ex-data throwable)
   :error/cause   (ex-cause throwable)
   :error/class   (class throwable)})

 (defprotocol ILogData
   (log* [this builder]
         [this builder args]))

 (extend-protocol ILogData
   IPersistentMap
   (log*
     ([this ^LogBuilder builder]
      (let [^Marker marker      (some-> (:log/marker this) (MarkerManager/getMarker))
            ^LogBuilder builder (cond-> builder (some? marker) (.withMarker marker))
            ^Map this           (update-keys (dissoc this :log/marker) util/name++)]
        (->> (MapMessage. this)
             (.log builder))))
     ([this builder args]
      (-> this
          (assoc :log/args args)
          (log* builder))))

   String
   (log*
     ([this ^LogBuilder builder] ;; single String
      (.log builder this))
     ([this builder args] ;; String followed by an even number of key-vals or a single Map
      (let [dispatch-arg (first args)
            arg-data (if (map? dispatch-arg)
                       dispatch-arg
                       (apply hash-map args))]
        (-> arg-data
            (assoc :log/message this)
            (log* builder))))) ;; delegate to Map impl

   Throwable
   (log*
     ([this ^LogBuilder builder] ;; single Throwable
      (->> (builder/with-throwable builder this)
           (log* (error-keys this)))) ;; delegate to Map impl
     ;; Throwable followed by an even number of args (key-vals),
     ;; or an odd number of args (String followed by key-vals)
     ([this builder args]
      (let [builder       (builder/with-throwable builder this)
            error-details (error-keys this)
            dispatch-arg  (first args)]
        (if (even? (count args))
          (if (string? dispatch-arg)
            ;; Throwable followed by String followed by Map
            (log* dispatch-arg builder [(merge error-details (second args))])
            ;;Throwable followed by even number of key-vals
            (log* (merge error-details (apply hash-map args)) builder))
          (if (string? dispatch-arg)
            ;; Throwable followed by String followed by even number of key-vals
            (->> (rest args)
                 (concat (flatten (seq error-details)))
                 (log* dispatch-arg builder))
            ;; Throwable followed by Map
            (log* (merge error-details dispatch-arg) builder))))))

   Object
   (log*
     ([this ^LogBuilder builder]
      (.log builder this))
     ([this builder _]
      (log* this builder)))
   )
