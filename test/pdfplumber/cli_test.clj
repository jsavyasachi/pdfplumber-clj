(ns pdfplumber.cli-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pdfplumber.cli :as cli]
            [pdfplumber.core :as pdf]
            [pdfplumber.fixtures :as fix]))

(deftest parse-arguments
  (testing "defaults"
    (is (= {:path "sample.pdf"
            :format :csv
            :types [:char :line :rect :curve :image :annot]}
           (cli/parse-args ["sample.pdf"]))))
  (testing "explicit options"
    (is (= {:path "sample.pdf"
            :format :json
            :pages [1 3]
            :types [:char :line]
            :precision 2
           :indent 4}
           (cli/parse-args ["sample.pdf" "--format" "json"
                            "--pages" "1,3" "--types" "char,line"
                            "--precision" "2" "--indent" "4"]))))
  (testing "text format and inclusive ranges"
    (is (= {:path "sample.pdf" :format :text}
           (select-keys (cli/parse-args ["sample.pdf" "--format" "text"])
                        [:path :format])))
    (is (= [1 2 3 5]
           (:pages (cli/parse-args ["sample.pdf" "--pages" "1-3,5"]))))
    (doseq [args [["sample.pdf" "--pages" "3-1"]
                  ["sample.pdf" "--pages" "1,1"]
                  ["sample.pdf" "--pages" "1-"]]]
      (is (string? (:error (cli/parse-args args))))))
  (testing "space-separated lists"
    (is (= [1 3 5]
           (:pages (cli/parse-args ["sample.pdf" "--pages" "1" "3" "5"]))))
    (is (= [:char :line]
           (:types (cli/parse-args ["sample.pdf" "--types" "char" "line"])))))
  (testing "help and errors"
    (is (= {:help true} (cli/parse-args ["--help"])))
    (is (string? (:error (cli/parse-args []))))
    (is (string? (:error (cli/parse-args ["sample.pdf" "--format" "xml"]))))
    (is (string? (:error (cli/parse-args ["sample.pdf" "--pages" "nope"]))))))

(deftest render-json
  (pdf/with-pdf [#_:clj-kondo/ignore doc (fix/multi-page-pdf ["one" "two" "three"])]
    (let [output (cli/render doc {:format :json
                                  :pages [1 3]
                                  :types [:char]
                                  :precision 1})
          result (json/read-str output :key-fn keyword)]
      (is (contains? result :metadata))
      (is (contains? result :pages))
      (is (= [1 3] (mapv :page_number (:pages result))))
      (is (= [#{:char} #{:char}]
             (mapv (comp set keys :objects) (:pages result))))
      (is (= 78.7 (get-in result [:pages 0 :objects :char 0 :x1])))))
  (pdf/with-pdf [doc (fix/simple-text-pdf)]
    (let [lines (str/split-lines (cli/render doc {:format :json
                                                  :types [:char]
                                                  :indent 4}))]
      (is (< 1 (count lines)))
      (is (str/starts-with? (second lines) "    ")))))

(deftest render-csv
  (pdf/with-pdf [doc (fix/table-pdf)]
    (let [output (cli/render doc {:format :csv :types [:char :line]})
          lines (str/split-lines output)]
      (is (str/starts-with? (first lines) "object_type,"))
      (is (some #(str/starts-with? % "line,") (rest lines)))
      (is (some #(str/starts-with? % "char,") (rest lines)))))
  (pdf/with-pdf [doc (fix/advanced-text-pdf)]
    (let [output (cli/render doc {:format :csv :types [:char]})]
      (is (str/includes? output "\",\""))
      (is (not-any? #(str/starts-with? % "line,")
                    (rest (str/split-lines output))))))
  (pdf/with-pdf [doc (fix/simple-text-pdf)]
    (let [[header row] (map #(str/split % #",")
                            (take 2 (str/split-lines
                                     (cli/render doc {:format :csv
                                                      :types [:char]
                                                      :precision 1}))))
          x1-index (.indexOf ^java.util.List header "x1")]
      (is (= "80.7" (nth row x1-index))))))

(deftest render-text
  (pdf/with-pdf [doc (fix/multi-page-pdf ["one" "two" "three"])]
    (let [output (cli/render doc {:format :text :pages [2] :types [:char]
                                  :precision 1})]
      (is (= "Page 2" (first (str/split-lines output))))
      (is (str/includes? output "char: adv=3.3"))))
  (pdf/with-pdf [doc (fix/multi-page-pdf ["one" "two"])]
    (try
      (cli/render doc {:format :text :pages [1 3]})
      (is false "expected out-of-range page error")
      (catch clojure.lang.ExceptionInfo e
        (is (re-find #"out of range" (.getMessage e)))))))
