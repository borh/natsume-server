(ns natsume-server.utils.naming
  (:require [clojure.string :as string]
            [clojure.walk :as walk]
            [camel-snake-kebab :as csk]))

;; # Naming Conversion Utility Functions
;;
;; Converts between idiomatic Clojure dash naming and SQL underscore/JSON camel-case.
;;
;; ## Clojure <-> JSON

(defn dashes->lower-camel
  "Converts Clojure kebab-case keyword to JSON camel-case string."
  [s]
  (->> s
       name
       csk/->camelCase))

(defn camel->lower-dash
  "Converts JSON camel-case string to Clojure kebab-case keyword."
  [s]
  (->> s
       keyword
       csk/->kebab-case))

;; ## Clojure <-> SQL

(defn dashes->underscores
  "Converts Clojure kebab-case keywords to SQL snake-case strings."
  [s]
  (-> s
      name
      csk/->snake_case))

(defn underscores->dashes
  "Converts SQL snake-case strings to Clojure kebab-case keywords."
  [s]
  (-> s
      keyword
      csk/->kebab-case))
