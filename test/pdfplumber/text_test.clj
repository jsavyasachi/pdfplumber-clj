(ns pdfplumber.text-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [pdfplumber.core :as pdf]
            [pdfplumber.fixtures :as fix]))

(deftest chars-extraction
  (pdf/with-pdf [d (fix/simple-text-pdf)]
    (let [cs (pdf/chars d)]
      (testing "captures every glyph (ignoring spacing)"
        (is (= "HelloPDF" (str/replace (apply str (map :text cs)) #"\s" ""))))
      (testing "char maps carry position, font, and page"
        (let [h (first cs)]
          (is (every? #(contains? h %) [:text :x0 :top :x1 :bottom :font-name :font-size :page-number]))
          (is (= 1 (:page-number h)))
          (is (< (:top h) (:bottom h)))         ; top-left origin
          (is (< 11.0 (:font-size h) 13.0))     ; drawn at 12pt
          (is (re-find #"(?i)helvetica" (:font-name h))))))))

(deftest rich-char-records
  (pdf/with-pdf [d (fix/multi-page-pdf ["A" "B"])]
    (let [[a b] (pdf/chars d)]
      (testing "pdfplumber-compatible character attributes"
        (is (every? #(contains? a %)
                    [:x0 :x1 :y0 :y1 :top :bottom :width :height :doctop
                     :page-number :fontname :size :adv :upright :matrix
                     :object-type]))
        (is (= :char (:object-type a)))
        (is (= (:font-name a) (:fontname a)))
        (is (== (:font-size a) (:size a)))
        (is (== (- (:x1 a) (:x0 a)) (:width a)))
        (is (== (- (:bottom a) (:top a)) (:height a)))
        (is (== (- 792.0 (:bottom a)) (:y0 a)))
        (is (== (- 792.0 (:top a)) (:y1 a)))
        (is (number? (:adv a)))
        (is (boolean? (:upright a)))
        (is (= 6 (count (:matrix a)))))
      (testing "doctop is cumulative across pages"
        (is (== (:top a) (:doctop a)))
        (is (== (+ 792.0 (:top b)) (:doctop b)))))))

(deftest words-grouping
  (pdf/with-pdf [d (fix/simple-text-pdf)]
    (testing "splits on the space into two words"
      (is (= ["Hello" "PDF"] (mapv :text (pdf/words d)))))
    (testing "word bbox spans its chars"
      (let [hello (first (pdf/words d))]
        (is (< (:x0 hello) (:x1 hello)))
        (is (< (:top hello) (:bottom hello)))))))

(deftest text-reconstruction
  (pdf/with-pdf [d (fix/simple-text-pdf)]
    (is (= "Hello PDF" (pdf/text d)))))

(deftest bbox-filtering
  (pdf/with-pdf [d (fix/simple-text-pdf)]
    (let [hello (first (filter #(= "Hello" (:text %)) (pdf/words d)))
          box [(- (:x0 hello) 1) (- (:top hello) 1)
               (+ (:x1 hello) 1) (+ (:bottom hello) 1)]]
      (testing "cropping to one word's region excludes the other"
        (is (= ["Hello"] (mapv :text (pdf/words d {:bbox box}))))))))

(deftest page-scoped-extraction
  (pdf/with-pdf [d (fix/multi-page-pdf ["one" "two" "three"])]
    (testing "extraction limited to a single page"
      (is (= ["two"] (mapv :text (pdf/words d {:page 2}))))
      (is (= [2] (distinct (map :page-number (pdf/chars d {:page 2}))))))))

(deftest advanced-word-options
  (pdf/with-pdf [d (fix/advanced-text-pdf)]
    (testing "punctuation can become separate words"
      (is (= ["alpha" "," "beta" "right" "second" "line"]
             (mapv :text (pdf/words d {:split-at-punctuation true})))))
    (testing "blank characters can remain inside words"
      (is (= ["second line"]
             (->> (pdf/words d {:keep-blank-chars true})
                  (filter #(str/starts-with? (:text %) "second"))
                  (mapv :text)))))
    (testing "right-to-left output reverses characters and word order"
      (is (= "thgir" (:text (nth (pdf/words d {:horizontal-ltr false}) 0)))))
    (testing "extra attributes are copied to word records"
      (is (every? #(re-find #"(?i)helvetica" (:fontname %))
                  (pdf/words d {:extra-attrs [:fontname]}))))
    (testing "text flow preserves content-stream order"
      (is (= "right" (:text (first (pdf/words d {:use-text-flow true}))))))))

(deftest text-maps-and-layout
  (pdf/with-pdf [d (fix/advanced-text-pdf)]
    (let [extract-text-var (ns-resolve 'pdfplumber.core 'extract-text)
          extract-words-var (ns-resolve 'pdfplumber.core 'extract-words)
          word-map-var (ns-resolve 'pdfplumber.core 'word-map)
          text-map-var (ns-resolve 'pdfplumber.core 'text-map)]
      (is (every? some? [extract-text-var extract-words-var word-map-var text-map-var]))
      (when (every? some? [extract-text-var extract-words-var word-map-var text-map-var])
        (is (= (pdf/words d) (extract-words-var d)))
        (let [wm (word-map-var d)
              tm (text-map-var d)
              laid-out (extract-text-var d {:layout true :x-density 6.0})]
          (is (= (count (:words wm)) (count (:word-chars wm))))
          (is (= (:text tm) (apply str (map second (:tuples tm)))))
          (is (every? #(or (nil? (first %)) (map? (first %))) (:tuples tm)))
          (is (re-find #"alpha,beta\s{5,}right" laid-out)))))))

(deftest text-lines-and-positional-search
  (pdf/with-pdf [d (fix/advanced-text-pdf)]
    (let [lines-var (ns-resolve 'pdfplumber.core 'extract-text-lines)
          search-var (ns-resolve 'pdfplumber.core 'search)]
      (is (every? some? [lines-var search-var]))
      (when (and lines-var search-var)
        (let [lines (lines-var d)]
          (is (= ["alpha,beta right" "second line"] (mapv :text lines)))
          (is (every? #(every? (partial contains? %) [:x0 :top :x1 :bottom :chars])
                      lines))
          (is (= "alpha,betaright" (str/replace (apply str (map :text (:chars (first lines)))) #"\s" ""))))
        (let [match (first (search-var d #"alpha,(be)(ta)"))]
          (is (= "alpha,beta" (:text match)))
          (is (= ["be" "ta"] (:groups match)))
          (is (= "alpha,beta" (apply str (map :text (:chars match)))))
          (is (< (:x0 match) (:x1 match)))
          (is (< (:top match) (:bottom match))))
        (is (= ["second line"]
               (mapv :text (search-var d "second line" {:regex false}))))))))

(deftest character-deduplication
  (pdf/with-pdf [d (fix/duplicate-text-pdf)]
    (let [dedupe-var (ns-resolve 'pdfplumber.core 'dedupe-chars)]
      (is (some? dedupe-var))
      (when dedupe-var
        (is (= 2 (count (pdf/chars d))))
        (is (= 1 (count (dedupe-var d {:tolerance 11.0}))))
        (is (= 2 (count (dedupe-var d {:tolerance 0.1}))))
        (is (= 1 (count (dedupe-var d {:tolerance 11.0
                                       :compare-attrs [:fontname :size]}))))))))

(deftest simple-text-entry-point
  (let [simple-var (ns-resolve 'pdfplumber.core 'extract-text-simple)]
    (is (some? simple-var))
    (when simple-var
      (pdf/with-pdf [d (fix/advanced-text-pdf)]
        (is (= "alpha,beta right\nsecond line" (simple-var d))))
      (pdf/with-pdf [d (fix/multi-page-pdf ["one" "two"])]
        (is (= "one\ntwo" (simple-var d)))))))
