;
; Copyright (C) 2015 Colin Smith.
; This work is based on the Scmutils system of MIT/GNU Scheme.
;
; This is free software;  you can redistribute it and/or modify
; it under the terms of the GNU General Public License as published by
; the Free Software Foundation; either version 3 of the License, or (at
; your option) any later version.
;
; This software is distributed in the hope that it will be useful, but
; WITHOUT ANY WARRANTY; without even the implied warranty of
; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
; General Public License for more details.
;
; You should have received a copy of the GNU General Public License
; along with this code; if not, see <http://www.gnu.org/licenses/>.
;

(ns net.littleredcomputer.math.structure
  (:import (clojure.lang Sequential Seqable IFn ILookup IPersistentCollection AFn Associative Counted PersistentVector))
  (:require [net.littleredcomputer.math
             [value :as v]
             [generic :as g]]))

(declare make)
(declare make-identity-like)

(def ^:private orientation->symbol {::up 'up ::down 'down})

(deftype Struct [orientation v]
  v/Value
  (nullity? [_] (every? g/zero? v))
  (unity? [_] false)
  (zero-like [_] (make orientation (-> v count (repeat 0))))
  (exact? [_] (every? v/exact? v))
  (numerical? [_] false)
  (freeze [_] `(~(orientation orientation->symbol) ~@(map v/freeze v)))
  (arity [_] (v/joint-arity (map v/arity v)))
  (kind [_] orientation)
  Object
  (equals [_ b]
    (and (instance? Struct b)
         (let [^Struct bs b]
           (= orientation (.orientation bs))
          (= v (.v bs)))))
  (toString [_] (str (cons (orientation orientation->symbol) v)))
  Sequential
  ;; Counted
  ;; (count [_] (count v))
  Seqable
  (seq [_] (seq v))
  IPersistentCollection
  (count [_] (count v))
  (cons [s _] s) ;; XXX
  (empty [_] (Struct. orientation []))
  (equiv [s t] (and (= orientation (.orientation t))
                    (= v (.v t))))
  Associative
  ;; (containsKey [_ i] (< i (count v)))
  ;; (entryAt [_ i] (v i))
  (assoc [s k v] s)
  ILookup
  (valAt [_ key] (get v key))
  (valAt [_ key default] (get v key default))
  IFn
  (invoke [_ x]
    (Struct. orientation (mapv #(% x) v)))
  (applyTo [s xs]
    (AFn/applyToHelper s xs))
  )

(defn- make
  [orientation xs]
  (Struct. orientation (into [] xs)))

(defn up [& xs]
  (make ::up xs))

(defn down [& xs]
  (make ::down xs))

(defn structure? [s]
  (sequential? s))

(defn- up? [^Struct s]
  (or (vector? s)
      (list? s)
      (and (instance? Struct s) (= (.orientation s) ::up))))

(def ^:private opposite-orientation {::up ::down ::down ::up})

(defn opposite [s xs]
  (make (if (up? s) ::down ::up) xs))

(defn orientation [^Struct s]
  (if (instance? Struct s) (.orientation s) ::up))

(defn same [s xs]
  (make (orientation s) xs))

(defn- elementwise [op s t]
  (if (= (count s) (count t))
    (Struct. (orientation s) (mapv op s t))
    (throw (ArithmeticException.
            (str op " provided arguments of differing length")))))

(defn mapr
  "Return a structure with the same shape as s but with f applied to
  each primitive (that is, not structural) component."
  [f ^Struct s]
  (cond (instance? Struct s) (Struct. (.orientation s) (mapv #(mapr f %) (.v s)))
        (vector? s) (mapv #(mapr f %) s)
        :else (f s)))

(defn structure-assoc-in
  "Like assoc-in, but works for structures. At this writing we're not
  sure if we want to overwrite the stock definition of assoc-in to
  something that would fall through for standard clojure data types"
  [^Struct s keys value]
  (if (empty? keys) value
      (let [w (.v s)
            k0 (first keys)]
        (make (.orientation s)
              (assoc w k0 (structure-assoc-in (nth w k0) (next keys) value))))))

(defn- compatible-for-contraction?
  "True if s and t are equal in length but opposite in orientation"
  [s t]
  (and (= (count s) (count t))
       (not= (orientation s) (orientation t))))

(defn- inner-product
  [s t]
  (reduce g/+ 0 (map g/* s t)))

(defn- outer-product
  [s t]
  (same t (map #(g/* s %) t)))

(defn square?
  "Returns [dimension major-orientation minor-orientation] if s is a square structure, else nil."
  [s]
  (let [major-size (count s)
        major-orientation (orientation s)
        minor-sizes (map #(if (structure? %) (count %) 1) s)
        minor-orientations (map orientation s)
        first-minor-orientation (first minor-orientations)]
    (if (and (every? #(= major-size %) minor-sizes)
             (every? #(= first-minor-orientation %) (rest minor-orientations)))
      [major-size major-orientation first-minor-orientation])))

(defn transpose
  "The transpose of a structure s is just the same structure with the
  outermost orientation reversed."
  [s]
  (opposite s (seq s)))

(defn m:transpose
  "Transpose the structure s like a matrix. The result will have
  the same orientation at all levels."
  [^Struct s]
  (let [d1 (count s)
        d2s (map count (.v s))
        ragged (not (apply = d2s))
        o2s (map (fn [^Struct t] (.orientation t)) s)
        weird (not (apply = o2s))
        o2 (first o2s)]
    (when (or ragged weird)
      (prn "can't transpose" ragged weird d2s o2s)
      (throw (IllegalArgumentException.
              "a structure must be rectangular if it is to be transposed.")))
    (same s (for [i (range (first d2s))]
              (Struct. o2 (vec (for [j (range d1)]
                                 (get (get s j) i))))))))

(defn- without-index
  "The structure s with element index i removed"
  [^Struct s i]
  (same s (into
            (subvec (.v s) 0 i)
            (subvec (.v s) (inc i)))))

(defn substructure-without
  "The structure with the i'th component removed at the top level and the j'th
  component removed from each of the structures at the next level."
  [s i j]
  (let [a (map #(without-index % j) s)
        b (concat (take i a) (drop (inc i) a))]
    (same s b)))

(declare determinant)

(defn- make-square
  "Make a square structure of size n by n with outer and inner orientations as given,
  whose elements are (f i j), where i and j range from [0..n)"
  [n outer-orientation inner-orientation f]
  (make outer-orientation
        (for [i (range n)]
          (make inner-orientation
                (for [j (range n)]
                  (f i j))))))

(defn cofactors
  "Computes the matrix of cofactors of the given structure with the
  same shape, if s is square."
  [s]
  (let [[d outer-orientation inner-orientation] (square? s)
        checkerboard-negate (fn [s i j] (if (even? (+ i j)) s (g/negate s)))]
    (cond (< d 2) s
          (= d 2) (let [[[a b] [c d]] s]
                    (make outer-orientation
                          [(make inner-orientation [d (g/negate c)])
                           (make inner-orientation [(g/negate b) a])]))
          :else (make-square d outer-orientation inner-orientation
                             #(-> s (substructure-without %1 %2) determinant (checkerboard-negate %1 %2))))))

(defn determinant
  "Computes the determinant of s, which must have square shape. Generic
  operations are used, so this works on symbolic square structures."
  [s]
  (let [[d _ _] (square? s)]
    (when-not d (throw (IllegalArgumentException. "not square")))
    (cond (= d 0) (throw (IllegalArgumentException. "zero size matrix has no determinant"))
          (= d 1) (first (first s))
          (= d 2) (let [[[a b] [c d]] s]
                    (g/- (g/* a d) (g/* b c)))
          :else (reduce g/+
                        (map g/*
                             (cycle [1 -1])
                             (first s)
                             (for [i (range d)] (determinant (substructure-without s 0 i))))))))

(defn- invert
  "Computes the inverse of s viewed as a square matrix."
  [s]
  (let [[d o1 o2] (square? s)]
    (when-not d (throw (IllegalArgumentException. "not square")))
    (cond (= d 0) (throw (IllegalArgumentException. "zero size matrix has no inverse"))
          (= d 1) (make o1 [(make o2 [(g/invert (first (first s)))])])
          :else (let [C (cofactors s)
                      Δ (reduce g/+ (map g/* (first s) (first C)))
                      outer-orientation (if (= o1 o2) (opposite-orientation o1) o1)
                      inner-orientation (if (= o1 o2) (opposite-orientation o2) o2)]
                  (make-square d outer-orientation inner-orientation
                               #(-> C (nth %2) (nth %1) (g/divide Δ)))))))

(defn- cross-product
  [s t]
  (when (or (not= (count s) 3) (not= (count t) 3))
    (throw (IllegalArgumentException. "cross product only works on two elements of ^3")))
  (let [[s0 s1 s2] s [t0 t1 t2] t]
    (up (g/- (g/* s1 t2) (g/* t1 s2)) (g/- (g/* s2 t0) (g/* s0 t2)) (g/- (g/* s0 t1) (g/* t0 s1)))))

(defn- make-identity-like
  "Produce a multiplicative identity with the same shape as the square structure s."
  [s]
  (let [[d outer-orientation inner-orientation] (square? s)]
    (when-not d (throw (IllegalArgumentException. "cannot make non-square identity structure")))
    (make-square d outer-orientation inner-orientation #(if (= %1 %2) 1 0))))

(defn characteristic-polynomial
  "Compute the characteristic polynomial of the square structure s, evaluated
  at x. Typically x will be a dummy variable, but if you wanted to get the
  value of the characteristic polynomial at some particular point, you could
  supply a different expression."
  [s x]
  (determinant (g/- (g/* x (make-identity-like s)) s)))

(defn- mul
  "If s and t are compatible for contraction, returns their inner product,
  else their outer product."
  [s t]
  (if (compatible-for-contraction? s t)
    (inner-product s t)
    (outer-product s t)))

;; hmmm. why not do the repeated-squaring trick here?
;; perhaps structures are not typically raised to high
;; exponents.

(defn- expt [s n]
  "Raise the structure s to the nth power."
  (cond (= n 1) s
        (> n 1) (g/* s (g/expt s (- n 1)))
        :else (throw (ArithmeticException. (str "Cannot: " `(expt ~s ~n))))))

(defn- matrix->structure "TODO: implement" [m] m)

(defn seq->
  "Convert a sequence (typically, of function arguments) to an up-structure.
  GJS: Any matrix in the argument list wants to be converted to a row of
  columns (TODO: this is not implemented yet)"
  [s]
  (Struct. ::up (mapv matrix->structure s)))

(defn unflatten
  [values struct]
  (letfn [(u [values struct]
            (if (structure? struct)
              (let [[values' struct']
                    (reduce (fn [[values struct] element]
                              (let [[values' struct'] (u values element)]
                                [values' (conj struct struct')]))
                            [values []]
                            struct)]
                [values' (same struct struct')])
              [(rest values) (first values)]))]
    (second (u values struct))))

;; 1 ]=> (structure->access-chains (up (down 3 4) (down 4 5) (up (up 1 2) 4) 4 (up 7 9)))
;; #|
;; (up (down (0 0) (0 1)) (down (1 0) (1 1)) (up (up (2 0 0) (2 0 1)) (2 1)) (3) (up (4 0) (4 1)))
;; |#

;; (defn structure->access-chains
;;   [struct]
;;   (letfn [(u [chain struct]
;;             (if (structure? struct)
;;               ;; need some magic with map-indexed here
;;               (let [chain' struct'])))]))

;; also need:

(defmethod g/add [::down ::down] [a b] (elementwise g/+ a b))
(defmethod g/add [::up ::up] [a b] (elementwise g/+ a b))
(defmethod g/sub [::down ::down] [a b] (elementwise g/- a b))
(defmethod g/sub [::up ::up] [a b] (elementwise g/- a b))
(defmethod g/cross-product [::up ::up] [a b] (cross-product a b))
(derive ::up ::structure)
(derive ::down ::structure)
(derive PersistentVector ::up)
(defmethod g/mul [::structure ::structure] [a b] (mul a b))
(defmethod g/mul [::structure :net.littleredcomputer.math.expression/numerical-expression] [a b] (outer-product b a))
(defmethod g/mul [:net.littleredcomputer.math.expression/numerical-expression ::structure] [a b] (outer-product a b))
(defmethod g/mul [::structure :net.littleredcomputer.math.calculus.derivative/differential] [a b] (outer-product b a))
(defmethod g/mul [:net.littleredcomputer.math.calculus.derivative/differential ::structure] [a b] (outer-product a b))
(defmethod g/div [::structure :net.littleredcomputer.math.expression/numerical-expression] [a b] (outer-product (g/invert b) a))
(defmethod g/div [::structure ::structure] [a b] (mul (invert b) a))
(defmethod g/expt [::structure Long] [a b] (expt a b))
(defmethod g/negate ::structure [a] (same a (mapv g/negate a)))
(defmethod g/invert ::structure [a] (invert a))
(defmethod g/square ::structure [a] (inner-product a a))
(defmethod g/cube ::structure [a] (mul a (mul a a)))
(defmethod g/simplify ::structure [a] (->> a (mapr g/simplify) v/freeze))
