(ns rems.concurrency
  "Namespace for wrapping java.util.concurrent functions."
  (:import [java.util.concurrent Executors ExecutorService ThreadFactory TimeUnit]))

(defn- create-thread-factory ^ThreadFactory [& [thread-prefix]]
  (let [default-thread-factory (Executors/defaultThreadFactory)]
    (proxy [java.util.concurrent.ThreadFactory] []
      (newThread [runnable]
        (let [thread (.newThread default-thread-factory runnable)
              thread-name (if thread-prefix
                            (str thread-prefix "-" (.getName thread))
                            (.getName thread))]
          (doto thread
            (.setName thread-name)))))))

(defn get-available-processors []
  (.. Runtime getRuntime availableProcessors))

(defn cached-thread-pool
  "Creates a thread pool that creates new threads as needed, but will reuse previously constructed
   threads when they are available.
   
   Available options:
   - `thread-prefix` sets thread name prefix, e.g. \"worker-...\", useful for debugging"
  ^ExecutorService [& [{:keys [thread-prefix]}]]
  (Executors/newCachedThreadPool (create-thread-factory thread-prefix)))

(defn work-stealing-thread-pool
  "Creates a thread pool that maintains enough threads to support the given parallelism level,
   and may use multiple queues to reduce contention. If `parallelism` is not provided, uses
   the number of available processors instead.
   
   Available options:
   - `parallelism` the targeted parallelism level"
  ^ExecutorService [& [{:keys [parallelism]}]]
  (if parallelism
    (Executors/newWorkStealingPool parallelism)
    (Executors/newWorkStealingPool)))

(defn submit-one! [^ExecutorService thread-pool ^Callable task]
  (.submit thread-pool task))

(defn submit! [^ExecutorService thread-pool & tasks]
  (doall
   (for [task (flatten tasks)]
     (submit-one! thread-pool task))))

(defn shutdown!
  "Initiates an orderly shutdown in which previously submitted tasks are executed,
   but no new tasks will be accepted.
   
   If `timeout-ms` is provided, blocks until all tasks have completed execution after
   a shutdown request, or the timeout occurs, or the current thread is interrupted,
   whichever happens first."
  [^ExecutorService thread-pool & [{:keys [timeout-ms]}]]
  (.shutdown thread-pool)
  (when timeout-ms
    (when-not (.awaitTermination thread-pool timeout-ms TimeUnit/MILLISECONDS)
      (throw (IllegalStateException. "did not terminate")))))

(defn shutdown-now!
  "Attempts to stop all actively executing tasks and halts the processing of waiting tasks.
   
   If `timeout-ms` is provided, blocks until all tasks have completed execution after
   a shutdown request, or the timeout occurs, or the current thread is interrupted,
   whichever happens first."
  [^ExecutorService thread-pool & [{:keys [timeout-ms]}]]
  (.shutdownNow thread-pool)
  (when timeout-ms
    (when-not (.awaitTermination thread-pool timeout-ms TimeUnit/MILLISECONDS)
      (throw (IllegalStateException. "did not terminate")))))
