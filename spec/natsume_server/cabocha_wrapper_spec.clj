(ns natsume-server.cabocha-wrapper-spec
  (:use [speclj.core]
        [natsume-server.cabocha-wrapper]))

;; ## Test data

(describe
 "sentence->tree"

 (it "parse-checks"
     (let [test-string "今日はいいあしあちぇ"] ;; include UNK tokens (harder with UniDic)
       (should== [{:id 0, :link 2, :head 0, :tail 1, :prob 0.81487185, :tokens [{:orth "今日", :fType "*", :begin 0, :iType "*", :lemma "今日", :cForm "*", :pos1 "名詞", :pos2 "普通名詞", :pos3 "副詞可能", :pron "キョー", :ne "B-DATE", :pos4 "*", :orthBase "今日", :end 2, :cType "*", :goshu "和", :lForm "キョウ", :pronBase "キョー", :iForm "*", :fForm "*"} {:orth "は", :fType "*", :begin 2, :iType "*", :lemma "は", :cForm "*", :pos1 "助詞", :pos2 "係助詞", :pos3 "*", :pron "ワ", :ne "O", :pos4 "*", :orthBase "は", :end 3, :cType "*", :goshu "和", :lForm "ハ", :pronBase "ワ", :iForm "*", :fForm "*"}]} {:id 1, :link 2, :head 0, :tail 0, :prob 0.0, :tokens [{:orth "いい", :fType "*", :begin 3, :iType "*", :lemma "良い", :cForm "連体形-一般", :pos1 "形容詞", :pos2 "非自立可能", :pos3 "*", :pron "イー", :ne "O", :pos4 "*", :orthBase "いい", :end 5, :cType "形容詞", :goshu "和", :lForm "ヨイ", :pronBase "イー", :iForm "*", :fForm "*"}]} {:id 2, :link -1, :head 2, :tail 2, :prob 0.0, :tokens [{:orth "あし", :fType "*", :begin 5, :iType "*", :lemma "足", :cForm "*", :pos1 "名詞", :pos2 "普通名詞", :pos3 "一般", :pron "アシ", :ne "O", :pos4 "*", :orthBase "あし", :end 7, :cType "*", :goshu "和", :lForm "アシ", :pronBase "アシ", :iForm "*", :fForm "*"} {:orth "あ", :fType "*", :begin 7, :iType "*", :lemma "あー", :cForm "*", :pos1 "感動詞", :pos2 "フィラー", :pos3 "*", :pron "ア", :ne "O", :pos4 "*", :orthBase "あ", :end 8, :cType "*", :goshu "和", :lForm "アー", :pronBase "ア", :iForm "*", :fForm "*"} {:orth "ちぇ", :fType "*", :begin 8, :iType "*", :lemma "ちぇっ", :cForm "*", :pos1 "感動詞", :pos2 "一般", :pos3 "*", :pron "チェ", :ne "O", :pos4 "*", :orthBase "ちぇ", :end 10, :cType "*", :goshu "和", :lForm "チェッ", :pronBase "チェ", :iForm "*", :fForm "*"}]}]
                (with-new-parser (parse-sentence test-string)))))
 )
