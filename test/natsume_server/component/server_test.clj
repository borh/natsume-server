(ns natsume-server.component.server-test
  (:require [clojure.test :refer :all]
            [natsume-server.component.server :refer :all]
            [clojure.java.io :as io]
            [ring.mock.request :as mock]))

(use-fixtures :once (fn [f] (mount.core/start) (f)))

(defn edn-body
  "Set the body of the request to a JSON structure. The supplied body value
  should be a map of parameters to be converted to JSON."
  [request body-value]
  (-> request
      (assoc :content-type "application/edn")
      (assoc-in [:headers "content-type"] "application/edn")
      (assoc :body (.getBytes (pr-str body-value)))))

(defn r
  ([request-type path]
   (r request-type path nil))
  ([request-type path body?]
   (let [req (mock/request request-type path)
         req (if body? (edn-body req body?) req)]
     (app req))))

(defn slurp-body [response]
  (if (= 200 (:status response))
    (slurp (:body response))))

(defn- slurp-webjars [path]
  (if-let [f (io/resource (str "META-INF/resources/webjars/" path))]
    (slurp f)))

(deftest url-test
  (is (= 301 (:status (r :get "/"))))
  (doseq [url ["/fulltext"]]
    (is (= 200 (:status (r :get url)))))
  (is (r :post "/api" [:sources/genre :tokens])))

(deftest api-test
  (is (r :get "/api")))

(deftest local-test
  (doseq [url ["public/app.css" "public/js/main.js"]]
    (is (= (slurp (io/resource url)) (slurp-body (r :get (str "/" url)))))))

(deftest webjar-test
  (doseq [url ["assets/bulma/css/bulma.css" "assets/font-awesome/css/all.min.css" "assets/balloon-css/balloon.min.css"]]
    (is (= (slurp-webjars url) (slurp-body (r :get url))))))