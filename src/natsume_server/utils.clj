(ns natsume-server.utils
  (:refer-clojure :exclude [partition-by map mapcat filter reduce take-while drop take remove flatten])
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.core.reducers :refer :all]
            [clojure.tools.reader.edn :as edn])
  (:import [org.apache.commons.compress.compressors.xz XZCompressorInputStream XZCompressorOutputStream]))

;; Begin reducers utils

(defn- do-curried
  [name doc meta args body]
  (let [cargs (vec (butlast args))]
    `(defn ~name ~doc ~meta
       (~cargs (fn [x#] (~name ~@cargs x#)))
       (~args ~@body))))

(defmacro ^:private defcurried
  "Builds another arity of the fn that returns a fn awaiting the last
param"
  [name doc meta args & body]
  (do-curried name doc meta args body))

(defprotocol PartitionBuffer
  (append [buf r v])
  (finish [buf]))

(deftype PartBuffer [last-result init fun tail]
  PartitionBuffer
  (append [this partition-result value]
    (if (= partition-result last-result)
      (PartBuffer. last-result init fun (conj tail value))
      (if (seq tail)
        (let [reduce-result (fun init tail)]
          (PartBuffer. partition-result reduce-result fun [value]))
        (PartBuffer. partition-result init fun [value]))))
  (finish [_]
    (fun init tail)))

(defcurried partition-by
  "Applies f to each value in coll, splitting it each time f returns
  a new value."
  {:added "1.5"}
  [fun coll]
  (reify
    clojure.core.protocols/CollReduce
    (coll-reduce [this f1]
      (clojure.core.protocols/coll-reduce this f1 (f1)))
    (coll-reduce [_ f1 init]
      (finish (clojure.core.protocols/coll-reduce
               coll
               (fn [accum v]
                 (append accum (fun v) v))
               (PartBuffer. (Object.) init f1 []))))))

;; End reducers utils

(defn strict-map [& args]
  (apply (comp doall clojure.core/map) args))

(defn strict-map-discard [& args]
  (apply (comp dorun clojure.core/map) args))

;; EDN writer and reader utils

(defn xz-line-seq
  "Utility function that turns XZ compressed text files into a line-seq."
  [fn]
  (-> fn io/file io/input-stream XZCompressorInputStream. io/reader line-seq))

(defn spit-edn-xz [filename data]
  (with-open [xz-out (-> filename
                         (string/replace ".txt" ".edn.xz")
                         io/file
                         io/output-stream
                         XZCompressorOutputStream.)]
    (spit xz-out (prn-str data))))

(defn slurp-edn-xz [filename]
  (with-open [xz-in (-> filename
                        (string/replace ".txt" ".edn.xz")
                        io/file
                        io/input-stream
                        XZCompressorInputStream.)]
    (edn/read-string (slurp xz-in))))
