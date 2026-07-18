(ns pdfplumber.form
  "AcroForm field extraction with top-left widget geometry."
  (:require [pdfplumber.geometry :as g])
  (:import [org.apache.pdfbox.pdmodel PDDocument PDPage]
           [org.apache.pdfbox.pdmodel.common PDRectangle]
           [org.apache.pdfbox.pdmodel.interactive.annotation PDAnnotationWidget]
           [org.apache.pdfbox.pdmodel.interactive.form PDAcroForm PDButton
            PDCheckBox PDChoice PDComboBox PDField PDListBox PDPushButton
            PDRadioButton PDSignatureField PDTerminalField PDTextField]))

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
  "Vector of terminal AcroForm fields with first-widget geometry."
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
