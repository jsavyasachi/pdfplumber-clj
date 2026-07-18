(ns pdfplumber.table-maps-test
  (:require [clojure.test :refer [deftest is]]
            [pdfplumber.table :as table]))

(deftest first-row-headers
  (is (= [{"Name" "Ada" "Age" "36"}
          {"Name" "Grace" "Age" nil}]
         (table/table->maps [["Name" "Age"]
                             ["Ada" "36"]
                             ["Grace" nil]]))))

(deftest keywordized-headers
  (is (= [{:first-name "Ada" :order-total "10"}]
         (table/table->maps [[" First Name " "Order Total"]
                             ["Ada" "10"]]
                            {:keywordize? true}))))

(deftest explicit-headers
  (is (= [{:name "Ada" :age 36}]
         (table/table->maps [["Ada" 36 "ignored"]]
                            {:header [:name :age]}))))

(deftest positional-headers
  (is (= [{0 "Ada" 1 36}
          {0 "Grace" 1 nil}]
         (table/table->maps [["Ada" 36]
                             ["Grace"]]
                            {:header false})))
  (is (= [{0 "Ada" 1 36}]
         (table/table->maps [["Ada" 36]]
                            {:header false :keywordize? true}))))

(deftest ragged-rows-and-duplicate-or-blank-headers
  (is (= [{"Name" "Ada" "Name-2" "Lovelace" 2 nil 3 nil}
          {"Name" "Grace" "Name-2" "Hopper" 2 "Rear Admiral" 3 nil}]
         (table/table->maps [["Name" "Name" "" nil]
                             ["Ada" "Lovelace"]
                             ["Grace" "Hopper" "Rear Admiral" nil "ignored"]]))))

(deftest extracted-table-map-input
  (is (= [{"Name" "Ada"}]
         (table/table->maps {:rows [[{:text "Name" :bbox [0 0 1 1]}]
                                    [{:text "Ada" :bbox [0 1 1 2]}]]}))))

(deftest empty-table
  (is (= [] (table/table->maps [])))
  (is (= [] (table/table->maps {:rows []})))
  (is (= [] (table/table->maps nil))))
