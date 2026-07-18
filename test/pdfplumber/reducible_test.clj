(ns pdfplumber.reducible-test
  (:require [clojure.test :refer [deftest is testing]]
            [pdfplumber.core :as pdf]
            [pdfplumber.fixtures :as fix]
            [pdfplumber.reducible :as r]))

(deftest reducible-api-is-available
  (is (every? fn? [r/page-reducible
                   r/reducible-chars r/reducible-words r/reducible-objects
                   r/reducible-lines r/reducible-rects r/reducible-curves
                   r/reducible-images r/reducible-annots])))

(deftest reducibles-match-page-scoped-eager-extraction
  (pdf/with-pdf [doc (fix/multi-page-pdf ["first page" "second page"])]
    (let [opts {:page 2}]
      (is (= (pdf/chars doc opts)
             (into [] (r/reducible-chars doc opts))))
      (is (= (pdf/words doc opts)
             (into [] (r/reducible-words doc opts))))))
  (pdf/with-pdf [doc (fix/ruled-pdf)]
    (let [opts {:page 1}]
      (is (= (pdf/objects doc opts)
             (into [] (r/reducible-objects doc opts))))
      (is (= (pdf/lines doc opts)
             (into [] (r/reducible-lines doc opts))))
      (is (= (pdf/rects doc opts)
             (into [] (r/reducible-rects doc opts))))
      (is (= (pdf/curves doc opts)
             (into [] (r/reducible-curves doc opts))))))
  (pdf/with-pdf [doc (fix/image-pdf)]
    (is (= (pdf/images doc)
           (into [] (r/reducible-images doc)))))
  (pdf/with-pdf [doc (fix/annotations-pdf)]
    (is (= (pdf/annots doc)
           (into [] (r/reducible-annots doc))))))

(deftest transducers-and-lazy-sequences-work
  (pdf/with-pdf [doc (fix/multi-page-pdf ["one two" "three four"])]
    (let [eager (vec (mapcat #(pdf/words doc {:page %}) [1 2]))
          words (r/reducible-words doc)]
      (is (instance? clojure.lang.IReduceInit words))
      (is (= (vec (take 2 eager))
             (into [] (take 2) words)))
      (is (= (apply str (map :text eager))
             (transduce (map :text) str words)))
      (is (= (mapv #(update % :text clojure.string/upper-case) eager)
             (into [] (eduction (map #(update % :text clojure.string/upper-case))
                                 words))))
      (is (= (mapv :text eager)
             (into [] (sequence (map :text) words)))))))

(deftest reduced-result-skips-later-pages
  (pdf/with-pdf [doc (fix/multi-page-pdf ["one" "two" "three"])]
    (let [visited (atom [])
          records (r/page-reducible doc
                                    (fn [_ {:keys [page]}]
                                      (swap! visited conj page)
                                      [{:page page}]))]
      (is (= [{:page 1}] (into [] (take 1) records)))
      (is (= [1] @visited)))))

(deftest cropped-page-view-is-preserved
  (pdf/with-pdf [doc (fix/simple-text-pdf)]
    (let [hello (first (pdf/words doc))
          bbox [(- (:x0 hello) 1) (- (:top hello) 1)
                (+ (:x1 hello) 1) (+ (:bottom hello) 1)]
          view (pdf/crop-page doc {:page 1 :bbox bbox})]
      (testing "view operations apply to each extracted page"
        (is (= (pdf/words view)
               (into [] (r/reducible-words view))))))))
