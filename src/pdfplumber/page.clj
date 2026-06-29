(ns pdfplumber.page
  "Lightweight cropped page views. A view carries the document handle, a page
   number, and a crop bbox; extraction functions accept it in place of a
   document handle and restrict their output to the bbox. Nothing is copied or
   translated — a view is just resolved into `:page`/`:bbox` options.")

(defn crop-page
  "A cropped page view over `doc`. `opts` takes `:page` (1-based) and `:bbox`
   `[x0 top x1 bottom]`. Pass the result to `chars`/`words`/`text`/`objects` to
   restrict extraction to the region."
  [doc {:keys [page bbox]}]
  {::view true :document doc :page page :bbox bbox})

(defn page-view?
  "True when `x` is a cropped page view produced by `crop-page`."
  [x]
  (boolean (and (map? x) (::view x))))

(defn resolve-source
  "Normalize a document-or-view plus `opts` into `[doc merged-opts]`. A view's
   `:page`/`:bbox` become options; explicit `opts` override them."
  [source opts]
  (if (page-view? source)
    [(:document source)
     (merge (cond-> {}
              (:page source) (assoc :page (:page source))
              (:bbox source) (assoc :bbox (:bbox source)))
            opts)]
    [source opts]))
