(ns natsume-server.config
  (:require [cfg.core :refer :all]))

(init)

(defopt :log-directory
  :default  "./log"
  :validate #(.isFile (clojure.java.io/file %))
  :help-string "Set logging directory"
  :aliases  ["l" "-log-directory"])

(defopt :verbose
  :default false
  :bool    true
  :help-string "Turn on verbose logging"
  :aliases ["v" "-verbose"])

(defopt :clean
  :default false
  :bool    true
  :help-string "Reset database on run (WARNING: will delete all data)"
  :aliases ["c" "-clean"])

(defopt :no-process
  :default  false
  :bool     true
  :help-string "Do not process corpus data (processes by default)"
  :aliases  ["-no-process"])

(defopt :search
  :default false
  :bool    true
  :help-string "Create search-optimized tables"
  :aliases ["-search"])

(defopts :sampling
  (defopt :ratio
    :default  0.0
    :validate #(and (<= % 1.0) (>= % 0.0))
    :parse    #(Double/parseDouble %)
    :help-string "Sample size (as percent of original population (0.0-1.0))"
    :aliases  ["-sampling-ratio"])
  (defopt :seed
    :default  2
    :validate integer?
    :help-string "Random number seed for sampling"
    :aliases  ["-sampling-seed"])
  (defopt :replace
    :default false
    :bool    true
    :help-string "Use replacement with sampling"
    :aliases ["-sampling-replace"])
  (defopt :hold-out
    :default false
    :bool    true
    :help-string "FIXME"
    :aliases ["-sampling-hold-out"]))

(defopts :models
  (defopts :n-gram
    (defopt :type
      :default  :token
      :parse    keyword
      :validate (fn [input] (#{:token :char} input))
      :help-string "N-Gram model type (:char or :token)"
      :aliases  ["-models-n-gram-type"])
    (defopt :n
      :default  3
      :parse    #(Integer/parseInt %)
      :validate #(and (integer? %) (pos? %))
      :help-string "N-Gram order (n)"
      :aliases  ["-models-n-gram-n"]))
  (defopt :mode
    :default  :noop
    :parse    keyword
    :validate (fn [input] (#{:noop :build :load} input))
    :help-string "Build, load (or do nothing -- default) models"
    :aliases  ["-models-mode"])
  (defopt :directory
    :default  "./data/models/"
    :validate #(.isFile (clojure.java.io/file %))
    :help-string "Set model persistence directory"
    :aliases  ["-models-directory"]))

(defopts :server
  (defopt :run
    :default false
    :bool    true
    :help-string "Run server"
    :aliases ["s" "-server"])
  (defopt :port
    :default  3000
    :parse    #(Integer/parseInt %)
    :validate #(and (integer? %) (pos? %))
    :help-string "Server port"
    :aliases  ["p" "-port"]))
