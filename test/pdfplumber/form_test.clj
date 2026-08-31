(ns pdfplumber.form-test
  (:require [clojure.test :refer [deftest is testing]]
            [pdfplumber.core :as pdf]
            [pdfplumber.fixtures :as fix]
            [pdfplumber.form :as form])
  (:import [org.apache.pdfbox.cos COSDictionary COSName COSStream]
           [org.apache.pdfbox.pdmodel PDDocument PDPage]
           [org.apache.pdfbox.pdmodel.common PDRectangle]
           [org.apache.pdfbox.pdmodel.interactive.annotation PDAnnotationWidget]
           [org.apache.pdfbox.pdmodel.interactive.form PDAcroForm PDCheckBox
            PDComboBox PDField PDNonTerminalField PDTextField]
           [java.io ByteArrayInputStream ByteArrayOutputStream]))

(set! *warn-on-reflection* true)

(defn- place-widget! [^PDField field ^PDPage page x y w h]
  (let [^PDAnnotationWidget widget (first (.getWidgets field))]
    (.setRectangle widget (PDRectangle. (float x) (float y) (float w) (float h)))
    (.setPage widget page)
    widget))

(defn- form-doc ^PDDocument []
  (let [doc (PDDocument.)
        page (PDPage. PDRectangle/LETTER)
        form (PDAcroForm. doc)
        parent (PDNonTerminalField. form)
        text-field (PDTextField. form)
        checkbox (PDCheckBox. form)
        combo (PDComboBox. form)]
    (.addPage doc page)
    (.setAcroForm (.getDocumentCatalog doc) form)

    (.setPartialName parent "profile")
    (.setPartialName text-field "name")
    (.setString (.getCOSObject text-field) COSName/V "Ada")
    (.setDefaultValue text-field "Unknown")
    (.setRequired text-field true)
    (.setMultiline text-field true)
    (.setMaxLen text-field 80)

    (.setPartialName checkbox "active")
    (.setName (.getCOSObject checkbox) COSName/V "Yes")
    (.setReadOnly checkbox true)

    (.setPartialName combo "country")
    (.setOptions combo ["us" "ca"] ["United States" "Canada"])
    (.setString (.getCOSObject combo) COSName/V "ca")

    (let [widgets [(place-widget! text-field page 72 650 160 20)
                   (place-widget! checkbox page 72 610 20 20)
                   (place-widget! combo page 72 570 160 20)]
          appearance-n (doto (COSDictionary.)
                         (.setItem COSName/OFF (COSStream.))
                         (.setItem COSName/YES (COSStream.)))
          appearance (doto (COSDictionary.)
                       (.setItem COSName/N appearance-n))]
      (.setItem (.getCOSObject ^PDAnnotationWidget (second widgets)) COSName/AP appearance)
      (.setAnnotations page widgets))
    (.setChildren parent [text-field checkbox combo])
    (.setFields form [parent])
    doc))

(deftest extracts-terminal-form-fields
  (with-open [doc (form-doc)]
    (let [fields (form/form-fields doc)
          by-name (into {} (map (juxt :name identity)) fields)
          text-field (get by-name "profile.name")
          checkbox (get by-name "profile.active")
          combo (get by-name "profile.country")]
      (is (= 3 (count fields)))
      (testing "text field metadata and top-left geometry"
        (is (= {:name "profile.name"
                :partial-name "name"
                :type :text
                :value "Ada"
                :default-value "Unknown"
                :required? true
                :read-only? false
                :multiline? true
                :max-len 80
                :page-number 1
                :bbox [72.0 122.0 232.0 142.0]}
               text-field)))
      (testing "checkbox value and flags"
        (is (= :checkbox (:type checkbox)))
        (is (= "Yes" (:value checkbox)))
        (is (false? (:required? checkbox)))
        (is (true? (:read-only? checkbox)))
        (is (= 1 (:page-number checkbox)))
        (is (= [72.0 162.0 92.0 182.0] (:bbox checkbox))))
      (testing "choice export and display options"
        (is (= :combo (:type combo)))
        (is (= "[ca]" (:value combo)))
        (is (= [{:export "us" :display "United States"}
                {:export "ca" :display "Canada"}]
               (:options combo)))
        (is (= [72.0 202.0 232.0 222.0] (:bbox combo))))
      (is (= {"profile.name" "Ada"
              "profile.active" "Yes"
              "profile.country" "[ca]"}
             (form/field-values doc))))))

(deftest no-acroform-is-empty
  (with-open [^PDDocument doc (pdf/open-pdf (fix/simple-text-pdf))]
    (is (= [] (form/form-fields doc)))
    (is (= {} (form/field-values doc)))))

(deftest setting-values-without-acroform-throws
  (with-open [^PDDocument doc (pdf/open-pdf (fix/simple-text-pdf))]
    (try
      (form/set-values doc {"missing" "value"})
      (is false "set-values should reject a non-empty update without an AcroForm")
      (catch clojure.lang.ExceptionInfo error
        (is (= :no-acroform (:pdfplumber/error (ex-data error))))))))

(deftest sets-form-values-and-refreshes-appearances
  (with-open [doc (form-doc)]
    (.setReadOnly ^PDCheckBox (.getField (.getAcroForm (.getDocumentCatalog doc)) "profile.active") false)
    (is (identical? doc (form/set-values doc {"profile.name" "Grace"
                                               "profile.active" false
                                               "profile.country" "us"})))
    (is (= {"profile.name" "Grace"
            "profile.active" "Off"
            "profile.country" "[us]"}
           (form/field-values doc)))
    (is (identical? doc (form/refresh-appearances doc)))))

(deftest flatten-removes-interactive-fields
  (with-open [doc (form-doc)]
    (.setReadOnly ^PDCheckBox (.getField (.getAcroForm (.getDocumentCatalog doc)) "profile.active") false)
    (form/set-values doc {"profile.name" "Flattened" "profile.active" true})
    (is (identical? doc (form/flatten doc)))
    (is (= [] (form/form-fields doc)))))

(deftest fdf-export-import-round-trip
  (with-open [doc (form-doc)]
    (.setReadOnly ^PDCheckBox (.getField (.getAcroForm (.getDocumentCatalog doc)) "profile.active") false)
    (form/set-values doc {"profile.name" "FDF round trip" "profile.active" true})
    (let [output (ByteArrayOutputStream.)]
      (is (= output (form/export-fdf doc output)))
      (with-open [copy (form-doc)]
        (.setReadOnly ^PDCheckBox (.getField (.getAcroForm (.getDocumentCatalog copy)) "profile.active") false)
        (is (identical? copy
                       (form/import-fdf copy (ByteArrayInputStream. (.toByteArray output)))))
        (is (= {"profile.name" "FDF round trip"
                "profile.active" "Yes"
                "profile.country" "[ca]"}
               (form/field-values copy)))))))
