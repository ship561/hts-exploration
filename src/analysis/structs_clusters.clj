(ns analysis.structs-clusters
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.core.reducers :as r]
            [clojure.contrib.io :as io]
            [clojure.contrib.string :as str]
            [clojure.pprint :as pp])
  (:use edu.bc.utils.fold-ops
        edu.bc.utils.probs-stats
        hts-exploration.db-queries
        hts-exploration.globals
        hts-exploration.utils))



(defn remove-empty-structs 
  "Only keep the centroid structures which have structure"
  [db coll]
  (filter (fn [[sid vs]] 
            (->> ["select structure from selex_structs where struct_id=?" sid]
                 (jdbc/query db )
                 first :structure
                 (re-find #"\(" )))
          coll)) ;must contain some structure

(defn pairwise-distance 
  "Given a collection of strings, finds the pairwise distance between
  all of them. Returns a square 'matrix' of the results"
  [distfn coll]
  (for [i coll] 
    (vec (for [j coll] (distfn i j)))))

(defn get-seqs-centroid [round]
  (->> (round-all-usable-seqs-centroid round) sql-query
       (reduce (fn [M {:keys [id hit_seq cs sid]}]
                 (let [sq (re-find #"TGCGTAACGTACACT(.*)ATGTCTCTAAGTACT" hit_seq)
                       k sid
                       cur-val (get M k [])]
                   (if (and sid sq (<= 28 cs 32)) ;standard sequence
                     (assoc M k (conj cur-val [id (second sq)]))
                     M)))
               {})))

(defn filter-doubletons
  "Specific to getting doubletons which are in the data from some
  variant of get-seqs."
  
  [get-sqs] (into {} (filter #(> (->> % second count) 1) get-sqs)))

(def common-seqs (intersect-maps (get-seqs 10) (get-seqs 11)))

(def common-structs (intersect-maps (get-seqs-centroid 10) (get-seqs-centroid 11)))

(binding [pp/*print-right-margin* 100]
  (let [dstruct-both common-structs
        dseq-both common-seqs]
    (prn :count :dstruct (count dstruct-both) (take 2 dstruct-both) :dseq (count dseq-both) (take 2 dseq-both))
    (jdbc/with-db-connection [db mysql-ds]
      (->> dstruct-both
           (remove-empty-structs db)
           (map #(vector (first %)
                         (->> % second count)
                         (reduce + (map (fn [k] (count (dseq-both k))) (distinct (mapv second (second %)))))
                         (distinct (mapv second (second %)))
                         ))
           (map #(conj % (-> % last count) ))
           #_(map #(conj % (pairwise-distance (nth % 3))))
           (sort-by #(nth % 1) >)
           #_(take 5)
           #_clojure.pprint/pprint
           (map last)
           (reduce +)))))
