(ns pdfplumber.cli
  "Command-line PDF object export."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pdfplumber.core :as pdf]))

(set! *warn-on-reflection* true)

(def ^:private all-types [:char :line :rect :curve :image :annot])

(def ^:private extractors
  {:char pdf/chars
   :line pdf/lines
   :rect pdf/rects
   :curve pdf/curves
   :image pdf/images
   :annot pdf/annots})

(def ^:private usage
  (str "Usage: clojure -M -m pdfplumber.cli <pdf-path> [options]\n\n"
       "Options:\n"
       "  --format csv|json        Output format (default: csv)\n"
       "  --pages 1,2,5            Page numbers (comma- or space-separated)\n"
       "  --types char,line,...    Object types (default: all)\n"
       "  --precision N            Round numeric attributes to N decimals\n"
       "  --indent N               JSON indentation width\n"
       "  --help                   Show this help"))

(defn- option? [s]
  (str/starts-with? ^String s "--"))

(defn- take-values [args]
  (split-with (complement option?) args))

(defn- comma-values [values]
  (->> values
       (mapcat #(str/split % #","))
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- parse-nonnegative [flag value]
  (try
    (let [n (parse-long (or value ""))]
      (if (and n (not (neg? n)))
        n
        {:error (str flag " requires a non-negative integer")}))
    (catch NumberFormatException _
      {:error (str flag " requires a non-negative integer")})))

(defn- parse-pages [values]
  (let [parts (comma-values values)]
    (if (empty? parts)
      {:error "--pages requires at least one page number"}
      (try
        (let [pages (mapv parse-long parts)]
          (if (every? #(and % (pos? %)) pages)
            pages
            {:error "--pages requires positive page numbers"}))
        (catch NumberFormatException _
          {:error "--pages requires positive page numbers"})))))

(defn- parse-types [values]
  (let [parts (comma-values values)
        types (mapv keyword parts)
        unknown (remove (set all-types) types)]
    (cond
      (empty? types) {:error "--types requires at least one object type"}
      (seq unknown) {:error (str "Unknown object type: " (name (first unknown)))}
      :else types)))

(defn parse-args
  "Parse command-line arguments."
  [argv]
  (if (some #{"--help" "-h"} argv)
    {:help true}
    (loop [args (seq argv)
           opts {:format :csv :types all-types}]
      (if-let [arg (first args)]
        (case arg
          "--format"
          (let [value (second args)]
            (cond
              (nil? value) {:error "--format requires csv or json"}
              (not (#{"csv" "json"} value))
              {:error (str "Unsupported format: " value)}
              :else (recur (nnext args) (assoc opts :format (keyword value)))))

          "--pages"
          (let [[values remaining] (take-values (next args))
                result (parse-pages values)]
            (if (:error result)
              result
              (recur remaining (assoc opts :pages result))))

          "--types"
          (let [[values remaining] (take-values (next args))
                result (parse-types values)]
            (if (:error result)
              result
              (recur remaining (assoc opts :types result))))

          "--precision"
          (let [result (parse-nonnegative "--precision" (second args))]
            (if (:error result)
              result
              (recur (nnext args) (assoc opts :precision result))))

          "--indent"
          (let [result (parse-nonnegative "--indent" (second args))]
            (if (:error result)
              result
              (recur (nnext args) (assoc opts :indent result))))

          (cond
            (option? arg) {:error (str "Unknown option: " arg)}
            (:path opts) {:error (str "Unexpected argument: " arg)}
            :else (recur (next args) (assoc opts :path arg))))
        (if (:path opts)
          opts
          {:error "Missing PDF path"})))))

(defn- round-number [n precision]
  (if (and precision (number? n) (not (integer? n)))
    (let [factor (Math/pow 10.0 (double precision))]
      (/ (double (Math/round (* (double n) factor))) factor))
    n))

(defn- transform-value [value precision json?]
  (cond
    (map? value)
    (into {}
          (map (fn [[k v]]
                 [(if json?
                    (str/replace (name k) "-" "_")
                    k)
                  (transform-value v precision json?)]))
          value)

    (vector? value) (mapv #(transform-value % precision json?) value)
    (seq? value) (mapv #(transform-value % precision json?) value)
    (set? value) (mapv #(transform-value % precision json?) (sort-by str value))
    (and json? (keyword? value)) (name value)
    (number? value) (round-number value precision)
    :else value))

(defn- selected-pages [doc pages]
  (let [wanted (when pages (set pages))]
    (cond->> (pdf/pages doc)
      wanted (filterv #(contains? wanted (:page-number %))))))

(defn- page-objects [doc page-number types precision]
  (into {}
        (map (fn [type]
               [type (mapv #(transform-value % precision false)
                           ((get extractors type) doc {:page page-number}))]))
        types))

(defn- json-document [doc {:keys [pages types precision]}]
  {:metadata (pdf/metadata doc)
   :pages (mapv (fn [{:keys [page-number width height]}]
                  {:page-number page-number
                   :width width
                   :height height
                   :objects (page-objects doc page-number types precision)})
                (selected-pages doc pages))})

(defn- reindent [s width]
  (->> (str/split s #"\n" -1)
       (map (fn [line]
              (let [spaces (count (re-find #"^ *" line))
                    depth (quot spaces 2)]
                (str (apply str (repeat (* width depth) \space))
                     (subs line spaces)))))
       (str/join "\n")))

(defn- render-json [doc opts]
  (let [data (transform-value (json-document doc opts) (:precision opts) true)]
    (if (some? (:indent opts))
      (reindent (json/write-str data :indent true) (:indent opts))
      (json/write-str data))))

(defn- csv-string [value]
  (let [s (cond
            (nil? value) ""
            (keyword? value) (name value)
            (or (coll? value) (sequential? value)) (pr-str value)
            :else (str value))]
    (if (re-find #"[,\"\r\n]" s)
      (str "\"" (str/replace s "\"" "\"\"") "\"")
      s)))

(defn- render-csv [doc {:keys [pages types precision]}]
  (let [rows (for [{:keys [page-number]} (selected-pages doc pages)
                   type types
                   object ((get extractors type) doc {:page page-number})]
               [type (transform-value object precision false)])
        columns (->> rows
                     (mapcat (comp keys second))
                     (remove #{:object-type})
                     distinct
                     (sort-by name)
                     vec)
        header (cons "object_type" (map #(str/replace (name %) "-" "_") columns))
        data (map (fn [[type object]]
                    (cons (name type) (map #(get object %) columns)))
                  rows)]
    (->> (cons header data)
         (map #(str/join "," (map csv-string %)))
         (str/join "\n"))))

(defn render
  "Render selected PDF objects."
  [doc opts]
  (let [opts (merge {:format :csv :types all-types} opts)]
    (case (:format opts)
      :json (render-json doc opts)
      :csv (render-csv doc opts)
      (throw (ex-info "Unsupported output format" {:format (:format opts)})))))

(defn -main
  "Dump PDF objects to standard output."
  [& argv]
  (let [opts (parse-args argv)]
    (cond
      (:help opts) (println usage)
      (:error opts) (println (str (:error opts) "\n\n" usage))
      :else (pdf/with-pdf [doc (:path opts)]
              (print (render doc opts))))))
