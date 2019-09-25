(ns flog.http.client
  (:require [flog.util :as ut])
  (:import (java.net.http HttpClient
                          HttpRequest
                          HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers
                          HttpClient$Version
                          HttpClient$Redirect
                          HttpRequest$Builder)
           (java.net ProxySelector
                     InetSocketAddress
                     URI
                     PasswordAuthentication
                     Authenticator$RequestorType
                     Authenticator)
           (java.nio.charset Charset)
           (java.time Duration)
           (flog.http.authenticator AuthenticatorImpl)))

(def ^:private versions
  {:http2   HttpClient$Version/HTTP_2
   :http1.1 HttpClient$Version/HTTP_1_1})

(def ^:private redirect-policies
  {:normal HttpClient$Redirect/NORMAL
   :always HttpClient$Redirect/ALWAYS
   :never  HttpClient$Redirect/NEVER})

(defn- authenticator-options
  [^AuthenticatorImpl this]
  {:host           (._host this)
   :port           (._port this)
   :prompt         (._prompt this)
   :protocol       (._protocol this)
   :scheme         (._scheme this)
   :site           (._site this)
   :url            (._url this)
   :requestor-type (.name ^Authenticator$RequestorType (._type this))})

(defn- authenticator
  "Given a function <f>, turns it into a
   java.net.Authenticator object. The function
   should accept one argument (a map with all the
   available <authenticator-options>), and it
   should return a tuple of `[username password]`."
  [f]
  ;; proxy to our own Impl which exposes
  ;; all the relevant protected fields
  (proxy [AuthenticatorImpl] []
    (getPasswordAuthentication []
      (let [arg-map (authenticator-options this)
            [username password] (f arg-map)]
        (PasswordAuthentication. username (.toCharArray password))))))

(defn standard-client
  "Returns an immutable HttpClient object
   to be used as the first argument to `send!`."
  ^HttpClient [opts]
  (let [{:keys [ssl-context
                ssl-params
                thread-pool ;; [:fixed 2] or [:cached] etc etc
                redirect-policy
                connect-timeout
                version
                priority
                proxy
                credentials] ;; a fn of 1 arg returning [username password]
         :or   {;priority 1 ;; 1 - 256
                version :http2
                redirect-policy :never}} opts]
    (cond-> (HttpClient/newBuilder)
            ssl-context (.sslContext ssl-context)
            ssl-params (.sslParameters ssl-params)
            connect-timeout (.connectTimeout (Duration/ofSeconds connect-timeout))
            thread-pool (.executor (apply ut/thread-pool thread-pool))
            version (.version (versions version))
            redirect-policy (.followRedirects (redirect-policies redirect-policy))
            proxy (.proxy (ProxySelector/of
                            (InetSocketAddress. ^String (:host proxy)
                                                ^Long (:port proxy))))
            authenticator (.authenticator (authenticator credentials))
            priority (.priority priority)
            true .build)))

(defonce string-handler
  (HttpResponse$BodyHandlers/ofString
    (Charset/defaultCharset)))

(defonce edn-content-type
  (into-array ["Content-Type" "application/edn"]))

(defonce json-content-type
  (into-array ["Content-Type" "application/json"]))

(defonce success?
  #{200 201 202 204 205 206})

(defn build-req
  ^HttpRequest [^URI uri json? timeout-seconds]
  (-> (HttpRequest/newBuilder uri)
      (.headers (if json? json-content-type edn-content-type))
      (.timeout (Duration/ofSeconds timeout-seconds))))

(defn post!
  ""
  ^String [^HttpClient client req-builder payload]
  (let [req (-> req-builder .copy
                (.POST (HttpRequest$BodyPublishers/ofString
                         (cond-> payload
                                 (not (string? payload))
                                 pr-str)))
                .build)]
    (let [resp (.send client req string-handler)
          resp-status (.statusCode resp)
          headers (.headers resp)
          protocol (-> resp .version .name)
          ret {:headers headers
               :protocol protocol
               :status resp-status}]
      (if (success? resp-status)
        (assoc ret :body (.body resp))
        (throw
          (ex-info (str "HTTP request failed with status-code " resp-status)
                   (assoc ret :body payload)))))))
