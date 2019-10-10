(ns natsume-web.viz
  (:require [rid3.core :as rid3 :refer [rid3->]]))

;;

(def margin
  {:top    2
   :right  20
   :bottom 2
   :left   120})

(def transition-duration 100)

(defn get-width [ratom]
  200)

(defn get-height [ratom]
  (* 25 (count @ratom)))

(defn x-scale [data x]
  ((-> js/d3
       (.scaleLinear)
       (.domain #js [0 (apply max (map :frequency @data))])
       (.range #js [0 (get-width data)]))
   x))

;;

(defn main-container-did-mount [node ratom]
  (rid3-> node
          {:transform (str "translate("
                           (get margin :left)
                           ","
                           (get margin :top)
                           ")")}))

(defn svg-did-mount [node ratom]
  (rid3-> node
          {:width (+ (get-width ratom)
                     (get margin :left 0)
                     (get margin :right 0))
           :height (+ (get-height ratom)
                      (get margin :top 0)
                      (get margin :bottom 0))}))

(defn svg-did-update [node ratom]
  (rid3-> node
          (.transition)
          (.duration transition-duration)
          {:width (+ (get-width ratom)
                     (get margin :left 0)
                     (get margin :right 0))
           :height (+ (get-height ratom)
                      (get margin :top 0)
                      (get margin :bottom 0))}))

(defn bar-did-mount [node ratom]
  (let [width       (get-width ratom)
        height      (get-height ratom)
        data-n      (count @ratom)
        rect-height (/ height data-n)
        color (.scaleOrdinal js/d3 (.-schemeCategory10 js/d3))]
    (rid3-> node
            (.style "shape-rendering" "crispEdges")
            {:fill "black"
             :x (x-scale ratom 0)
             :y (fn [_ i]
                  (* i rect-height))
             :height (- rect-height 1)
             :opacity 0.2}
            (.transition)
            (.duration transition-duration)
            {:width (fn [d]
                      (x-scale ratom (aget d "frequency")))
             :opacity 1})))

(defn bar-did-update [node ratom]
  (let [width       (get-width ratom)
        height      (get-height ratom)
        data-n      (count @ratom)
        rect-height (/ height data-n)
        color (.scaleOrdinal js/d3 (.-schemeCategory10 js/d3))]
    (rid3-> node
            (.style "shape-rendering" "crispEdges")
            (.attr "fill" "black" #_(fn [d] (color (aget d "idx"))))
            (.attr "opacity" 0.8)
            (.attr "y" (fn [_ i]
                         (* i rect-height)))
            (.transition)
            (.duration transition-duration)
            (.attr "opacity" 1)
            (.attr "height" (- rect-height 1))
            (.attr "x" (x-scale ratom 0))
            (.attr "width" (fn [d]
                             (x-scale ratom (aget d "frequency")))))))

(defn bar-label-common [node ratom text-key text-color text-size]
  (let [width       (get-width ratom)
        height      (get-height ratom)
        data-n      (count @ratom)
        rect-height (/ height data-n)]
    (rid3-> node
            {:y (fn [_ i]
                  (+ (* i rect-height)
                     (/ rect-height 2)))
             :alignment-baseline "middle"
             :fill text-color
             :font-family "sans-serif"
             :font-size text-size}
            (.text (fn [d] (aget d text-key))))))

(defn bar-freq-label-did-mount [node ratom]
  (let [width (get-width ratom)]
    (-> node
        (bar-label-common ratom "frequency" "white" "16")
        (.transition)
        (.duration transition-duration)
        (.attr "x" (fn [d] (max 0
                                (- (x-scale ratom (aget d "frequency"))
                                   5
                                   (* 10 (count (str (aget d "frequency")))))))))))

(defn bar-freq-label-did-update [node ratom]
  (let [width (get-width ratom)]
    (-> node
        (bar-label-common ratom "frequency" "white" "16")
        (.transition)
        (.duration transition-duration)
        (.attr "x" (fn [d] (max 0
                                (- (x-scale ratom (aget d "frequency"))
                                   5
                                   (* 10 (count (str (aget d "frequency")))))))))))

(defn bar-label-did-mount [node ratom]
  (let [width (get-width ratom)]
    (-> node
        (bar-label-common ratom "pattern" "black" "14")
        (.transition)
        (.duration transition-duration)
        (.attr "x" (fn [d] (- (* 14 (count (aget d "pattern")))))))))

(defn bar-label-did-update [node ratom]
  (let [width (get-width ratom)]
    (-> node
        (bar-label-common ratom "pattern" "black" "14")
        (.transition)
        (.duration transition-duration)
        (.attr "x" (fn [d] (- (* 14 (count (aget d "pattern")))))))))

(defn barchart [ratom]
  [rid3/viz
   {:id             "barchart"
    :ratom          ratom
    :svg            {:did-mount  svg-did-mount
                     :did-update svg-did-update}
    :main-container {:did-mount main-container-did-mount}
    :pieces
    [{:kind  :container
      :class "bars"
      :children
      [{:kind       :elem-with-data
        :prepare-dataset (fn [a] (clj->js @a))
        :class      "bar"
        :tag        "rect"
        :did-mount  bar-did-mount
        :did-update bar-did-update}

       {:kind       :elem-with-data
        :prepare-dataset (fn [a] (clj->js @a))
        :class      "bar-freq-label"
        :tag        "text"
        :did-mount  bar-freq-label-did-mount
        :did-update bar-freq-label-did-update}

       {:kind       :elem-with-data
        :prepare-dataset (fn [a] (clj->js @a))
        :class      "bar-label"
        :tag        "text"
        :did-mount  bar-label-did-mount
        :did-update bar-label-did-update}]}]}])
