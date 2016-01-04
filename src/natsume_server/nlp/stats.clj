(ns natsume-server.nlp.stats
  (:require [plumbing.core :refer [defnk for-map]]
            [plumbing.graph :as graph]
            [schema.core :as s]))

;; # Statistical Measures and Utility Functions

;; ## Utility functions

(defn log2 [n]
  (/ (Math/log n) (Math/log 2)))

(defn square [n]
  (*' n n))

(comment ;; Deemed too conservative in recent studies.
 (defn yates-continuity-correction [o e]
   (if (> o e)
     (- o 0.5)
     (+ o 0.5))))

(comment
  e = (Row total * Column total) / Grand total for each cell)

;; ## Contingency Table
;;
;; This is the contingency table for 2-gram collocations:
;;
;; +----+------+------+------+
;; |    | a    | !a   | RS   |
;; +----+------+------+------+
;; | b  | f-ii | f-oi | f-xi |
;; +----+------+------+------+
;; | !b | f-io | f-oo | f-xo |
;; +----+------+------+------+
;; | CS | f-ix | f-ox | f-xx |
;; +----+------+------+------+
;;
;; Where RS = row sum, CS = column sum.
;; f-xx is the total number of collocations (of some type).

(defn expand-contingency-table
  "Expands the contingency table to all cells, including column and row sums.
  At the least, f-ii and either f-io + f-oi + f-oo or f-ix + f-xi + f-xx must be given."
  [{:keys [f-ii f-io f-oi f-oo
           f-ix f-xi f-ox f-xo
           f-xx]}]
  (let [f-io (or f-io (- f-ix f-ii))
        f-oi (or f-oi (- f-xi f-ii))
        f-ix (or f-ix (+ f-ii f-io))
        f-xi (or f-xi (+ f-ii f-oi))
        f-xx (or f-xx (+ f-ii f-io f-io f-oo))
        f-oo (or f-oo (- f-xx f-ii f-io f-oi))]
    {:f-ii f-ii
     :f-io f-io
     :f-oi f-oi
     :f-oo f-oo
     :f-xx f-xx
     :f-ix f-ix
     :f-xi f-xi
     :f-xo (or f-xo (+ f-oo f-io))
     :f-ox (or f-ox (+ f-oo f-oi))}))

;; TODO things to think about: do we need to check for 0 frequencies, as these functions should only be called after we know a word exists in our DB?
;; TODO check for Integer overflows (make sure we promote to bignums when necessary)
;; TODO if we are going to be batch updating these offline, then should we tweak the functions as such? (or would a simple wrapper do?)
;; TODO check http://www.collocations.de/AM/
;; TODO http://linglit194.linglit.tu-darmstadt.de/linguisticsweb/bin/view/LinguisticsWeb/CollocationAnalysis
;; TODO more measures: https://github.com/MLCL/Byblo/tree/master/src/main/java/uk/ac/susx/mlcl/byblo/measures

;; TODO as we want to have scores per genre, they should be generated as such; the question is how to 'add' them together for higher genre levels?
;; TODO use frequency trees as basis for calculating these measures!! everything should be based on this!
;; TODO Genre sketch differences! This is closely related to register misuse detection and register classification.
;;      Find collocations that are most salient with one genre but not another and vice versa (ignoring the common part?)
;; TODO residual analysis on chi-sq variables to find out if they are significantly deviant (+/-) from normal distribution when doing chi square test
;; TODO some measures provide p values, it would be good if we could convey which results are 'significant'

;; ## Bigram Association Measures
;;
;; These measures can be used for trigrams which contain a grammatical relation.

;; ### Display Frequency

(defnk display-frequency
  "Human-interpretable frequency."
  [f-ii]
  (Math/log (+ f-ii 0.5)))

;; ### LogDice
;;
;; LogDice is the only measure that takes the grammatical relation into account.

(defn log-dice
  "logDice for 3-gram collocations (two words and one grammatical relation), where higher scores correspond to stronger collocations.

  Formula: 14 + log2((2 · ||w1, R, w2||) / (||w1, R, *|| + ||*, *, w2||))
  FIXME: should frequencies be normalized? Should we take into account the search order (i.e. if we specify only w1 or only w2?)
  Source: http://trac.sketchengine.co.uk/raw-attachment/wiki/SkE/DocsIndex/ske-stat.pdf"
  [coll w1-or-w2]
  (let [w1-by-R (for-map [[k v] (group-by w1-or-w2 coll)]
                         k (for-map [[k1 v1] (group-by :string-2 v)]
                                    k1 (reduce + (map :count v1))))]
    (map (fn [{:keys [count f-xi string-2] :as m}]
           (assoc m :log-dice
                  (+ 14
                     (log2 (/ (* 2
                                 count)
                              (+ (get-in w1-by-R [(w1-or-w2 m) string-2])
                                 f-xi))))))
         coll)))

;; ### T-Score

(defnk t [f-ii f-ix f-xi f-xx]
  (if (zero? f-ii)
    0
    (Math/abs ; Only interested in the t-score magnitude.
     (/ (- f-ii
           (/ (* f-ix f-xi)
              f-xx))
        (Math/sqrt f-ii)))))

;; ### Z-Score

(defnk z-score [])

;; ### Chi-Squared

(defnk chi-sq
  "Computes Pearson's Chi-Squared test (without Yate's continuity correction).
  Uses the auto-promoting multiplication function *' to prevent overflows."
  [f-ii f-oo f-io f-oi f-xx #_f-ox #_f-xo #_f-xi #_f-ix]
  (if (zero? f-ii)
    0
    (double
     (/ (*' f-xx
            (square (- (* f-ii f-oo)
                       (* f-io f-oi))))
        (*' (+ f-ii f-io)
            (+ f-ii f-oi)
            (+ f-io f-oo)
            (+ f-oi f-oo))))))

;; ### Fisher's Exact Test

(defnk fischer
  [])

;; ### Mi

(defnk mi
  "Computes the Mutual Information score for the two words a and b.
  Source: Church & Hanks. (1990). Word Association Norms, Mutual Information, and Lexicography. Computational Linguistics, 16(1), 22-29."
  [f-ii f-ix f-xi f-xx]
  (if (zero? f-ii)
    0
    (log2 (/ (* f-ii f-xx)
             (* f-ix f-xi)))))

;; ### MI^3-Score

(defnk mi-3
  "A heuristic association measure.
  Source: Oakes, M.P. (1998). Statistics for corpus linguistics. Edinburgh textbooks in empirical linguistics. Edinburgh University Press."
  [f-ii f-ix f-xi f-xx]
  (if (zero? f-ii)
    0
    (log2 (/ (* f-ii f-ii f-ii f-xx)
             (* f-ix f-xi)))))

;; ### Log-Likelihood

(defnk llr
  "Computes the llr of the collocation.
  This is a measure of the significance of association.

  Source: Dunning, Accurate Methods for the Statistics of Surprise and Coincidence, Computational Linguistics 19:1 1993."
  [f-ii #_f-io #_f-oi f-xx f-xi f-ix]
  #_(let [t (+ f-xx f-ii (- f-ix) (- f-xi))]
    (* 2
       (+ (* f-ii (Math/log (/ (* f-ii f-xx)
                               (* (+ f-ii f-io)
                                  (+ f-ii f-oi)))))
          (* f-io (Math/log (/ (* f-io f-xx)
                               (* (+ f-ii f-io)
                                  (+ f-io t)))))
          (* f-oi (Math/log (/ (* f-oi f-xx)
                               (* (+ f-ii f-oi)
                                  (+ f-oi t)))))
          (* t    (Math/log (/ (* t f-xx)
                               (* (+ f-io t)
                                  (+ f-oi t))))))))
  ;; Below is faster according to criterium bench.
  (if (zero? f-ii)
    0
    (letfn [(xlx [freq] (if-not (pos? freq) 0 (* freq (Math/log freq))))]
      (* 2
         (+ (xlx f-ii)
            (xlx (- f-ix f-ii))
            (xlx (- f-xi f-ii))
            (xlx f-xx)
            (xlx (+ f-xx f-ii (- f-ix) (- f-xi)))
            (- (xlx f-ix))
            (- (xlx f-xi))
            (- (xlx (- f-xx f-ix)))
            (- (xlx (- f-xx f-xi))))))))

;; ### Minimum Sensitivity

(defnk ms
  "Pedersen, Dependent Bigram Identification, in Proc. Fifteenth National Conference on Artificial Intelligence, 1998"
  [f-ii f-ix f-xi]
  (min (/ f-ii f-xi) (/ f-ii f-ix)))

;; ### MI-log-prod

(defnk mi-log-prod
  "Kilgariff, Rychly, Smrz, Tugwell, “The Sketch Engine” Proc. Euralex 2004."
  [f-ii f-ix f-xi f-xx]
  (* (mi {:f-ii f-ii :f-ix f-ix :f-xi f-xi :f-xx f-xx}) (Math/log10 (inc f-ii))))

;; ### Dice

(defnk dice
  ""
  [f-ii f-ix f-xi]
  (if (zero? f-ii)
    0
    (/ (* 2 f-ii)
       (+ f-ix f-xi))))

;; ### Relative Frequency

(defnk rf ; normalized-frequency?
  ""
  [f-ii f-ix]
  (* 100 (/ f-ii f-ix)))

;; ### TF-IDF

;; FIXME look at other weighting schemes: make weight type a parameter
;; TODO read "Generalized Inverse Document Frequency" by Donald Metzler (hierarchical Bayesian model) -- see if we can integrate media labels into this model!
(defn weighted-frequency
  "Sublinear term frequency scaling.
  See Manning, Raghavan, & Schutze p. 116."
  [tf]
  (if (zero? tf)
    0.0
    (inc (Math/log tf))))

(defn idf [df N]
  (Math/log (/ N df)))

;; TODO 2+ grams (i.e. cw-idf)
(defnk logarithm-tf-idf
  "Schutze&Manning p. 543"
  [f-ii f-xx f-df]
  (if (zero? f-ii)
    0.0
    (* (+ 1.0 (Math/log f-ii))
       (idf f-df f-xx))))

;; https://gist.github.com/ithayer/1050607
(defnk tf-idf-
  "http://trimc-nlp.blogspot.jp/2013/04/tfidf-with-google-n-grams-and-pos-tags.html"
  [f-ii f-xx f-df]
  (if (zero? f-ii)
    0.0
    (* (inc (Math/log f-ii))
       (Math/log10 (/ f-xx f-df)))))

;; ## Okapi BM25

;; http://en.wikipedia.org/wiki/Okapi_BM25
;; \text{score}(D,Q) = \sum_{i=1}^{n} \text{IDF}(q_i) \cdot \frac{f(q_i, D) \cdot (k_1 + 1)}{f(q_i, D) + k_1 \cdot (1 - b + b \cdot \frac{|D|}{\text{avgdl}})}

(defnk bm25
  [f-ii f-xx f-df] ;; How do keywords map to collocations?
  ())

;; FIXME needs average document length
(defnk okapi-tf
  [f-ii f-xx f-df k1 b]
  )

;; ## Similarity measures

;; These measures are learned from the whole vocabulary.
;; https://news.ycombinator.com/item?id=6216044
;; -   Vector space

;; ### Sketch Engine's Thesaurus Score

;; FIXME not sure we can do this (low priority):
(defnk thesaurus-score
  "Source: SkE p. 2
  To compute a similarity score, we:
  • compare w1 and w2’s word sketches
  • ignore contexts that supply no useful information (e. g. Association score < 0)
  • find all the overlaps, e. g. where w1 and w2 “share a triple” as in beer and wine “sharing” (drink, OBJECT, beer/wine)

                 Sum((tupi, tupj)∈{tupw1∩tupsw2} ASi + ASj − (ASi − ASj)^2 / 50)
  Dist(w1, w2) = ----------------------------------------------------------------
                                   Sum(tupi∈{tupw1 ∪tupw2}) ASi)

  The term (ASi − ASj)^2/50 is subtracted in order to give less
  weight to shared triples, where the triple is far more salient with
  w1 than w2 or vice versa. We find that this contributes to more
  readily interpretable results, where words of similar frequency are
  more often identified as near neighbours of each other. The constant
  50 can be changed using the -k option of the mkthes command."
  [constant])

;; ## Tri- and N-Gram Association Measures
;;
;; See http://www.sciencedirect.com/science/article/pii/S0885230809000448
;; Also see: http://www.sciencedirect.com/science/article/pii/S0957417413000067

;; TODO: use NLTK test data if there is any
;; Taken from NLTK source code (http://nltk.org/api/nltk.metrics.html):

;; FIXME delete when done
;; def score_ngram(self, score_fn, w1, w2, w3):
;;     """Returns the score for a given trigram using the given scoring
;;     function.
;;     """
;;     n_all = self.word_fd.N()
;;     n_iii = self.ngram_fd[(w1, w2, w3)]
;;     if not n_iii:
;;         return
;;     n_iix = self.bigram_fd[(w1, w2)]
;;     n_ixi = self.wildcard_fd[(w1, w3)]
;;     n_xii = self.bigram_fd[(w2, w3)]
;;     n_ixx = self.word_fd[w1]
;;     n_xix = self.word_fd[w2]
;;     n_xxi = self.word_fd[w3]
;;     return score_fn(n_iii,
;;                     (n_iix, n_ixi, n_xii),
;;                     (n_ixx, n_xix, n_xxi),
;;                     n_all)
;;
;; The arguments constitute the marginals of a contingency table, counting the occurrences of particular events in a corpus. The letter i in the suffix refers to the appearance of the word in question, while x indicates the appearance of any word. Thus, for example:
;; n_ii counts (w1, w2), i.e. the bigram being scored
;; n_ix counts (w1, *)
;; n_xi counts (*, w2)
;; n_xx counts (*, *), i.e. any bigram
;;
;; This may be shown with respect to a contingency table:
;;
;;         w1    ~w1
;;      ------ ------
;;  w2 | n_ii | n_oi | = n_xi
;;      ------ ------
;; ~w2 | n_io | n_oo |
;;      ------ ------
;;      = n_ix        TOTAL = n_xx


(defnk phi-sq [f-ii f-ix f-xi f-xx]
  ())

;;def phi_sq(cls, *marginals): """Scores bigrams using phi-square, the square of the Pearson correlation coefficient. """ n_ii, n_io, n_oi, n_oo = cls._contingency(*marginals) return (float((n_ii*n_oo - n_io*n_oi)**2) / ((n_ii + n_io) * (n_ii + n_oi) * (n_io + n_oo) * (n_oi + n_oo)))


;;[docs] def chi_sq(cls, n_ii, (n_ix, n_xi), n_xx): """Scores bigrams using chi-square, i.e. phi-sq multiplied by the number of bigrams, as in Manning and Schutze 5.3.3. """ return n_xx * cls.phi_sq(n_ii, (n_ix, n_xi), n_xx)

;; ## Association Measures Graph

(def association-measures-graph
  {;;:log-dice log-dice
   :t t
   :chi-sq chi-sq
   :mi mi
   :mi-3 mi-3
   :llr llr
   :ms ms
   :mi-log-prod mi-log-prod
   :dice dice
   :rf rf
   ;;:phi-sq phi-sq
   ;;:tf-idf weighted-tf-idf
   })

(comment
  ((graph/eager-compile association-measures-graph) (contingency-table :noun-particle-verb "薬" "もらう")))

(def compute-association-measures ; FIXME: eager faster than lazy? should move to simple functions for perf perhaps
  (graph/eager-compile association-measures-graph))

;; ## Summary Measure

;; When dealing with several measures, we can take the mean reciprocal rank (MRR) to get a summary rank.

(defn mean-reciprocal-rank
  [])

;; ## Measures that Operate on a Text

;; ### Yule's K

;; Baayen's implementation in R:
;; \value{
;;   Yule's characteristic constant K
;; }
;; \references{
;; Yule, G. U. (1944) \emph{The Statistical Study of Literary Vocabulary},
;; Cambridge: Cambridge University Press.
;;
;; Baayen, R. H. (2001) \emph{Word Frequency Distributions}, Dordrecht: Kluwer.
;; }
;; `yule.fnc` <-
;; function(spect) {
;;   N = sum(spect$frequency * spect$freqOfFreq)
;;   return(10000 * (sum(spect$frequency^2 * spect$freqOfFreq) - N)/N^2)
;; }
;; \arguments{
;;   \item{spect}{A frequency spectrum as generated by \code{spectrum.fnc}.}
;; }
;; `spectrum.fnc` <-
;; function(text) {
;;   tab = table(table(text))
;;   spectrum = data.frame(frequency = as.numeric(rownames(tf-ii)),
;;                         freqOfFreq = as.numeric(tf-ii))
;;   return(spectrum)
;; }

(defn- rank-frequencies [coll]
  (apply merge-with concat
         (for [[c f] (frequencies coll)]
           {f (list c)})))

(defn yules-k
  "Computes Yule's K for given collection.
  Yule's characteristic constant K is commonly used to measure the lexical richness of a text, which can also be an indicator of vocabulary difficulty.

  Yule's K = 10,000 * (M2 - M1) / (M1 * M1)
  Yule's I = (M1 * M1) / (M2 − M1)
  where M1 is the number of all word forms a text consists of and M2 is the sum of the products of each observed frequency to the power of two and the number of word types observed with that frequency (cf. Oakes 1998:204). For example, if one word occurs three times and four words occur five times, M2=(1*32)+(4*5 2)=109. The larger Yule's K, the smaller the diversity the vocabulary (and thus, argubaly, the easier the text). Since Yule's I is based on the reciprocal of Yule's K, the larger Yule's I, the larger the diversity of the vocabulary (and thus, arguably, the more difficult the text)."
  [coll]
  (let [N (count coll)
        rank-freq (rank-frequencies coll)]
    (* (float 10000)
       (- (reduce-kv (fn [init rank xs]
                       (+ init
                          (* (count xs)
                             (/ (* rank rank) N N))))
                     (float 0)
                     rank-freq)
          (/ 1 N)))))

(comment :test-for-yules-k)

;; ## Register measures

(s/defn mean :- Double
  [xs :- [s/Num]]
  (double (/ (reduce + xs) (count xs))))

(s/defn variance :- Double
  "Bias-corrected sample variance"
  [xs :- [s/Num]]
  (let [n (count xs)
        m (mean xs)
        square (map (fn [x] (* x x)))
        deviation (map (fn [x] (- x m)))]
    (/
     (transduce (comp deviation square)
                +
                0.0
                xs)
     (dec n))))

(s/defn sd :- Double
  [xs :- [s/Num]]
  (Math/sqrt (variance xs)))

;; (defn chisq-thresh [n]
;;   (first (stats/sample-chisq 0.05 :df n)))
;;
;; (defn chisq-test [xs]
;;   (stats/chisq-test :table xs))

(defn register
  "Based on tree similarities."
  [query]
  )
