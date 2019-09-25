(ns flog.http.authenticator
  (:gen-class ;; subclass of Authenticator exposing its protected fields
    :name flog.http.authenticator.AuthenticatorImpl
    :extends java.net.Authenticator
    :exposes-methods {getRequestingHost _host
                      getRequestingPort _port
                      getRequestingPrompt _prompt
                      getRequestingProtocol _protocol
                      getRequestingScheme _scheme
                      getRequestingSite _site
                      getRequestingURL _url
                      getRequestorType _type}))
