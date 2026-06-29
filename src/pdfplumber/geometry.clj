(ns pdfplumber.geometry
  "Bounding-box math and coordinate conversion.

   The public coordinate system has a top-left origin (matching Python
   `pdfplumber`): a bounding box is `[x0 top x1 bottom]` in PDF user-space points
   with `x0 <= x1` and `top <= bottom`. PDFBox works in a bottom-left origin, so
   conversion happens here and nowhere else. All extraction code must use these
   helpers rather than open-coding the arithmetic."
  (:refer-clojure :exclude [contains?]))

(set! *warn-on-reflection* true)

(defn flip-y
  "Convert a single y between bottom-origin and top-origin about `page-height`.
   Self-inverse: `(flip-y h (flip-y h y)) == y`."
  [page-height y]
  (- page-height y))

(defn pdfbox-rect->bbox
  "Convert a PDFBox lower-left rectangle `(x y w h)` on a page of `page-height`
   into a public top-left bbox `[x0 top x1 bottom]`."
  [page-height x y w h]
  (let [x0 (double x)
        x1 (double (+ x w))
        top (double (- page-height (+ y h)))
        bottom (double (- page-height y))]
    [x0 top x1 bottom]))

(defn bbox-width  [[x0 _ x1 _]] (- x1 x0))
(defn bbox-height [[_ top _ bottom]] (- bottom top))

(defn center
  "Midpoint `[x y]` of a bbox."
  [[x0 top x1 bottom]]
  [(/ (+ (double x0) x1) 2) (/ (+ (double top) bottom) 2)])

(defn intersects?
  "True when `a` and `b` overlap with positive area. Edge-touching boxes (zero
   overlap area) are not considered intersecting."
  [[ax0 atop ax1 abot] [bx0 btop bx1 bbot]]
  (and (< ax0 bx1) (< bx0 ax1)
       (< atop bbot) (< btop abot)))

(defn contains?
  "True when `inner` lies entirely within `outer` (boundaries inclusive)."
  [[ox0 otop ox1 obot] [ix0 itop ix1 ibot]]
  (and (<= ox0 ix0) (<= ix1 ox1)
       (<= otop itop) (<= ibot obot)))

(defn intersection
  "Overlap bbox of `a` and `b`, or nil when they do not overlap with positive area."
  [[ax0 atop ax1 abot] [bx0 btop bx1 bbot]]
  (let [x0 (max ax0 bx0)
        top (max atop btop)
        x1 (min ax1 bx1)
        bottom (min abot bbot)]
    (when (and (< x0 x1) (< top bottom))
      [x0 top x1 bottom])))

(defn within?
  "True when point `[x y]` falls inside `bbox` (boundaries inclusive)."
  [[x0 top x1 bottom] [x y]]
  (and (<= x0 x x1) (<= top y bottom)))
