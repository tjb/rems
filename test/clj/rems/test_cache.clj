(ns rems.test-cache
  (:require [clojure.pprint]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as log-test]
            [clojure.walk]
            [medley.core :refer [map-vals]]
            [rems.cache :as cache]
            [rems.common.dependency :as dep]
            [rems.concurrency :as concurrency]))

(defn- submit-all [thread-pool & fns]
  (->> fns
       (mapv bound-fn*)
       (concurrency/submit! thread-pool)))

(def ^:private caches (atom nil))
(def ^:private caches-dag (atom nil))

(defn- get-cache-entries
  "Returns cache as hash map that can be asserted with ="
  [c]
  (clojure.walk/keywordize-keys (cache/entries! c)))

(defn- get-cache-raw
  "Like get-cache-entries, but does not trigger cache readyness mechanisms."
  [c]
  (clojure.walk/keywordize-keys @(get c :the-cache)))

(def ^:private miss-fn (constantly {:value true}))
(def ^:private reload-fn (constantly {:always {:value :special}}))

(deftest test-basic-cache
  (with-redefs [rems.cache/caches (doto caches (reset! nil))
                rems.cache/caches-dag (doto caches-dag (reset! (dep/make-graph)))]
    (let [c (cache/basic {:id ::test-cache
                          :miss-fn (fn [id] (miss-fn id))
                          :reload-fn (fn [] (reload-fn))})]
      (is (= c
             (get @caches ::test-cache)))
      (is (= []
             (cache/get-cache-dependencies ::test-cache)))

      (testing "initialization"
        (testing "cache is uninitialized at start"
          (is (= {}
                 (get-cache-raw c)))
          (is (= false
                 (deref (:initialized? c))))
          (is (= {:evict 0 :get 0 :reload 0 :upsert 0}
                 (cache/export-statistics! c))))

        (testing "entries reloads cache"
          (is (= {:always {:value :special}}
                 (get-cache-entries c)))
          (is (= {:evict 0 :get 1 :reload 1 :upsert 0}
                 (cache/export-statistics! c)))))

      (testing "lookup"
        (is (= nil
               (cache/lookup! c :a)))
        (is (= {:value :special}
               (cache/lookup! c :always)))
        (is (= {:evict 0 :get 2 :reload 0 :upsert 0}
               (cache/export-statistics! c)))
        (is (= {:always {:value :special}}
               (get-cache-raw c))))

      (testing "lookup-or-miss"
        (testing "existing entry should not trigger cache miss"
          (is (= {:value :special}
                 (cache/lookup-or-miss! c :always)))
          (is (= {:evict 0 :get 1 :reload 0 :upsert 0}
                 (cache/export-statistics! c)))
          (is (= {:always {:value :special}}
                 (get-cache-raw c))))

        (testing "non-existing entry should be added on cache miss"
          (is (= {:value true}
                 (cache/lookup-or-miss! c :a)))
          (is (= {:evict 0 :get 2 :reload 0 :upsert 1}
                 (cache/export-statistics! c)))
          (is (= {:a {:value true}
                  :always {:value :special}}
                 (get-cache-raw c))))

        (testing "absent value skips cache entry"
          (with-redefs [miss-fn (constantly cache/absent)]
            (is (= nil
                   (cache/lookup-or-miss! c :test-skip))))
          (is (= {:evict 0 :get 1 :reload 0 :upsert 0}
                 (cache/export-statistics! c)))
          (is (= {:a {:value true}
                  :always {:value :special}}
                 (get-cache-raw c)))))

      (testing "evict"
        (cache/evict! c :a)
        (is (= nil
               (cache/lookup! c :a)))
        (is (= {:evict 1 :get 3 :reload 0 :upsert 0}
               (cache/export-statistics! c)))
        (is (= {:always {:value :special}}
               (get-cache-raw c)))

        (testing "non-existing entry does nothing"
          (cache/evict! c :does-not-exist)
          (is (= {:evict 0 :get 1 :reload 0 :upsert 0}
                 (cache/export-statistics! c)))
          (is (= {:always {:value :special}}
                 (get-cache-raw c)))))

      (testing "miss"
        (cache/miss! c :new-entry)
        (is (= {:value true}
               (cache/lookup! c :new-entry)))
        (is (= {:value true}
               (cache/lookup-or-miss! c :new-entry)))
        (is (= {:evict 0 :get 4 :reload 0 :upsert 1}
               (cache/export-statistics! c)))
        (is (= {:always {:value :special}
                :new-entry {:value true}}
               (get-cache-raw c)))))))

(deftest test-cache-dependencies
  (testing "cannot create caches with circular dependencies"
    (with-redefs [rems.cache/caches (doto caches (reset! nil))
                  rems.cache/caches-dag (doto caches-dag (reset! (dep/make-graph)))]
      (let [_cache-a (cache/basic {:id :a :depends-on [:b]})]
        (is (thrown-with-msg? RuntimeException #"Circular dependency between :b and :a"
                              (cache/basic {:id :b :depends-on [:a]}))))))

  (testing "cannot override existing cache id"
    (with-redefs [rems.cache/caches (doto caches (reset! nil))
                  rems.cache/caches-dag (doto caches-dag (reset! (dep/make-graph)))]
      (let [_cache-a (cache/basic {:id :a})]
        (is (thrown-with-msg? AssertionError #"Assert failed: error overriding cache id :a"
                              (cache/basic {:id :a}))))))

  (testing "can create basic caches with dependencies"
    (with-redefs [rems.cache/caches (doto caches (reset! nil))
                  rems.cache/caches-dag (doto caches-dag (reset! (dep/make-graph)))]
      (let [cache-a (cache/basic {:id :a
                                  :reload-fn (fn [] {1 true})})
            cache-b (cache/basic {:id :b
                                  :depends-on [:a]
                                  :reload-fn (fn [deps] {2 true})})
            cache-c (cache/basic {:id :c
                                  :depends-on [:b]
                                  :reload-fn (fn [deps] {3 true})})
            cache-d (cache/basic {:id :d
                                  :depends-on [:a :b]
                                  :reload-fn (fn [deps] {4 true})})]
        (testing "cache :a has no dependencies"
          (is (= cache-a (get @caches :a)))
          (is (= [] (cache/get-cache-dependencies :a))))

        (testing "cache :b depends on [:a]"
          (is (= cache-b (get @caches :b)))
          (is (= [cache-a] (cache/get-cache-dependencies :b))))

        (testing "cache :c depends on [:b]"
          (is (= cache-c (get @caches :c)))
          (is (= [cache-b] (cache/get-cache-dependencies :c))))

        (testing "cache :d depends on [:a :b]"
          (is (= cache-d (get @caches :d)))
          (is (= #{cache-a cache-b} (set (cache/get-cache-dependencies :d)))))

        (testing "accessing cache :c causes [:a :b] to reload"
          (log-test/with-log
            (is (= {}
                   (get-cache-raw cache-a)
                   (get-cache-raw cache-b)
                   (get-cache-raw cache-c)
                   (get-cache-raw cache-d))
                "raw caches should be empty initially")
            (is (= []
                   (log-test/the-log)))
            (is (= {3 true}
                   (get-cache-entries cache-c)))
            (is (= ["> :c :reload"
                    "> :b :reload"
                    "> :a :reload"
                    "> :a :reset-dependents {:dependents (:c :b :d)}"
                    "< :a :reset-dependents"
                    "< :a :reload {:count 1}"
                    "> :b :reset-dependents {:dependents (:c :d)}"
                    "< :b :reset-dependents"
                    "< :b :reload {:count 1}"
                    "< :c :reload {:count 1}"]
                   (mapv :message (log-test/the-log)))))

          (testing "accessing cache :a does not cause further reloads"
            (log-test/with-log
              (is (= {1 true}
                     (get-cache-entries cache-a)))
              (is (= []
                     (log-test/the-log)))))

          (testing "accessing cache :b does not cause further reloads"
            (log-test/with-log
              (is (= {2 true}
                     (get-cache-entries cache-b)))
              (is (= []
                     (log-test/the-log)))))

          (testing "accessing cache :c does not cause further reloads"
            (log-test/with-log
              (is (= {3 true}
                     (get-cache-entries cache-c)))
              (is (= []
                     (log-test/the-log)))))

          (testing "accessing cache :d reloads only itself"
            (log-test/with-log
              (is (= {4 true}
                     (get-cache-entries cache-d)))
              (is (= ["> :d :reload"
                      "< :d :reload {:count 1}"]
                     (mapv :message (log-test/the-log)))))))))))

(defmacro with-tracing [& body]
  `(let [start# (System/nanoTime)
         value# (do ~@body)
         end# (System/nanoTime)]
     [start# end# value#]))

(defn- random-wait []
  (Thread/sleep (+ 1 (rand-int 3))))

;; test outline:
;; - caches A and B hold separate state that is written into (incrementing values)
;; - dependent caches read from A and B
;; - separate threads handle reading and writing with random wait interval
;; - each read and write is logs an event with start/end timestamps, and value received from cache
;; - all events are sorted by _end_ timestamp (ascending)
;; - test result is processed from sorted events with event sourcing
;; - each event associated with cache function (lookup lookup-or-miss miss) is validated against then-current-state
;;
;; debugging with raw events can be useful, they look like this:
;; [:lookup-or-miss :cache-reader-a {:a 2} {:start 2013364271881178, :end 2013364280924581, :duration "9,043ms"}]
;;
;; threads run for roughly (50ms * progress)
;; e.g. progress 20 => 1000ms
(deftest test-cache-transactions
  (with-redefs [rems.cache/caches (doto caches (reset! nil))
                rems.cache/caches-dag (doto caches-dag (reset! (dep/make-graph)))]
    (let [progress (atom 0)
          finished? #(<= 20 @progress)
          make-progress! #(swap! progress inc)

          current-a (atom {:a 0})
          current-b (atom {:b 0})

          ;; separate event logs try to minimize test latency
          reader-a-events (atom [])
          reader-a-event! #(swap! reader-a-events conj [%1 :cache-reader-a %2 %3])

          reader-b-events (atom [])
          reader-b-event! #(swap! reader-b-events conj [%1 :cache-reader-b %2 %3])

          reader-c-events (atom [])
          reader-c-event! #(swap! reader-c-events conj [%1 :cache-reader-c %2 %3])

          writer-a-events (atom [])
          writer-a-event! #(swap! writer-a-events conj [%1 :cache-writer-a %2 %3])

          writer-b-events (atom [])
          writer-b-event! #(swap! writer-b-events conj [%1 :cache-writer-b %2 %3])

          evicter-a-events (atom [])
          evicter-a-event! #(swap! evicter-a-events conj [%1 :cache-evicter-a %2 %3])

          evicter-b-events (atom [])
          evicter-b-event! #(swap! evicter-b-events conj [%1 :cache-evicter-b %2 %3])

          get-all-events #(concat @reader-a-events
                                  @reader-b-events
                                  @reader-c-events
                                  @writer-a-events
                                  @writer-b-events
                                  @evicter-a-events
                                  @evicter-b-events)

          cache-a (cache/basic {:id :a
                                :miss-fn (fn [id]
                                           (random-wait)
                                           (let [value (inc (get @current-a id 0))]
                                             (swap! current-a assoc id value)
                                             value))
                                :reload-fn (fn []
                                             (random-wait)
                                             @current-a)})
          cache-b (cache/basic {:id :b
                                :miss-fn (fn [id]
                                           (random-wait)
                                           (let [value (inc (get @current-b id 0))]
                                             (swap! current-b assoc id value)
                                             value))
                                :reload-fn (fn []
                                             (random-wait)
                                             @current-b)})
          dependent-b (cache/basic {:id :dependent-b
                                    :depends-on [:b]
                                    :reload-fn (fn [deps]
                                                 (select-keys (:b deps) [:b]))})
          dependent-c (cache/basic {:id :dependent-c
                                    :depends-on [:a :b]
                                    :reload-fn (fn [deps]
                                                 (merge (select-keys (:a deps) [:a])
                                                        (select-keys (:b deps) [:b])))})

          cache-transactions-thread-pool (concurrency/cached-thread-pool {:thread-prefix "test-cache-transactions"})]
      (try
        (submit-all cache-transactions-thread-pool
                    (fn cache-reader-a [] (while true
                                            (random-wait)
                                            (let [[start end value] (with-tracing (cache/lookup-or-miss! cache-a :a))]
                                              (reader-a-event! :lookup-or-miss
                                                               {:a value}
                                                               {:start start :end end}))))
                    (fn cache-reader-b [] (while true
                                            (random-wait)
                                            (let [[start end value] (with-tracing (cache/lookup! dependent-b :b))]
                                              (reader-b-event! :lookup
                                                               {:b value}
                                                               {:start start :end end}))))
                    (fn cache-reader-c [] (while true
                                            (random-wait)
                                            (let [[start end value] (with-tracing (cache/entries! dependent-c))]
                                              (reader-c-event! :lookup
                                                               value
                                                               {:start start :end end}))))
                    (fn cache-writer-a [] (while (not (finished?))
                                            (random-wait)
                                            (let [[start end value] (with-tracing (cache/miss! cache-a :a))]
                                              (writer-a-event! :miss
                                                               {:a value}
                                                               {:start start :end end}))))
                    (fn cache-writer-b [] (while (not (finished?))
                                            (random-wait)
                                            (let [[start end value] (with-tracing (cache/miss! cache-b :b))]
                                              (writer-b-event! :miss
                                                               {:b value}
                                                               {:start start :end end}))))
                    (fn cache-evicter-a [] (while (not (finished?))
                                             (random-wait)
                                             (let [[start end] (with-tracing (cache/evict! cache-a :a))]
                                               (evicter-a-event! :evict
                                                                 {}
                                                                 {:key :a :cache (:id cache-a) :start start :end end}))))
                    (fn cache-evicter-b [] (while (not (finished?))
                                             (random-wait)
                                             (let [k (rand-nth ["random-1" "random-2" "random-3"])]
                                               (let [[start end value] (with-tracing (cache/miss! cache-b k))]
                                                 (evicter-b-event! :miss
                                                                   {k value}
                                                                   {:cache (:id cache-b) :start start :end end}))
                                               (random-wait)
                                               (let [[start end] (with-tracing (cache/evict! cache-b k))]
                                                 (evicter-b-event! :evict
                                                                   {}
                                                                   {:key k :cache (:id cache-b) :start start :end end}))))))
        (while (not (finished?))
          (make-progress!)
          (println "progress" @progress)
          (Thread/sleep 50))
        (finally
          (cache/shutdown-thread-pool!)
          (println "terminating test thread pool")
          (concurrency/shutdown-now! cache-transactions-thread-pool {:timeout-ms 5000})))

      (let [get-event-duration #(format "%.3fms" (/ (- (:end %) (:start %))
                                                    (* 1000.0 1000.0)))
            event-end (fn [[_ _ _ opts]] (:end opts))
            raw-events (->> (get-all-events)
                            (sort-by event-end)
                            (map (fn [[event-type id state-kv opts]]
                                   [event-type id state-kv (-> opts
                                                               (assoc :duration (get-event-duration opts)))])))
            result (->> raw-events
                        (reduce (fn [m [event-type id state-kv opts]]
                                  (case event-type
                                    :miss
                                    (-> m
                                        (update :state merge state-kv)
                                        (update :last-evicted (partial apply dissoc) (keys state-kv))
                                        (update :miss (fnil conj []) (merge {:id id
                                                                             :state-kv state-kv
                                                                             :state (:state m)}
                                                                            (select-keys m [:last-evicted]))))
                                    :lookup
                                    (-> m
                                        (update :lookup (fnil conj []) (merge {:id id
                                                                               :state-kv state-kv
                                                                               :state (:state m)}
                                                                              (select-keys m [:last-evicted]))))
                                    :lookup-or-miss
                                    (-> m
                                        (update :state merge state-kv)
                                        (update :last-evicted (partial apply dissoc) (keys state-kv))
                                        (update :lookup-or-miss (fnil conj []) (merge {:id id
                                                                                       :state-kv state-kv
                                                                                       :state (:state m)}
                                                                                      (select-keys m [:last-evicted]))))
                                    :evict
                                    (-> m
                                        (assoc-in [:last-evicted (:key opts)] (get-in m [:state (:key opts)]))
                                        (update :evict (fnil conj []) {:id id}))))
                                {:state {:a 0 :b 0}}))

            valid-lookup? (fn [{:keys [id state state-kv last-evicted]}]
                            (let [evicted #(some? (get last-evicted %))
                                  valid #(= (get state %) (get state-kv %))]
                              (case id
                                :cache-reader-a (or (evicted :a)
                                                    (valid :a))
                                :cache-reader-b (or (evicted :b)
                                                    (valid :b))
                                :cache-reader-c (or (some evicted [:a :b])
                                                    (every? valid [:a :b])))))
            valid-lookup-or-miss? (fn [{:keys [id state state-kv last-evicted]}]
                                    (let [evicted #(some? (get last-evicted %))
                                          validate-key #(cond
                                                          ;; miss-fn is invoked on evicted key
                                                          (evicted %) (= (inc (get last-evicted %)) (get state-kv %))
                                                          :else (= (get state %) (get state-kv %)))]
                                      (case id
                                        :cache-reader-a (validate-key :a)
                                        :cache-reader-b (validate-key :b)
                                        :cache-reader-c (every? validate-key [:a :b]))))
            valid-miss? (fn [{:keys [id state state-kv last-evicted]}]
                          (let [evicted #(some? (get last-evicted %))
                                validate-key #(cond
                                                (evicted %) (= (inc (get last-evicted %)) (get state-kv %))
                                                :else (= (inc (get state %)) (get state-kv %)))]
                            (case id
                              ;; updates must be sequential
                              :cache-writer-a (validate-key :a)
                              :cache-writer-b (validate-key :b)
                              true)))

            save-event-logs-to-file! (delay
                                       ;; uncomment for local debugging
                                       #_(spit "debug-cache-transactions-test-raw-events.edn"
                                               (binding [clojure.pprint/*print-right-margin* 150]
                                                 (with-out-str
                                                   (clojure.pprint/pprint raw-events)))))

            save-event-overview-to-file! (delay
                                           ;; uncomment for local debugging
                                           #_(let [event-counts (-> result
                                                                    (select-keys [:lookup :lookup-or-miss :miss :evict])
                                                                    (->> (map-vals count)))]
                                               (spit "cache-transactions-statistics.edn"
                                                     (with-out-str
                                                       (clojure.pprint/pprint {:events event-counts
                                                                               :events-total (reduce + 0 (vals event-counts))
                                                                               :state (:state result)})))))]

        (force save-event-logs-to-file!)
        (force save-event-overview-to-file!)

        (testing "no deadlock"
          (is (< 1 (get-in result [:state :a])))
          (is (< 1 (get-in result [:state :b])))
          (let [count-events #(count (filter (comp #{%2} :id) (get result %1)))]
            (is (< 1 (count-events :lookup-or-miss :cache-reader-a)))
            (is (< 1 (count-events :lookup :cache-reader-b)))
            (is (< 1 (count-events :lookup :cache-reader-c)))
            (is (< 1 (count-events :miss :cache-writer-a)))
            (is (< 1 (count-events :miss :cache-writer-b)))
            (is (< 1 (count-events :miss :cache-evicter-b)))
            (is (< 1 (count-events :evict :cache-evicter-a)))
            (is (< 1 (count-events :evict :cache-evicter-b)))))

        (testing "all cache writes happen in correct order"
          (is (= [] (remove valid-miss? (:miss result)))))

        (testing "all cache reads happen in correct order"
          (is (= [] (remove valid-lookup? (:lookup result)))))

        (testing "all cache lookup-or-misses happen in correct order"
          (is (= [] (remove valid-lookup-or-miss? (:lookup-or-miss result)))))))))
