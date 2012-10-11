(ns natsume-server.collocations-test
  (:use [midje.sweet]
        [natsume-server.collocations])
  (:require [natsume-server.cabocha-wrapper :as cw]))

;; Test data

(def test-sentences-quotes
  ["大友皇子は1870年に諡号を贈られて弘文天皇と呼ばれたため、弘文天皇即位説（こうぶんてんのうそくいせつ）ともいう。"
   "緑の革命（みどりのかくめい、英: Green Revolution）とは、1940年代から1960年代にかけて、穀物の大量増産を達成したこと。" ; Wikipedia-style phonetic/acronym information quoting
   "農民参加型の品種選択法（PVS, Farmer's participatory varietal selection）を通じて、米の増産の成果が得られている[* 11]。"
   "緑の革命での新技術の開発は、「奇跡の種子」と称された物の生産であった[11]。"
   "1)セロはその事実(人間がネコの王となってしまった事)に慌てふためき驚愕する。"
   "本当は、自分がいなくなると聞いて、会社の幹部に「君、そ、そりゃ困る!」と慌てふためいてほしい、というのがホンネだろう。"
   "途中で気が付き、車内で「あ〜、お菓子忘れた〜!」と慌てるあたし達の会話にも無言で運転し続けた"
   "運転手は「危ない、衝突する!」と慌てて急ブレーキをかけるが、その瞬間、あちらの汽車は忽然と姿を消してしまうのである。"
   "\"-おそらくこの\"芯\"はわたしの誤用といってもよい\"語の濫用\"で絲玉の芯、蹴鞠の芯、それをほどいて行くときの心の弾みまでも込めようとしている。"
   "路上ライブでの多くの出会いを奥華子は大切にしており、その想いは楽曲「笑って笑って」(インディーズアルバム『vol.best』収録)に込められている。" ; double- and adjacent-quotes
   "「映画史」と題されてはいる(原題「Histoire(s)ducinma」、確かに修飾詞はないが、(s)に「複数のあるいは/および単数の」の意味が込められていることに注意。)が、ここで参照される。"
   "その時に一緒に搭乗していた坂本冬美が慌てて、バッグで隠したというが、本人は全く気づかなかったらしい(2008年12月9日放送の「ぴったんこカン・カン」内で坂本が発言)。"
   "「ひょうきん予備校」に講師役として出演した際も生徒役の非常階段に「こんなしょうもないコンビ名、聞くに及ばん!」などと怒鳴り散らしていた(この時、同じく生徒役のダウンタウンは「僕がダウンで彼がタウンです」と慌てて名前を分割し難を逃れた)。"
   "ビーツ一個と人参一本を‘都合して’きた。"
   "黄真伊(ファン・ジニ、妓名は明月(ミョンウォル、)、約1506年 - 1544年頃)は、中宗の治世中に活躍した、李氏朝鮮で最も伝説的な妓生である。"
   ]
  )

(def test-sentences-escape ; this is meant more for checking if the sentences are correctly escaped to be inserted into postgres
  ["1979年11月1日に、CBS・ソニーレコード(現・ソニー・ミュージックエンタテインメント)より、川崎麻世の歌う主題歌2曲を収録したシングル盤(品番:06SH671)が定価\\\\600で発売された。"
   "1979年11月1日に、CBS・ソニーレコード(現・ソニー・ミュージックエンタテインメント)より、川崎麻世の歌う主題歌2曲を収録したシングル盤(品番:06SH671)が定価\\600で発売された。"
   ;; Is there a way to test this ala QuickCheck?
   "\b"
   "\f"
   "\n"
   "\r
\t
\\v
\1
\2
\3
\4
\5
\6
\7
\\8
\\9
\\x0
\\x0
\\x1
\\xa
\\x3
\\x4
\\x5
\\x18
\\\0
\\\\0
\\\\\0
\\\\\\0
\\\\\\\0
\0"])

(def test-date-normalization
  ["例えば、〇二年四月三〇日〜五月二日・復旦大学日本研究センターが主催した「戦後日本の主要社会思潮および中日関係」。" ; Date normalization + quote
   "しかし同年オフ、階段で転倒しそうになり、とっさに手すりをつかんだ際に脱臼。" ; ?
   "しかし、その自殺は極めて疑わしく、階段からわざと転落させられたとも言われる" ; Conjugation check
   ])

(def test-name-normalization
  ["二人を慌てて呼び止め、ミゲルがペネロペから預かった手紙を見せたので、ようやく二人は安堵した。" ; Name normalization
   "しかし、ルゥは無邪気に超能力で物を持ち上げたりフワフワと空を飛んだりして、2人を慌てさせる。"
   "サトウが慌てて、その腕をつかんで押し止める。"
   "どうせ、いざ選ぶとなると「わからん」といって猟奇的な彼女、ターミネーター、三ちよう目夕日、ドラえもん、電車男を足して5で割るみたい。" ; character and fictional names sanity check
   ; test results below
   "【人名】たち"
   "【人名】さん" ; or 〜さん
   "【地域】" ; do we want to group these? in some cases it is better to differentiate here?
   "【組織】"])

(def test-number-normalization
  ["小型で許容回転数を16000rpmと高める事で効率を改善している。" ; Number normalization
   "そのため校内を行き来する場合は100段近い階段を上り下りしなければならない。"
   "第2ソースファイルの情報を追加しました。"])

(def test-nouns
  ["名詞"
   "複合名詞"
   "複合名詞句"])

(def test-verbs
  ["言う"
   "言った"
   "言わない"
   "言わなかった"
   "言います"
   "言いました"
   "言いません"
   "言いませんでした"
   "降り始める"
   "言わせる"
   "言わせられる"
   "複合する"
   "複合させる"
   "複合できる"
   "複合させられる"
   "複合させられた"
   "複合させられなかった"
   "遊覧飛行する"])

(def test-adverbs
  [""
   ""
   ""])

(def test-adjectives
  ["綺麗"
   "綺麗な"
   "愛しい"
   "愛しく"
   "愛しくなかった"
   "愛しくありませんでした"
   "愛しかった"
   "転がりやすい"])

;; Tests

(fact "morpheme is a noun"
  (:pos1 (first (cw/tree-to-morphemes-flat (cw/string-to-tree "名詞"))))
  =>
  "名詞")

(fact "verbal chunks are verbs"
  (map #(:head-type (first %)) test-verbs) => (has every? :verb))

;; How to structure the test sentences? i.e. should there be one sentence list over which lots of facts should be checked against, or one/many facts per sentence (in a let binding)?
