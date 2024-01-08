(ns flog.data
  (:require [flog.util :as util]
            [flog.builder :as builder])
  (:import (org.apache.logging.log4j LogBuilder MarkerManager Marker)
           (org.apache.logging.log4j.message MapMessage)
           (clojure.lang IPersistentMap)
           (org.apache.logging.log4j.util Supplier)))

(defn- error-keys
  [throwable]
  {:error/message (ex-message throwable)
   :error/data    (some-> (ex-data throwable) util/pr-map-fully)
   :error/cause   (ex-cause throwable)
   :error/class   (.getName (class throwable))})

 (defprotocol ILogData
   (log* [this builder]
         [this builder args]))

 (extend-protocol ILogData
   Supplier ;; true base-case (pass-through)
   (log* [this ^LogBuilder builder] (.log builder this))

   IPersistentMap ;; kind-of a base-case because it (potentially) sets a marker
   (log*
     ([this ^LogBuilder builder]
      (let [^Marker marker      (some-> (:log/marker this) (MarkerManager/getMarker))
            ^LogBuilder builder (cond-> builder (some? marker) (.withMarker marker))
            ^Supplier supplier  (reify org.apache.logging.log4j.util.Supplier
                                  (get [_]
                                    (let [^java.util.Map m (-> this
                                                               (dissoc :log/marker)
                                                               (update-keys util/name++))]
                                      (MapMessage. m))))]
        (log* supplier builder))) ;; delegate to Supplier impl
     ([this builder args]
      (-> this
          (assoc :log/args args)
          (log* builder)))) ;; delegate to Map impl

   String
   (log*
     ([this ^LogBuilder builder] (.log builder this)) ;; single String
     ([this builder args] ;; String followed by an even number of key-vals or a single Map
      (let [dispatch-arg (first args)
            arg-data (if (map? dispatch-arg)
                       dispatch-arg
                       (apply hash-map args))
            final-map (assoc arg-data :log/message this)]
        ;; delegate to Map impl
        (log* final-map builder))))

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
            dispatch-arg  (first args)
            final-map (if (even? (count args))
                        (if (string? dispatch-arg)
                          ;; Throwable followed by String followed by Map
                          (merge error-details (second args) {:log/message dispatch-arg})
                          ;;Throwable followed by even number of key-vals
                          (merge error-details (apply hash-map args)))
                        (if (string? dispatch-arg)
                          ;; Throwable followed by String followed by even number of key-vals
                          (merge error-details (apply hash-map (rest args)) {:log/message dispatch-arg})
                          ;; Throwable followed by Map
                          (merge error-details dispatch-arg)))]
        ;; delegate to Map impl
        (log* final-map builder))))

   Object
   (log*
     ([this ^LogBuilder builder]
      (.log builder this))
     ([this builder _]
      (log* this builder)))
   )
