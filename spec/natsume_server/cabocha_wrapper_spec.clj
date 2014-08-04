(ns natsume-server.cabocha-wrapper-spec
  (:require [speclj.core :refer :all]
            [natsume-server.cabocha-wrapper :refer :all]))

;; ## Test data

(describe "sentence->tree"

    (it "parse-checks"
     (let [test-string "今日はいいあしあちぇ"] ;; include UNK tokens (harder with UniDic)
       (should= [{:id 0, :link 2, :head 0, :tail 1, :prob 0.81487185, :tokens [{:f-type "*", :orth "今日", :begin 0, :lemma "今日", :i-form "*", :pos-4 "*", :orth-base "今日", :pos-3 "副詞可能", :pos-2 "普通名詞", :pos-1 "名詞", :pron "キョー", :ne "B-DATE", :end 2, :pron-base "キョー", :c-type "*", :goshu "和", :c-form "*", :i-type "*", :l-form "キョウ", :f-form "*"} {:f-type "*", :orth "は", :begin 2, :lemma "は", :i-form "*", :pos-4 "*", :orth-base "は", :pos-3 "*", :pos-2 "係助詞", :pos-1 "助詞", :pron "ワ", :ne "O", :end 3, :pron-base "ワ", :c-type "*", :goshu "和", :c-form "*", :i-type "*", :l-form "ハ", :f-form "*"}]} {:id 1, :link 2, :head 0, :tail 0, :prob 0.0, :tokens [{:f-type "*", :orth "いい", :begin 3, :lemma "良い", :i-form "*", :pos-4 "*", :orth-base "いい", :pos-3 "*", :pos-2 "非自立可能", :pos-1 "形容詞", :pron "イー", :ne "O", :end 5, :pron-base "イー", :c-type "形容詞", :goshu "和", :c-form "連体形-一般", :i-type "*", :l-form "ヨイ", :f-form "*"}]} {:id 2, :link -1, :head 2, :tail 2, :prob 0.0, :tokens [{:f-type "*", :orth "あし", :begin 5, :lemma "足", :i-form "*", :pos-4 "*", :orth-base "あし", :pos-3 "一般", :pos-2 "普通名詞", :pos-1 "名詞", :pron "アシ", :ne "O", :end 7, :pron-base "アシ", :c-type "*", :goshu "和", :c-form "*", :i-type "*", :l-form "アシ", :f-form "*"} {:f-type "*", :orth "あ", :begin 7, :lemma "あー", :i-form "*", :pos-4 "*", :orth-base "あ", :pos-3 "*", :pos-2 "フィラー", :pos-1 "感動詞", :pron "ア", :ne "O", :end 8, :pron-base "ア", :c-type "*", :goshu "和", :c-form "*", :i-type "*", :l-form "アー", :f-form "*"} {:f-type "*", :orth "ちぇ", :begin 8, :lemma "ちぇっ", :i-form "*", :pos-4 "*", :orth-base "ちぇ", :pos-3 "*", :pos-2 "一般", :pos-1 "感動詞", :pron "チェ", :ne "O", :end 10, :pron-base "チェ", :c-type "*", :goshu "和", :c-form "*", :i-type "*", :l-form "チェッ", :f-form "*"}]}]
                 (parse-sentence-synchronized test-string))))
 )
