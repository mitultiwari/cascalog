 ;    Copyright 2010 Nathan Marz
 ; 
 ;    This program is free software: you can redistribute it and/or modify
 ;    it under the terms of the GNU General Public License as published by
 ;    the Free Software Foundation, either version 3 of the License, or
 ;    (at your option) any later version.
 ; 
 ;    This program is distributed in the hope that it will be useful,
 ;    but WITHOUT ANY WARRANTY; without even the implied warranty of
 ;    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 ;    GNU General Public License for more details.
 ; 
 ;    You should have received a copy of the GNU General Public License
 ;    along with this program.  If not, see <http://www.gnu.org/licenses/>.

(ns cascalog.ops
  (:refer-clojure :exclude [count min max comp juxt])
  (:use [cascalog ops-impl api util])
  (:use [clojure.contrib.def :only [defnk]])
  (:require [cascalog [vars :as v]]))

;; Operation composition functions

(defn negate [op]
  (<- [:<< !invars :> !true?]
      (op :<< !invars :> !curr?)
      (not !curr? :> !true?)
      ))

(defn all [& ops]
  (logical-comp ops #'bool-and))

(defn any [& ops]
  (logical-comp ops #'bool-or))

(defn comp [& ops]
  (let [ops (reverse ops)
        intvars (map vector (v/gen-nullable-vars (dec (clojure.core/count ops))))
        intvars (vec (cons "!invars" intvars))
        allvars (conj intvars ["!result"])
        varpairs (partition 2 1 allvars)
        ]
    (construct
     [:<< "!invars" :> "!result"]
     (map (fn [o [invars outvars]] [o :<< invars :>> outvars]) ops varpairs)
     )))

(defn juxt [& ops]
  (let [outvars (v/gen-nullable-vars (clojure.core/count ops))]
    (construct
     [:<< "!invars" :>> outvars]
     (map (fn [o v] [o :<< "!invars" :> v]) ops outvars))
    ))

(defn each [op]
  (predmacro [invars outvars]
    {:pre [(or (= 0 (clojure.core/count outvars))
               (= (clojure.core/count invars) (clojure.core/count outvars)))]}
    (if (empty? outvars)
      (for [i invars]
        [op i])
      (map
        (fn [i v]
          [op i :> v] )
        invars
        outvars ))))


;; Operations to use within queries

(defmapop [re-parse [pattern]] [str]
  (re-seq pattern str))

(defparallelagg count :init-var #'one
                      :combine-var #'+)

(def sum (each sum-parallel))

(def min (each min-parallel))

(def max (each max-parallel))

(def !count (each !count-parallel))

(defparallelbuf limit :hof? true
                      :init-hof-var #'limit-init
                      :combine-hof-var #'limit-combine
                      :extract-hof-var #'limit-extract
                      :num-intermediate-vars-fn (fn [infields outfields] (clojure.core/count infields))
                      :buffer-hof-var #'limit-buffer )

(def limit-rank (merge limit {:buffer-hof-var #'limit-rank-buffer} ))

(def avg
  (<- [!v :> !avg]
    (count !c) (sum !v :> !s) (div !s !c :> !avg)))

(def distinct-count
  (<- [:<< !invars :> !c]
    (:sort :<< !invars) (distinct-count-agg :<< !invars :> !c)))

;; Common patterns

(defnk first-n
  "Returns a subquery getting the first n elements from sq it
  finds. Can pass in sorting arguments."
  [gen n :reverse false :sort nil]
  (let [num-fields (num-out-fields gen)
        in-vars  (v/gen-nullable-vars num-fields)
        out-vars (v/gen-nullable-vars num-fields)
        sort-set (if sort (-> sort collectify set) #{})
        sort-vars (if sort
                    (mapcat (fn [f v] (if (sort-set f) [v]))
                            (get-out-fields gen)
                            in-vars))]
    (<- out-vars
        (gen :>> in-vars)
        (:sort :<< sort-vars)
        (:reverse reverse)
        (limit [n] :<< in-vars :>> out-vars))))

;; Helpers to use within ops

(defmacro with-timeout [[ms] & body]
  `(let [^java.util.concurrent.Future f# (future ~@body)]
     (try
       (.get f# ~ms java.util.concurrent.TimeUnit/MILLISECONDS)
     (catch java.util.concurrent.TimeoutException e#
       (.cancel f# true)
       nil
       ))))
