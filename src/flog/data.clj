(ns flog.data
   (:require [flog.util :as util])
   (:import (org.apache.logging.log4j LogBuilder MarkerManager Marker)
            (org.apache.logging.log4j.message MapMessage)
            (java.util Map)
            (clojure.lang IPersistentMap)))

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

   Object
   (log*
     ([this ^LogBuilder builder]
      (.log builder this))
     ([this builder _]
      (log* this builder)))
   )
