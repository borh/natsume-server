(ns natsume-server.server-spec
  (:use [speclj.core]
        [natsume-server.server]
        [ring.mock.request]
        [natsume-server.cabocha-wrapper :only (sentence->tree)]
        [natsume-server.core :only (string->paragraphs)])
  (:require [cheshire.core :as json]))

(defmacro handler-runner
  [method relative-url & opts]
  `(-> (request ~method (str "http://localhost:5011" ~relative-url)) ~@opts handler))

(describe
 "Route"

 (with mock-req {:status 200
                 :headers {"Content-Type" "application/json; charset=UTF-8"}})

 (it "/corpus/genres returns name-id map"
     (should= (assoc @mock-req
                :body "[{\"name\":\"検定教科書\",\"id\":1},{\"name\":\"国会会議録\",\"id\":2}]")
              (handler-runner :get "/corpus/genres")))

 (it "/corpus/genres > npv return genre name"
     (should= (assoc @mock-req :body "[\"korpus\"]")
              (handler-runner :get "/corpus/genres/korpus")))

 (it "/corpus/genres > npv return npv"
     (should= (assoc @mock-req :body "[\"korpus\",\"こと\",\"が\",\"ある\"]")
              (handler-runner :get "/corpus/genres/korpus/npv/こと/が/ある")))

 (it "/token/error returns register score of token"
     (should= (assoc @mock-req :body "[]")
              (handler-runner :get "/token/error" (query-string (json/encode {:orthBase "ルビ" :pos "名詞普通名詞" :positive 2 :negative 1})))))

 (it "/sentence/analyze analyzes sentence"
     (should= (assoc @mock-req :body (json/encode (sentence->tree "Hello\n")))
               (handler-runner :get "/sentence/analyze" (query-string "{\"text\":\"Hello\n\"}"))))

 (it "/paragraph/analyze analyzes paragraph"
     (should= (assoc @mock-req :body (json/encode (paragraph->tree "Hello\nWorld\n\nSecond.")))
               (handler-runner :get "/paragraph/analyze" (query-string "{\"text\":\"Hello\nWorld\n\nSecond.\"}"))))

 (it "/paragraph/split splits paragraphs into sentences"
     (should= (assoc @mock-req :body (json/encode ["Hello" "World"]))
              (handler-runner :get "/paragraph/split" (query-string "{\"text\":\"Hello\nWorld\n\"}"))))

 (it "/text/split splits text into paragraphs and sentences"
     (should= (assoc @mock-req :body (json/encode [["Hello" "World"] ["Second."]]))
              (handler-runner :get "/text/split" (query-string "{\"text\":\"Hello\nWorld\n\nSecond.\"}"))))

 (it "/text/analyze analyzes text into paragraphs and sentences and CaboCha trees"
     (should= (assoc @mock-req :body (json/encode (text-analyze-error "Hello\nWorld\n\nSecond." nil 1 2)))
              (handler-runner :get "/text/analyze" (query-string "{\"text\":\"Hello\nWorld\n\nSecond.\"}"))))

 )
