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
            ^Map this           (util/map-keys util/name++ (dissoc this :log/marker))]
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
     ([this builder args] ;; String followed by an even number of key-vals
      (-> {:log/message this} ;; delegate to Map impl
          (merge (apply hash-map args))
          (log* builder))))

   Throwable
   (log*
     ([this ^LogBuilder builder] ;; single Throwable
      (->> (builder/with-throwable builder this)
           (log* (error-keys this)))) ;; delegate to Map impl
     ;; Throwable followed by an even number of args (key-vals),
     ;; or an odd number of args (String followed by key-vals)
     ([this builder args]
      (let [arg-count (count args)
            first-arg (first args)
            arg-data (if (even? arg-count)
                       (if (string? first-arg)
                         ;; assuming single map after the string
                         (assoc (second args) :log/message first-arg)
                         (apply hash-map args))
                       (if (string? first-arg)
                         ;; assuming even number of key-vals after the string
                         (-> (apply hash-map (rest args))
                             (assoc :log/message first-arg))
                         ;; assuming single map
                         first-arg))]
        (->> (builder/with-throwable builder this)
             ;; delegate to Map impl
             (log* (merge (error-keys this) arg-data))))))

   Object
   (log*
     ([this ^LogBuilder builder]
      (.log builder this))
     ([this builder _]
      (log* this builder)))
   )
