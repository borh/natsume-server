(ns natsume-server.endpoint.webapp
  (:require [compojure.core :refer :all]))

(defn webapp-endpoint [config]
  (routes
   (GET "/" [] "Welcome to the client-side component of Natsume")))
