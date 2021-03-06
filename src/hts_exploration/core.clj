(ns hts-exploration.core
  (:require [edu.bc.fs :as fs]
            [clojure.contrib.io :as io]
            [clojure.contrib.string :as str]
            [clojure.core.reducers :as r]
            [clojure.java.jdbc :as jdbc]
            [clojure.math.numeric-tower :as math]
            [clojure.pprint :as pp]
            [iota]
            [foldable-seq.core :as fseq]
            gibbs-sampler
            [smith-waterman :as sw]
            )
  (:use edu.bc.utils.fold-ops
        edu.bc.bio.seq-utils
        edu.bc.bio.sequtils.files
        edu.bc.utils.probs-stats
        edu.bc.utils
        hts-exploration.db-queries
        hts-exploration.globals
        hts-exploration.utils))

(set! *print-length* 10)
*print-length*

(defn struct-sim [n ref-structure]
  (->> data-r1
       (sample-data n )
       get-name-seq
       (add-structure 10)
       (dist-filter 10 0.2 ref-structure)
       (get-regions (- (count prime5-const) 3)
                    (+ 30 (count prime5-const) ;var+const region
                       (count prime3-const)))))

(defn motif-found [sim-seqs]
  (future
    (as-> (range 10) r                 ;multiple random gibbs samples
          (pmap (fn [_]
                  (gibbs-sampler/-main (->> sim-seqs
                                            (map second)
                                            (remove #(re-find #"[^ACGTU]" %))
                                            (mapv #(->> (subs % 3)
                                                        (str/take 30))))))
                r)
          (remove nil? r)
          (last r)                     ;pick a random gibbs sample
          (second r) ;last state
          (r :baseprob))))

(def struct-sim-bob
  ^{:doc "Pulling sequences which have simlar structure to bob"}
  (time 
   (let [bob-st (fold bob)]
     (struct-sim 1000000 bob-st))))

(def motif-found-bob
  @(motif-found struct-sim-bob))

(def struct-sim-ref-seq-firmicute
  ^{:doc "Pulling sequences which have simlar structure to Gk ref-seq"}
  (time 
   (let [firm-st (fold ref-seq-firmicute)]
     (struct-sim firm-st))))

(def struct-sim-ref-seq-ec
  ^{:doc "Pulling sequences which have simlar structure to Ec ref-seq"}
  (time 
   (let [ec-st (fold ref-seq)]
     (struct-sim 1000000 ec-st))))

(def motif-found-ref-seq-ec @(motif-found struct-sim-ref-seq-ec))





(defn  most-common-struct
  "Sample sequences from the fasta file and fold them. Then use this
  sequence set to compare against a sampling of 1M reads. This should
  find the most common structures because the sampled sequences will
  be from common structures. The data comes from reading in a file
  using iota/vec"

  [outfile data]
  (future
    (let [samples1 (similarity-remove (sample-n 200 data))
          samples2 (sample-n 1e6 data)
          _ (prn :samples (count samples1) (count samples2))
          xxx (r/reduce
               (fn [V [nm sample sample-st]]
                 (let [len (count sample-st)
                       dist-map (reduce (fn [M [nm d]]
                                          (->> (relative-dist d len)
                                               (assoc M nm)))
                                        {}
                                        (calc-dist 30 sample-st samples2))
                       dist-distribution (frequencies (vals dist-map))
                       sim (vec (filter (fn [[nm s]]
                                          (< (dist-map nm) 0.2))
                                        samples2))]
                   (conj V [nm sample sample-st dist-distribution sim])))
               [] samples1) ]
      (io/with-out-writer outfile
        (prn :samples (count samples1) (count samples2))
        (doseq [[nm sampled-seq sampled-struct distribution similar-st] xxx]
          (prn nm)
          (prn sampled-seq) 
          (prn sampled-struct) 
          (prn distribution)
          (prn similar-st))))))

(comment (most-common-struct "/home/peis/data/foo.test" (read-fasta-csv data-r1))) ;typical case

(defn read-common-struct-hits [f]
  (let [f (rest (io/read-lines f))]
     (reduce (fn [M x]
               (let [[name sample-seq sample-struct distribution hits]
                     (map read-string x)
                     hits (distinct hits)
                     key-vec [:sample-seq :sample-struct :distribution :hits]]
                 (assoc M name
                        (->> [sample-seq sample-struct distribution hits]
                             (interleave key-vec )
                             (apply assoc {})))))
               {} (partition 5 f))))

(defn get-centroid

  [hits]
  (prn :centroid)
  (structs->centroid
   (r/fold 50
           (fn ([] [])
             ([l r] (concat l r)))
           (fn ([] [])
             ([V s]
                (conj V (fold s))))
           hits)))

(defn determine-clusters
  "data comes from reading output from common-struct-hits"
  
  [data]
  (r/reduce (fn [M [k sample-seq hits]]
              (prn :seq k :count (count hits) :sample (type hits))
              (let [structs (map last hits)
                    vector-structs (map struct->vector structs)]
                (assoc M k {:sample-seq sample-seq
                            :n (count hits)
                            :hit-names (map first hits)
                            :hit-seqs (map second hits)
                            :hit-structs structs
                            :centroid (structs->centroid structs)
                            :vector-structs vector-structs})))
            {}
            (->> data
                 (r/map #(vector (first %)
                                 (-> % second :sample-seq)
                                 (-> % second :hits vec)))
                 (r/remove #(zero? (-> % last count))))))

(defn positional-H
  "after finding the most common seqs according to structure
  simlarity, we look at the entropy per position to see if there are
  any strongly conserved residues. (ie clusters=foo-clusters)"

  [clusters]
    (doseq [k (keys clusters)
            :let [m (clusters k )]
            :when (> (m :n) 5000)] 
      (pp/pprint
       (let [{centroid :centroid 
              sample-seq :sample-seq
              hit-seqs :hit-seqs} (select-keys m [:centroid :sample-seq :hit-seqs])
              most-freq (reduce (fn [max-entry entry]
                                  (let [cur-max (second max-entry)
                                        cur-cnt (second entry)]
                                    (if (> cur-cnt cur-max)
                                      entry
                                      max-entry)))
                                [0 0] (frequencies (map count hit-seqs)))
              hit-seqs (filter #(= (first most-freq) (count %)) hit-seqs)
              hit-seqs-T (->> (edu.bc.utils/transpose hit-seqs)
                              (drop 13)
                              (take 40))
              H (map-indexed (fn [i coll]
                               [(+ 14 i)
                                (round 3 (entropy (probs 1 coll)))
                                (repeatedly 10 #(markov-step (probs 1 coll)));(probs 1 coll)
                                ])
                             hit-seqs-T)]
         [k centroid sample-seq most-freq (count hit-seqs) H]))))
(comment
  (def foo-test (read-common-struct-hits "/home/peis/data/foo.test"))
  (def foo-clusters (determine-clusters foo-test))

  ;;after finding the most common seqs according to structure
  ;;simlarity, we look at the entropy per position to see if there are
  ;;any strongly conserved residues
  
  
  ;;discovery of TAATACGAC as a common motif in sequences with
  ;;deletions. To identify how common this or similar motifs are, use
  ;;the following:
  (let [f (iota/vec S15-round10-fastq-qual-filter-csv)
        motif "TAATACGAC"
        mutant-motif (as-> motif m
                           (mutant-neighbor 3 m)
                           (conj m motif)
                           (map re-pattern m))
        contain-motif? (fn [s]
                         (->> (for [re mutant-motif]
                                (re-find re s))
                              (not-every? nil?)))
        usable (->> (read-fasta-csv f)
                    (r/map second)
                    (r/map #(re-find forward-re %))
                    (r/remove nil?)
                    (into []))
        data (map contain-motif? usable)]
    [(frequencies (map count (filter #(re-find #"TAATACGAC" %) usable)))
     (->> (map #(when %1 (count %2)) data usable)
          (remove nil?)
          frequencies)
     (count (filter true? data))
     (count usable)])

  ;;Counts of finding similar seqs to the motif according to the
  ;;length of the sequence
  [{96 558, 97 819, 98 4102, 99 5707, 79 52528, 80 20062, 81 13060,
    82 10528, 83 3984, 84 3139, 85 3266, 86 4847, 87 2614, 88 1403,
    89 1397, 90 934, 91 1492, 92 712, 93 1047, 94 2989, 95 541}
   {96 601, 97 852, 98 4218, 99 5815, 79 54906, 80 22877, 81 16961,
    82 15359, 83 10791, 84 12939, 85 15941, 86 22035, 87 34923,
    88 8889, 89 3388, 90 1600, 91 1838, 92 920, 93 1183, 94 3151, 95 596}
   239783
   631958]

  (defn- mutant-motif [n motif]
    (as-> motif m
          (mutant-neighbor n m)
          (conj m motif)
          (map re-pattern m)))
  
  (defn count-motifs

    [motif f]
    (let [;f S15-round10-qual-filter-csv
          motif (re-pattern motif) ;GAGC
          usable (map second (get-usable f))
          data (frequencies (map count (filter #(re-find motif %) usable)))
          total-nt (reduce #(+ %1 (- (count %2) 3)) 0 usable)
          cnt-hits (reduce + (vals data) )
          expectation (/ total-nt 256)
          zscore (/ (- cnt-hits expectation)
                    (Math/sqrt (* total-nt (/ 256) (/ 255 256))))
          cnt-usable (count usable)]
      [:data data
       :total-nt total-nt
       :cnt-hits cnt-hits
       :expectation (double expectation)
       :zscore (double zscore)
       :cnt-usable cnt-usable]))


  ;;pick favorite parasite seqs
  (let [f S15-round11-qual-filter-csv
        usable (->> (get-usable f)
                    (r/map second) 
                    (r/filter parasite?)
                    (r/filter #(= (count %) 79))
                    (into []) )
        p-table (->> usable 
                     transpose
                     (map (fn [coll]
                            (probs 1 coll))))
        make-seq (fn [] (apply str (map #(markov-step %) p-table)))
        seq-set (repeatedly 100 make-seq)
        p-seq-set (repeatedly 100 #(rand-nth usable))
        ]
    (pp/pprint
     [(->> seq-set
           (pxmap (fn [s]
                    [s (mean (map #(hamming-dist s %) usable))])
                  30)
           (sort-by second )
           (take 10))
      (->> p-seq-set
           (pxmap (fn [s]
                    [s (mean (map #(hamming-dist s %) usable))])
                  30)
           (sort-by second )
           (take 10))
      (->> usable
           rfrequencies
           (sort-by second > )
           (take 10))]))
  
  )

(->> foo-test
     determine-clusters
     (filter #(> (->> % (drop 2) first) 10000))
     (map butlast)
     pp/pprint)

(io/with-out-writer "/home/peis/data/foo.csv"
  (doseq [i foo-clusters
          j (last i)]
    (println (str/join "," (concat (butlast i) j)))))


(time
 (let [data (->> (get-usable S15-round11-qual-filter-csv)
                 (map-indexed (fn [i [nm s]] 
                                [(str/take 19 (str i nm)) s]))
                 (into {}))
       clusters (->> (io/read-lines "/home/peis/data/s15-round11-qual-filter.cdhit.clusters")
                     (map #(str/split #"\t" %)))
       [header clusters] (split-at 1 clusters)
       header (map keyword (first header))
       xxx (second 
            (reduce (fn [[clstr-rep M] clstr]
                      (let [c (zipmap header clstr)
                            k (if (= "1" (c :clstr_rep)) (c :id) clstr-rep)
                            new-entry {:seq (data (c :id)) 
                                       :len (Integer/parseInt (c :length))
                                       :id (c :id) 
                                       :clstr-rep (Integer/parseInt (c :clstr_rep))}
                            old-entry (get M [(c :clstr) k] [])]
                        [k (assoc M [(c :clstr) k] (conj old-entry new-entry))]))
                    ["" {}] clusters))
       yyy 0 #_(r/reduce (fn [M [k vs]]
                           (prn "working on entry:" k (count vs))
                           (let [s1 (data (second k))
                                 new-entries (r/fold 100 concat
                                                     (fn ([] [])
                                                       ([V m]
                                                          (let [s2 (m :seq)
                                                                s2-aligned (last (sw/sw s1 s2 :global true))]
                                                            (conj V (assoc m :seq s2-aligned)))))
                                                     vs)]
                             (assoc M k new-entries)))
                         {} (r/take 2 (r/filter #(>= 2000 (-> % second count) 1000) xxx)))] 
   (prn :header header) 
   (pp/pprint (take 3 clusters))
   (let [c (filter #(>= (-> % second count) 10000) xxx)
         outfiles (repeatedly (count c) fs/tempfile)
         out (pmap (fn [outfile [k vs]] 
                     [outfile k (distinct-hits (map (fn [m] [(m :id) (m :seq)]) vs))])
                   outfiles c)]
     (doseq [[outfile k vs] out]
       (write-fasta outfile vs))
     (prn outfiles)
     (map (fn [[_ k vs]] [k (count vs)]) out))))

(dorun
 (map (fn [cluster-num f]
        (fs/copy f (str "/home/peis/data/s15-round11-cdhit-cluster-" (ffirst cluster-num) ".fna")))
      '([["20" "346150@HWI-ST1129:5"] 10513] [["3" "60035@HWI-ST1129:52"] 100543] [["7" "109128@HWI-ST1129:5"] 23402]
          [["13" "161701@HWI-ST1129:5"] 12255] [["39" "29626@HWI-ST1129:52"] 11058] [["1" "4103@HWI-ST1129:529"] 104857]
            [["0" "7@HWI-ST1129:529:H9"] 19095] [["10" "123410@HWI-ST1129:5"] 21696] [["5" "67664@HWI-ST1129:52"] 8451]
              [["2" "12981@HWI-ST1129:52"] 7681] [["6" "91594@HWI-ST1129:52"] 7838] [["8" "113894@HWI-ST1129:5"] 48164]) 
      '("/tmp/-fs-6029566329720862683" "/tmp/-fs-4491974394548404381" "/tmp/-fs-2679861326937564582" "/tmp/-fs-4394718262706558267"
        "/tmp/-fs-8209872026325559064" "/tmp/-fs-5751431934686883617" "/tmp/-fs-5742671127339322398" "/tmp/-fs-1653243009906879314"
        "/tmp/-fs-6262556634941290504" "/tmp/-fs-6007613895371995308" "/tmp/-fs-6286049487709373225" "/tmp/-fs-1124450394497733987")))

;;;generate random sequences

(defn chi-sq [obs expected]
  (let [S (clojure.set/union (set (keys obs)) (set (keys expected)))]
    (sum
     (map (fn [k] (let [obs (get obs k 0)
                       exp (get expected k 0)]
                   (/ (Math/pow (- obs exp) 2)
                      exp)))
          S))))

(defn kmer-freq [size inseqs]
  (->> inseqs
       (r/fold (fn ([] {})
                 ([l r] (merge-with + l r)))
               (fn ([] {})
                 ([M s]
                    (merge-with + M (freqn size s)))))))

(defn kmer-probs [m]
  (let [tot (sum (vals m))]
    (reduce (fn [M [k v]]
                (assoc M k (/ v tot)))
              {} m)))

(doseq [infile (fs/re-directory-files "/home/peis/data" #"cluster*.fna")]
                          (let [f (->> (iota/seq infile)
                                       (partition-all 2) 
                                       (r/map second) 
                                       (into []))
                                kmer4 (kmer-freq f)
                                background (->> (rand-sequence (count f)) 
                                                vec
                                                (kmer-freq 4))
                                pback (kmer-probs background)]
                            (prn infile)
                            (->> (clojure.set/union (set (keys kmer4)) 
                                                    (set (keys pback)))
                                 (reduce (fn [M x]
                                           (->> (- (kmer4 x) (background x))
                                                (assoc M x )))
                                         {}) 
                                 (sort-by val >)
                                 (take 5) pp/pprint)
                            (prn :chisq (chi-sq kmer4 background))
                            (prn :test (chi-sq background background))
                            (prn (jensen-shannon (kmer-probs kmer4) pback))))

(let [f (->> (iota/seq "/home/peis/data/s15-round11-cdhit-cluster-17-cmsearch.fna" )
             (partition-all 2) 
             (r/map second) 
             (into []))
      kmer4 (kmer-freq 4 f)
      tot (sum (vals kmer4))
      background (->> (get-usable S15-round11-qual-filter-csv)
                      parasite-remove
                      (r/map second)
                      (kmer-freq 4)
                      kmer-probs
                      (reduce (fn [M [kmer pr]]
                                (assoc M kmer (* pr tot)))
                              {} ))]
  (->> (clojure.set/union (set (keys kmer4)) 
                          (set (keys background)))
       (reduce (fn [M x]
                 (->> (- (kmer4 x) (background x))
                      double (assoc M x )))
               {}) 
       (sort-by val >)
       pp/pprint)
  (prn :chisq (chi-sq kmer4 background))
  (prn :test (chi-sq background background)))

(let [usable (->> "/home/peis/data/s15-round11-cdhit-cluster-17.fna"
                  io/read-lines
                  (partition-all 2)
                  (map vec)
                  (into {}))
      cmsearch (->> (io/read-lines "/home/peis/data/test.cmsearch.out")
                    (remove #(.startsWith % "#"))
                    (map #(vec (str/split #"\s+" %))))
      keep (reduce (fn [M entry]
                     (let [nm (first entry)
                           qstart (entry 5)
                           qend (entry 6)
                           hstart (Integer/parseInt (entry 7))
                           hend (Integer/parseInt (entry 8))
                           score (Double/parseDouble (entry 14))
                           eval (Double/parseDouble (entry 15))]
                       (if (and (> hend 40)
                                (< eval 1e-7))
                         (assoc M (str ">" nm) [(dec hstart) (dec hend)])
                         M)))
                   {} cmsearch)
      out (->> (keys keep)
               (select-keys usable )
               (mapv (fn [[nm s]]
                       [(str ">a" (re-find #"\d+" nm))
                        (apply subs s (keep nm))]))
               distinct-hits
               (sort-by val))]
  (io/with-out-writer "/home/peis/data/s15-round11-cdhit-cluster-17-cmsearch-2.fna"
    (doseq [[nm s] out]
      (println nm)
      (println s))))

(let [data (->> "/home/peis/data/s15-round11-cdhit-cluster-17-cmsearch-2-052914.sto"
                read-sto 
                :seqs
                (map #(str/replace-re #"\." "" %))
                (map str/upper-case))
      get-energy (fn [s]
                   (->> ((clojure.java.shell/sh "RNAfold"
                                                "--noPS"
                                                :in s )
                         :out)
                        str/split-lines
                        second
                        (str/split #" ")
                        last
                        (re-find #"\-*\d*.\d+")
                        (Double/parseDouble)))
      zscore (fn [s] 
               (let [p (probs 1 s)
                     energies (map get-energy (rand-sequence 1000 (count s) p))]
                 (/ (- (get-energy s) (mean energies))
                    (sd energies))))
      z (pxmap zscore 10 data)]
  [(mean z) (/ -11.22 (mean (map get-energy data)))])
;;[-1.474822732828705 0.8164149910022694]

cmalign --noprob -o s15-round11-cdhit-cluster-17-cmsearch-2.sto test.cm s15-round11-cdhit-cluster-17-cmsearch-2.fna

;;;identification of high indels
(time 
 (let [stmt "SELECT sr.sequence, sr.usable_start, sr.usable_stop 
                                              FROM selex_reads as sr 
                                             WHERE sr.usable=1 
                                               AND sr.strand=1 
                                             LIMIT 100000;"
       st (str prime3-const cds)
       aligned-seqs (->> (get-db-seq stmt)
                         align-to-const)
       _ (prn :count (count aligned-seqs))
       xxx (->> (map #(apply count-indel %) aligned-seqs)
                (map vector aligned-seqs)
                (filter (fn [[_ [_ ins-lens _ del-lens]]]
                          (or (some #(> % 7) ins-lens)
                              (some #(> % 5) del-lens)))))]
   (prn :cntxxx (count xxx))
   (doseq [[i j] xxx
           :let [[s1 s2] i]]
     (prn s1)
     (prn s2)
     (println j))))

(def foo 
  (future 
    (time 
     (let [st prime3-const
           stmt "SELECT sr.sequence, sr.usable_start, sr.usable_stop 
                                              FROM selex_reads as sr 
                                             WHERE sr.usable=1 
                                               AND sr.strand=1;"
           aligned-seqs (-> stmt get-db-seq align-to-const)
           _ (prn :count (count aligned-seqs))
           [mrates lrates overall totals] (->> aligned-seqs count-mutations get-mutation-rates )
           mrates (mapv #(/ % (count st)) mrates)
           overall (/ overall (count st))
           t-matrix (mutation-matrix aligned-seqs)]
       {:overall overall
        :totals totals
        :mrates mrates
        :lrates lrates
        :tmatrix t-matrix}))))


(comment
  ;;calculate the mutation rates for primers or const regions. The
  ;;results are stored in defs
  (def foo [(-> (calc-mutation-rates prime3-const align-to-const) time future)
            (future (time (calc-mutation-rates (str prime5-const cds) align-to-primer)))]))



(def background-seqs (future (doall (repeatedly 1e6 simulate-seq))))

(def background-stats
  (let [cnt (count @background-seqs)
        kmerfn (fn [n inseqs]
                 "The mean kmer count per seq"
                 (->> (map #(freqn n %) inseqs)
                      (apply merge-with +)
                      (reduce-kv (fn [M k v]
                                   (assoc M k (double (/ v (count inseqs)))))
                                 {})))
         ]
    (merge {:n cnt :len-dist (probs 1 (map count @background-seqs))}
           (apply hash-map (interleave [:kmer1 :kmer2 :kmer3 :kmer4 :kmer5 :kmer6 :kmer7]
                                       (pmap #(kmerfn % @background-seqs) (range 1 8)))))))

(let [sqs (->> (sql-query "SELECT SUBSTRING(sr.sequence, sr.usable_start+1, sr.length) as hit_seq FROM selex_reads as sr WHERE sr.usable=1 AND sr.strand=1 ;")
                                     (map :hit_seq))
                            re #"T[CGT][GC][TGA]T" ;#"A[ACT][CG][ACG]A"
                            fun (fn [sqs] (->> sqs
                                               ;(filter #(re-find re %) ) 
                                               (map (fn [s]
                                                      (->> (str-re-pos re s)
                                                           (remove #(<= 15 (first %) 45)))))))
                            normalize (fn [x] (mean (map count x)))]
                        [(normalize (fun sqs)) (normalize (fun @background-seqs))
                         (frequencies (mapcat #(map first %)(fun sqs)))
                         (reduce (fn [M [v k]]
                                   (assoc-in M [k v] (inc (get-in M [k v] 0))))
                                 {} (apply concat (fun sqs)))])

(let [sqs (->> (round-all-usable-seqs 11) sql-query (mapv :hit_seq ))
      kmer (pmap #(kmer-freqn % sqs) [2 3 4 5])
      chisq (fn [Mobs Mexp]
              (as-> (merge-with - Mobs Mexp) x
                    (merge-with (fn [num denom] (/ (sqr num) denom)) x Mexp)))]
  (map #(->> (chisq %1 %2) (sort-by val > ) (take 10))
       kmer
       (map background-stats [:kmer2 :kmer3 :kmer4 :kmer5])))

(def mean-sd ((juxt mean sd) (map #(-> % fold second) (take 10000 @background-seqs))))
(let [sqs (->> (round-all-usable-seqs 11) sql-query (mapv :hit_seq )
                                     (filter #(re-find #"A[ACT][CG][ACG]A.{3,}T[CGT][GC][TGA]T" %)))
                            foo (->> (map #(vector % (count (re-seq #"A[ACT][CG][ACG]A" %))) sqs)
                                     (group-by second)
                                     (sort-by key >)
                                     (map #(->> % second (sort-by count >)));sort on length
                                     )
                            trivial-zscore (fn [s] (/ (- (-> s fold second) (first mean-sd)) (second mean-sd)))
                            chisq (fn [Mobs Mexp]
                                    (as-> (merge-with - Mobs Mexp) x
                                          (merge-with (fn [num denom] (/ (sqr num) denom)) x Mexp)))]
                        (for [xxx foo]
                          (reduce (fn [V data]
                                    ;;data is formated [seq motif-cnt [chisq-kmer2 chisq-kmer3 ... kmer5]]
                                    (if (and (<= (-> data first count) 87)
                                             (>= (-> data last first) 25)
                                             (>= (-> data last second) 83)
                                             (>= (-> data last third) 293))
                                      (let [dist (levenshtein (first data) bob)
                                            z (trivial-zscore (first data))]
                                        (if (<= dist 27) 
                                          (->> (conj V (conj data dist z))
                                               (sort-by last )
                                               (take 3))
                                          V))
                                      V)) []
                                  (map (fn [[s cnt]]
                                         [s cnt 
                                          (map #(apply + (map second (chisq %1 %2)))
                                               (map #(kmer-freqn % [s]) [2 3 4 5])
                                               (map background-stats [:kmer2 :kmer3 :kmer4 :kmer5]))])
                                       xxx))))

["TGCGTAACGTACACTATCGAAAGGAGAATGGAATCGAGCAATCGATCATTCTATCTTAGGATTTAAAAATGTCTCTAAGTACT" 5 ;pick this one
  (20.58710780740946;most deviation from expected dimer count...
   100.35018189484066
   389.83382282396497
   1514.1072691229554) 22]
["TGCGTAACGTACACTGTCAAACAAAACAAAAGACGAAGACGCACTTCATTCAATACTTGGACTCTTAAAATGTCTCTAAGTACT" 4 (25.450923249798738 89.96136254358372 403.93468076920476 1608.6484871597056) 27 -0.5247170019887462]
["TGCGTAACGTACACTCACGAAGAGGACGGAAGACAGATGAAGAGCTCGTTCTATACTTTGGAGATTTAAAATGTCTCTAAGTACT" 3 (29.843538435035306 93.35265995533078 394.51988629953615 1588.9677584109838) 22 -0.8349109319076299]
["TGCGTAACGTACACTAACGATTCGAAAGTGAAAGAAAGAGAAATCATTCTTATACTTTGGAGAGTTAAAATGTCTCTAAGTACT" 2 (27.700575628411325 86.44315532626388 414.62469863857905 1431.9806485636368) 22 -1.004107620954294]
["TGCGTAACGTACACTGGGAGCCGCCCCACCCAGGCGCCCTCGGTGTCATTCTATACGCTTTGGGGTTTTAAAATGTCTCTAAGTACT" 1 (34.66052698680395 96.3740191847706 334.6083922999456 1343.6005201916003) 23 -3.1754651303864803]
["TGCGTAACGTACACTGCGGACAGCGAGACAGATCGAAGGTTTTGATCATTCTATACTTTGGATTTTAAAATGTCTCTAAGTACT" 3955];most frequent in round11
["TGCGTAACGTACACTGCGGGAACAGACCCAACCTACCCTGCGGTGTCTTCTATTACTTTGAGTTTTAAAATGTCTCTAAGTACT" 1085];second
["TGCGTAACGTACACTGTGACGAAGACAAAGACTAGGTTACTGACTTCATTCTATAACTTGGTTTTAAAATGTCTCTAAGTACT" 696];third
"TGCGTAACGTACACTCGATCACACGAGAACATCGGTGATTTGGTGTCAATTCTATATACTTTGGGAGTTTTTAAAATGTCTCTAAGTACT" ;cluster17-108
"TGCGTAACGTACACTACCCAAGACGGCTCTACAGTAAGATAGCCTATCATTCTATATGCTTTGGAGTTTTTAAAATGTCTCTAAGTACT" ;cluster17-157
"TGCGTAACGTACACTGGGCAGATCGCACACACGTCTTGCTCGGTGTCATTCTATATACTTTGGGAGTTTTAAAATGTCTCTAAGTACT" ;cluster7-2147
"TGCGTAACGTACACTCGATCACACGAGAACATCGGTGATTTGGTGTCAATCCTATATACTTTGGGAGTTTTAAAATGTCTCTAAGTACT" ;cluster7-255
"TGCGTAACGTACACTCGGCAAATCCACTAACGGACTACTGGGTGATCATTCAATATACTTTTGGAGTTTTAAAATGTCTCTAAGTACT" ;cluster7-637
"TGCGTAACGTACACTAGGCAAACCGATCCTAACGAATGCTTGGTGTCATTCTATATACCTTGGAGTGTTTTAAAATGTCTCTAAGTACT" ;cluster7-73




(time (let [sqs11 (->> (round-all-usable-seqs 11) sql-query (map #(->> (% :hit_seq) (str/drop 15) (str/butlast 15) )))
                            sqs10 (->> (round-all-usable-seqs 10) sql-query (map #(->> (% :hit_seq) (str/drop 15) (str/butlast 15) )))
                            kmerfn(fn [n inseqs]
                                    (->> (map #(freqn n %) inseqs)
                                         (apply merge-with +)
                                         (reduce-kv (fn [M k v]
                                                      (assoc M k (double (/ v (count inseqs)))))
                                                    {})))
                            n 6]
                        (->> (merge-with / (kmerfn n sqs11)
                                         (kmerfn n sqs10))
                             (sort-by val > )
                             (take 10))))


(defn motif-ratio
  "Finds the ratio of mean motif rate in 2 separate rounds (ra,
  rb). Reports the top 10 hits"

  [n ra rb]
  (time
   (let [get-seqs (fn [round]
                    (->> (round-all-usable-seqs round) sql-query
                         (map :hit_seq )
                         (map #(second
                                (re-find #"TGCGTAACGTACACT(.*)ATGTCTCTAAGTACT" %)))
                         (remove nil?)
                         distinct))
         sqsa (get-seqs ra)
         sqsb (get-seqs rb)
         kmerfn (fn [n inseqs]
                  (->> (map #(probs n %) inseqs)
                       (apply merge-with +)
                       (reduce (fn [M [k v]]
                                 (assoc M k (double (/ v (count inseqs)))))
                               {})))]
     (->> (merge-with / (kmerfn n sqsa)
                      (kmerfn n sqsb))
          (sort-by val > )
          (take 10)))))

;;round 11 vs round 10
(motif-ratio 11 10)
"Elapsed time: 980305.595233 msecs"
(["GTGTCT" 2.06174109652782] ["GCGGTG" 2.0616517848451674] ["CCTGCG" 2.042251885207106] ["CTGCGG" 1.9855611802607638] ["TGCGGT" 1.9823356229670237] ["GCGGAC" 1.966339850816003] ["TTGATC" 1.9568510477633874] ["GTTTTG" 1.9135521648495453] ["TTTGAT" 1.890558758524509] ["TGTCTT" 1.874874631767203])
;;after forcing all seqs to be distinct
(["GGTGTC" 1.6428656241397235] ["CGGGTA" 1.6195919644356367] ["CCGGGT" 1.5897515542640006] ["GCGGTG" 1.5739453570424213] ["TGGGTG" 1.5727188947041364] ["GGGTAT" 1.5714839436422858] ["CGGTGT" 1.5652489716303113] ["TGGTGT" 1.5467607633840224] ["TTGTGG" 1.535166940421112] ["ACTCTT" 1.4982984993353305])

;;round 11 vs round 4
"Elapsed time: 2516346.633465 msecs"
(["TTGGTT" 25.987668137754557] ["TGGTTT" 25.89882809444897] ["TTTGGT" 20.17223022804577] ["CTTGGT" 19.287400487754503] ["CTTCTT" 17.46616736742761] ["GGTTTA" 17.435844990074866] ["TTCTTT" 16.394382056124638] ["TTGGTG" 13.530774021665612] ["TGGTGT" 12.088138188711829] ["TCTTTG" 11.876481436902408])

;;round 11 vs round 9
(["TTGGTT" 25.91787330674479] ["TGGTTT" 25.845936514710676] ["TTTGGT" 19.877741563560754] ["CTTGGT" 19.24089275573208] ["CTTCTT" 17.549219268199415] ["GGTTTA" 17.50697341968236] ["TTCTTT" 16.634552819390308] ["TTGGTG" 13.529265555173582] ["TCTTTG" 11.941030978936078] ["TGGTGT" 11.848737115678052])
(time
 (let [get-seqs (fn [round]
                  (->> (round-all-usable-seqs round) sql-query
                       (map :hit_seq )
                       (map #(second
                              (re-find #"TGCGTAACGTACACT(.*)ATGTCTCTAAGTACT" %)))
                       (remove nil?)))
       sqs11 (get-seqs 11)
       cnt (count sqs11)
       res (map first [["GTGTCT" 2.06174109652782] ["GCGGTG" 2.0616517848451674]
                       ["CCTGCG" 2.042251885207106] ["CTGCGG" 1.9855611802607638]
                       ["TGCGGT" 1.9823356229670237] ["GCGGAC" 1.966339850816003]
                       ["TTGATC" 1.9568510477633874] ["GTTTTG" 1.9135521648495453]
                       ["TTTGAT" 1.890558758524509] ["TGTCTT" 1.874874631767203]])]
   (reduce (fn [M re]
             (let [sqs (->> (map #(str-re-pos (re-pattern re) %) sqs11)
                            (remove empty?))]
               (assoc M re {"starts" (->> (mapcat keys sqs) (map #(/ % 30))
                                          frequencies (into (sorted-map)))
                            "motifs per seq" (->> (map count sqs) frequencies)
                            
                            "percent containing motif" (double (/ (count sqs) cnt))})))
           {"lengths" (->> (map count sqs11) frequencies (into (sorted-map)))}
           res)))


(defn locate-motifs
  "attempts to locate the position of motifs in round 11 based on the
  most prevalent motifs found when comparing different rounds"

  [res]
  (time
   (let [get-seqs (fn [round]
                    (->> (round-all-usable-seqs round) sql-query
                         (map :hit_seq )
                         (map #(second
                                (re-find #"TGCGTAACGTACACT(.*)ATGTCTCTAAGTACT" %)))
                         (remove nil?)
                         distinct))
         sqs11 (get-seqs 11)
         cnt (count sqs11)]
     (reduce (fn [M re]
               (let [sqs (->> (map #(vector % (str-re-pos (re-pattern re) %)) sqs11)
                              (remove #(-> % second empty?)))
                     motifs-per-seq (->> (map second sqs) (map count) frequencies)
                     cutoff (fn [cnt thr] (<= (/ cnt (reduce + (vals motifs-per-seq)))
                                            thr))]
                 (assoc M re {"starts" (->>
                                        (pxmap (fn [[s m]]
                                                 (let [ks (keys m)
                                                       c (const-start s)]
                                                   (map #(/ (inc %) c) ks)))
                                               20 sqs)
                                        (apply concat) frequencies
                                        (remove #(cutoff (second %) 0.05))
                                        (into (sorted-map)))
                              "motifs per seq" motifs-per-seq
                              "lengths" (->> (map first sqs) (map count) frequencies (into (sorted-map)))
                              "percent containing motif" (double (/ (count sqs) cnt))})))
             {}
             res))))

(locate-motifs (mapv first [["GTGTCT" 2.06174109652782] ["GCGGTG" 2.0616517848451674]
                          ["CCTGCG" 2.042251885207106] ["CTGCGG" 1.9855611802607638]
                          ["TGCGGT" 1.9823356229670237] ["GCGGAC" 1.966339850816003]
                          ["TTGATC" 1.9568510477633874] ["GTTTTG" 1.9135521648495453]
                          ["TTTGAT" 1.890558758524509] ["TGTCTT" 1.874874631767203]]))

(locate-motifs (mapv first  '(["GGTGTC" 1.6428656241397235] ["CGGGTA" 1.6195919644356367] ["CCGGGT" 1.5897515542640006] ["GCGGTG" 1.5739453570424213] ["TGGGTG" 1.5727188947041364] ["GGGTAT" 1.5714839436422858] ["CGGTGT" 1.5652489716303113] ["TGGTGT" 1.5467607633840224] ["TTGTGG" 1.535166940421112] ["ACTCTT" 1.4982984993353305] )))
"Elapsed time: 671047.354179 msecs"
{"GGGTAT" {"starts" {2/31 519, 3/31 355, 4/31 353}, "motifs per seq" {1 5819, 2 7}, "lengths" {49 43, 50 52, 51 75, 52 134, 53 230, 54 404, 55 660, 56 1194, 57 2424, 58 422, 59 109, 60 41, 61 13, 62 9, 63 6, 64 3, 65 3, 66 2, 68 2}, "percent containing motif" 0.0127320050613326}, "GGTGTC" {"starts" {27/31 8459}, "motifs per seq" {1 12982, 2 41}, "lengths" {49 85, 50 70, 51 155, 52 419, 53 751, 54 1189, 55 1564, 56 2564, 57 4702, 58 1119, 59 250, 60 68, 61 35, 62 21, 63 12, 64 9, 65 4, 66 4, 67 1, 68 1}, "percent containing motif" 0.02846016167417344}, "TGGGTG" {"starts" {12/31 288, 24/31 635, 25/31 1180}, "motifs per seq" {1 5069, 2 9}, "lengths" {49 57, 50 45, 51 95, 52 190, 53 260, 54 399, 55 638, 56 982, 57 1734, 58 490, 59 113, 60 41, 61 16, 62 10, 63 5, 66 1, 67 2}, "percent containing motif" 0.01109734323746085}, "TTGTGG" {"starts" {8/31 128, 9/31 307, 10/31 117, 23/31 149}, "motifs per seq" {1 2310, 2 1}, "lengths" {49 19, 50 22, 51 111, 52 104, 53 129, 54 218, 55 300, 56 508, 57 659, 58 156, 59 57, 60 17, 61 5, 62 4, 63 2}, "percent containing motif" 0.005050405715197329}, "CCGGGT" {"starts" {1/31 799, 2/31 1103}, "motifs per seq" {1 7476, 2 2}, "lengths" {49 35, 50 35, 51 85, 52 162, 53 285, 54 473, 55 929, 56 1478, 57 3041, 58 644, 59 188, 60 55, 61 27, 62 17, 63 13, 64 8, 66 3}, "percent containing motif" 0.01634224748517768}, "TGGTGT" {"starts" {26/31 2705, 45/31 808, 46/31 3182}, "motifs per seq" {1 12308, 2 78}, "lengths" {49 52, 50 68, 51 152, 52 316, 53 379, 54 615, 55 1346, 56 2535, 57 5420, 58 1091, 59 267, 60 75, 61 34, 62 7, 63 10, 64 4, 65 5, 66 4, 67 1, 68 4, 69 1}, "percent containing motif" 0.02706807667175859}, "GCGGTG" {"starts" {23/31 211, 24/31 319, 25/31 1113}, "motifs per seq" {1 3762, 2 7}, "lengths" {49 98, 50 47, 51 83, 52 156, 53 347, 54 581, 55 437, 56 598, 57 1074, 58 260, 59 48, 60 18, 61 10, 62 5, 63 4, 64 2, 66 1}, "percent containing motif" 0.008236685045685302}, "ACTCTT" {"starts" {27/29 1007, 14/15 2600, 29/31 8072}, "motifs per seq" {1 16761, 2 17}, "lengths" {49 515, 50 883, 51 1468, 52 2673, 53 3158, 54 3447, 55 2435, 56 1061, 57 768, 58 197, 59 89, 60 38, 61 24, 62 9, 63 5, 64 6, 65 1, 69 1}, "percent containing motif" 0.03666625144508039}, "CGGGTA" {"starts" {1/31 1089, 2/31 828, 3/31 998}, "motifs per seq" {1 10256, 2 13}, "lengths" {49 60, 50 86, 51 105, 52 202, 53 368, 54 690, 55 1210, 56 2091, 57 4270, 58 813, 59 208, 60 79, 61 39, 62 21, 63 13, 64 6, 65 4, 66 3, 68 1}, "percent containing motif" 0.02244163404991838}, "CGGTGT" {"starts" {24/31 431, 25/31 486, 26/31 3499}, "motifs per seq" {1 7954, 2 11}, "lengths" {49 46, 50 60, 51 90, 52 248, 53 520, 54 884, 55 866, 56 1573, 57 2807, 58 609, 59 164, 60 45, 61 17, 62 16, 63 7, 64 7, 65 3, 66 2, 67 1}, "percent containing motif" 0.01740652597211022}}

;;mean base rate in round11
(time
   (let [get-seqs (fn [round]
                    (->> (round-all-usable-seqs round) sql-query
                         (map :hit_seq )
                         (map #(second
                                (re-find #"TGCGTAACGTACACT(.*)ATGTCTCTAAGTACT" %)))
                         (remove nil?)
                         distinct))
         sqs11 (get-seqs 11)
         cnt (count sqs11)
         const-start (fn [s] (let [dists (map-indexed (fn [i x] [i (levenshtein prime3-const x)])
                                                    (str-partition 20 s))
                                 m (apply min (map second dists))]
                             (prn :s s)
                             (-> (drop-while #(not= (second %) m) dists)
                                 ffirst inc)))]
     (->> (map #(probs 1 %) sqs11)
          (apply merge-with +)
          (reduce (fn [M [k p]]
                    (assoc M k (/ p cnt)))
                  {}))))
"Elapsed time: 20713.256229 msecs"
{\C 0.18905955831540414, \T 0.3135315468699793, \A 0.3088526372889163, \G 0.18855625752406222}

;;;variable region base freq round 11
(time
 (let [get-seqs (fn [round]
                  (->> (round-all-usable-seqs 11) sql-query
                       (reduce (fn [M {:keys [id hit_seq cs]}]
                                 (let [sq (second
                                           (re-find #"TGCGTAACGTACACT(.*)ATGTCTCTAAGTACT" hit_seq))]
                                   (if sq (assoc M sq cs) M)))
                               {})
                       ))
       sqs11 (get-seqs 11)
       cnt (count sqs11)
       ]
   (->> (map #(freqn 1 (str/take (-> % second) (first %))) sqs11)
        (apply merge-with +)
        probs  )))
"Elapsed time: 13456.612033 msecs"
{\G 0.24554906691039702, \C 0.24531945542539446, \T 0.18459663501996412, \A 0.32453484264424437}

(def foo (time
          (let [get-seqs (fn [round]
                           (->> (round-all-usable-seqs round) sql-query
                                (reduce (fn [M {:keys [id hit_seq]}]
                                          (let [cval (get M hit_seq [])
                                                sq (second
                                                    (re-find #"TGCGTAACGTACACT(.*)ATGTCTCTAAGTACT" hit_seq))]
                                            (if sq (assoc M sq (conj cval id)) M)))
                                        {})
                                ))
                sqs11 (get-seqs 11)
                cnt (count sqs11)
                const-start (fn [s] (let [dists (map-indexed (fn [i x] [i (levenshtein prime3-const x)])
                                                           (str-partition 20 s))
                                        m (apply min (map second dists))]
                                    (-> (drop-while #(not= (second %) m) dists)
                                        ffirst inc)))]
            (->> (pxmap (fn  ([[s ids]]
                              (let [c (const-start s)]
                                (interleave ids (repeat c)))))
                        20 sqs11)
                 flatten
                 (partition-all 2)
                 doall))))


