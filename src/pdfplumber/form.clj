(ns pdfplumber.form
  "Extract AcroForm fields with top-left widget geometry."
  (:refer-clojure :exclude [flatten])
  (:require [pdfplumber.geometry :as g])
  (:import [org.apache.pdfbox.pdmodel PDDocument PDPage]
           [org.apache.pdfbox.pdmodel.common PDRectangle]
           [org.apache.pdfbox.pdmodel.interactive.annotation PDAnnotationWidget]
           [org.apache.pdfbox.pdmodel.interactive.form PDAcroForm PDButton
            PDCheckBox PDChoice PDComboBox PDField PDListBox PDPushButton
            PDRadioButton PDSignatureField PDTerminalField PDTextField]
           [org.apache.pdfbox.io RandomAccessReadBuffer]
           [org.apache.pdfbox.pdfparser FDFParser]
           [org.apache.pdfbox.pdmodel.fdf FDFDocument]
           [java.io File FileInputStream InputStream OutputStream]))

(set! *warn-on-reflection* true)

(defn- field-type [^PDField field]
  (cond
    (instance? PDTextField field) :text
    (instance? PDCheckBox field) :checkbox
    (instance? PDRadioButton field) :radio
    (instance? PDComboBox field) :combo
    (instance? PDListBox field) :listbox
    (instance? PDChoice field) :choice
    (instance? PDPushButton field) :push-button
    (instance? PDSignatureField field) :signature))

(defn- field-value [^PDField field]
  (when-not (instance? PDSignatureField field)
    (try
      (.getValueAsString field)
      (catch Exception _ nil))))

(defn- default-value [^PDField field]
  (try
    (cond
      (instance? PDTextField field) (.getDefaultValue ^PDTextField field)
      (instance? PDChoice field) (let [value (.getDefaultValue ^PDChoice field)]
                                   (when (seq value) (vec value)))
      (instance? PDButton field) (.getDefaultValue ^PDButton field))
    (catch Exception _ nil)))

(defn- field-options [^PDField field]
  (cond
    (instance? PDChoice field)
    (let [^PDChoice choice field]
      (if (.hasSeparateExportAndDisplayValues choice)
        (mapv (fn [export display] {:export export :display display})
              (.getOptionsExportValues choice)
              (.getOptionsDisplayValues choice))
        (vec (.getOptions choice))))

    (instance? PDRadioButton field)
    (vec (.getExportValues ^PDRadioButton field))))

(defn- page-number [^PDDocument doc ^PDPage page]
  (loop [index 0]
    (when (< index (.getNumberOfPages doc))
      (if (= page (.getPage doc index))
        (inc index)
        (recur (inc index))))))

(defn- widget-geometry [^PDDocument doc ^PDField field]
  (some (fn [widget]
          (let [^PDAnnotationWidget widget widget
                ^PDPage page (.getPage widget)
                ^PDRectangle rect (.getRectangle widget)]
            (when (and page rect)
              (let [page-no (page-number doc page)
                    page-height (double (.getHeight (.getMediaBox page)))]
                (when page-no
                  {:page-number page-no
                   :bbox (g/pdfbox-rect->bbox
                          page-height
                          (.getLowerLeftX rect)
                          (.getLowerLeftY rect)
                          (.getWidth rect)
                          (.getHeight rect))})))))
        (.getWidgets field)))

(defn- field-map [^PDDocument doc ^PDTerminalField field]
  (let [widgets (.getWidgets field)
        widget-count (.size ^java.util.List widgets)
        default (default-value field)
        options (field-options field)]
    (cond-> {:name (.getFullyQualifiedName field)
             :type (field-type field)
             :value (field-value field)
             :required? (.isRequired field)
             :read-only? (.isReadOnly field)}
      (.getPartialName field) (assoc :partial-name (.getPartialName field))
      (some? default) (assoc :default-value default)
      (seq options) (assoc :options options)
      (instance? PDTextField field)
      (assoc :multiline? (.isMultiline ^PDTextField field))
      (and (instance? PDTextField field)
           (pos? (.getMaxLen ^PDTextField field)))
      (assoc :max-len (.getMaxLen ^PDTextField field))
      (> widget-count 1) (assoc :widget-count widget-count)
      :always (merge (widget-geometry doc field)))))

(defn form-fields
  "Return a vector of terminal AcroForm fields with first-widget geometry."
  [^PDDocument doc]
  (let [catalog (.getDocumentCatalog doc)
        ^PDAcroForm form (.getAcroForm catalog)]
    (if form
      (into []
            (comp (filter #(instance? PDTerminalField %))
                  (map #(field-map doc ^PDTerminalField %)))
            (iterator-seq (.iterator ^Iterable (.getFieldTree form))))
      [])))

(defn field-values
  "Map of terminal field names to values."
  [doc]
  (into {} (map (juxt :name :value)) (form-fields doc)))

(defn- acro-form ^PDAcroForm [^PDDocument doc]
  (.getAcroForm (.getDocumentCatalog doc)))

(defn- required-field ^PDField [^PDAcroForm form ^String name]
  (or (.getField form name)
      (throw (ex-info (str "No form field named " name)
                      {:pdfplumber/error :field-not-found :field name}))))

(defn- set-field-value! [^PDField field value]
  (cond
    (instance? PDCheckBox field)
    (if (or (true? value)
            (= value (.getOnValue ^PDCheckBox field)))
      (.check ^PDCheckBox field)
      (.unCheck ^PDCheckBox field))

    (instance? PDChoice field)
    (if (sequential? value)
      (.setValue ^PDChoice field (java.util.ArrayList. ^java.util.Collection value))
      (.setValue ^PDChoice field (str value)))

    :else
    (.setValue field (str value)))
  field)

(declare refresh-appearances)

(defn set-values
  "Set form field values by fully qualified field name and return `doc`.
   Text and scalar choice values are strings; multi-select choices accept a
   collection of strings. Checkbox values are booleans or the checkbox on-value."
  [^PDDocument doc values]
  (when-let [^PDAcroForm form (acro-form doc)]
    (doseq [[name value] values]
      (set-field-value! (required-field form ^String name) value)))
  (refresh-appearances doc)
  doc)

(defn refresh-appearances
  "Regenerate appearance streams for all form fields and return `doc`."
  [^PDDocument doc]
  (when-let [^PDAcroForm form (acro-form doc)]
    (.refreshAppearances form))
  doc)

(defn flatten
  "Bake form appearances into page content, remove interactive fields, and return `doc`."
  [^PDDocument doc]
  (when-let [^PDAcroForm form (acro-form doc)]
    (.flatten form))
  doc)

(defn export-fdf
  "Export form data to a String/File path or OutputStream and return `dest`."
  [^PDDocument doc dest]
  (let [^PDAcroForm form (acro-form doc)]
    (when-not form
      (throw (ex-info "Document has no AcroForm"
                      {:pdfplumber/error :no-acroform})))
    (let [^FDFDocument fdf (.exportFDF form)]
      (try
        (cond
          (instance? OutputStream dest) (.save fdf ^OutputStream dest)
          (instance? File dest) (.save fdf ^File dest)
          (string? dest) (.save fdf ^String dest)
          :else (throw (ex-info "FDF destination must be a path, File, or OutputStream"
                                {:pdfplumber/error :invalid-output
                                 :destination-class (class dest)})))
        (finally (.close fdf))))
    dest))

(defn- fdf-reader ^RandomAccessReadBuffer [source]
  (cond
    (bytes? source) (RandomAccessReadBuffer. ^bytes source)
    (instance? InputStream source) (RandomAccessReadBuffer. ^InputStream source)
    (instance? File source) (with-open [in (FileInputStream. ^File source)]
                               (RandomAccessReadBuffer. ^InputStream in))
    (string? source) (with-open [in (FileInputStream. ^String source)]
                       (RandomAccessReadBuffer. ^InputStream in))
    :else (throw (ex-info "FDF source must be bytes, an InputStream, or a path"
                          {:pdfplumber/error :invalid-input
                           :source-class (class source)}))))

(defn import-fdf
  "Import FDF data from bytes, an InputStream, String path, or File into `doc` and return it."
  [^PDDocument doc source]
  (let [^RandomAccessReadBuffer reader (fdf-reader source)]
    (try
      (let [^FDFDocument fdf (.parse (FDFParser. reader))]
        (try
          (if-let [^PDAcroForm form (acro-form doc)]
            (.importFDF form fdf)
            (throw (ex-info "Document has no AcroForm"
                            {:pdfplumber/error :no-acroform})))
          (finally (.close fdf))))
      (finally (.close reader)))
    doc))
