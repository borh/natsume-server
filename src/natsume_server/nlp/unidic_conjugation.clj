;; ## Verb Conjugation
;;
;; ### Conjugation Mapping Tables
;; TODO: possible to move from compact to spelling-out-the-whole-pos-field tables?
(ns natsume-server.nlp.unidic-conjugation
  (:require [clojure.string :as string]))

(def godan-tr {"カ行"   "き"
               "ガ行"   "ぎ"
               "サ行"   "し"
               "タ行"   "ち"
               "ナ行"   "に"
               "バ行"   "び"
               "マ行"   "み"
               "ラ行"   "り"
               "ワア行" "い"})

(def godan-onbin-tr {"イ音便" "い"
                     "促音便" "っ"
                     "撥音便" "ば"})

(def godan-mizen-tr {"カ行"   "か"
                     "カ"     "か" ; TODO
                     "ガ行"   "が"
                     "サ行"   "さ"
                     "サ"     "さ"
                     "タ行"   "た"
                     "ナ行"   "な"
                     "バ行"   "ば"
                     "マ行"   "ま"
                     "ラ行"   "ら"
                     "ワア行" "わ"})

(def bungo-yodan-tr {"カ行" "き"
                     "ガ行" "ぎ"
                     "サ行" "し"
                     "タ行" "ち"
                     "ハ行" "ひ"
                     "バ行" "び"
                     "ラ行" "り"
                     "マ行" "み"})

(def bungo-shimo-nidan-tr {"カ行" "け"
                           "ガ行" "げ"
                           "サ行" "せ"
                           "ザ行" "ぜ"
                           "タ行" "て"
                           "ダ行" "で"
                           "ナ行" "ね"
                           "ハ行" "え"
                           "バ行" "べ"
                           "マ行" "め"
                           "ヤ行" "え"
                           "ラ行" "れ"
                           "ワ行" "え"})

(def bungo-kami-nidan-tr {"カ行" "き"
                          "ガ行" "ぎ"
                          "タ行" "ち"
                          "ダ行" "じ"
                          "ハ行" "ひ"
                          "バ行" "び"
                          "マ行" "み"
                          "ヤ行" "い"
                          "ラ行" "り"})

(defn conjugate
  "Conjugates verb lemmas from 終止形 to 連用形.
  TODO compare speed of `string/join` vs. `str`.
  TODO the bungo stuff should be thoroughly debugged."
  [{:keys [lemma c-type c-form]}]
  (let [[type-main type-sub] (string/split c-type #"-")
        [form-main form-sub] (string/split c-form #"-")
        lemma-stem (subs lemma 0 (dec (count lemma)))]
    ;;(println type-main type-sub form-main form-sub lemma lemma-stem)
    (cond
     (or (= lemma "する")
         (= lemma "来る"))
     (case form-main
       "未然形" ({"カ行変格" "こ"
                  "サ行変格" "さ"} form-sub)
       "仮定形" (str lemma-stem "れ")
       ({"する" "し"
         "来る" "き"} lemma))

     (= type-main "五段") (str lemma-stem (if-let [r (cond
                                                     (re-seq #"音便$" form-sub) (get godan-onbin-tr form-sub)
                                                     (= form-main "未然形")     (get godan-mizen-tr type-sub)
                                                     :else                      (get godan-tr type-sub))]
                                            r
                                            (do (println type-main type-sub form-main form-sub lemma lemma-stem))))
     (re-find #"([上下]一段|[カサ]行変格)" type-main) lemma-stem
     (= type-main "ザ行変格") (str (subs lemma 0 (dec (count lemma-stem))) "じ")
     (re-find #"文語" type-main) (case (subs type-main 2)
                                   "四段"     (str lemma-stem (get bungo-yodan-tr type-sub))
                                   "上一段"   lemma-stem
                                   "下二段"   (if (= type-sub "ア行") ; special case
                                                lemma
                                                (str lemma-stem
                                                     (get bungo-shimo-nidan-tr type-sub)))
                                   "上二段"   (str lemma-stem (get bungo-kami-nidan-tr type-sub))
                                   "サ行変格" (str lemma-stem "し")
                                   "ザ行変格" (str lemma-stem "じ")
                                   "ナ行変格" (str lemma-stem "ね")
                                   "カ行変格" lemma ; 「巡り来」など
                                   "ラ行変格" lemma
                                   lemma) ; TODO log these
     :else lemma))) ; TODO should print to log for debugging
