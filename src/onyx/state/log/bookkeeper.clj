(ns ^:no-doc onyx.state.log.bookkeeper
  (:require [onyx.log.curator :as curator]
            [taoensso.timbre :refer [info error warn trace fatal] :as timbre]
            [com.stuartsierra.component :as component]
            [clojure.core.async :refer  [chan timeout thread go >! <! <!! >!! alts!! close!]]
            [onyx.compression.nippy :as nippy]
            [onyx.extensions :as extensions]
            [onyx.monitoring.measurements :refer [emit-latency-value emit-latency]]
            [onyx.peer.operation :as operation]
            [onyx.windowing.aggregation :as agg]
            [onyx.state.state-extensions :as state-extensions]
            [onyx.types :refer [inc-count! dec-count!]]
            [onyx.log.replica]
            [onyx.log.commands.common :refer [peer-slot-id]]
            [onyx.log.zookeeper :as zk]
            [onyx.static.default-vals :refer [arg-or-default defaults]])
  (:import [org.apache.bookkeeper.client LedgerHandle LedgerEntry BookKeeper BookKeeper$DigestType AsyncCallback$AddCallback]
           [org.apache.bookkeeper.conf ClientConfiguration]
           [org.apache.curator.framework CuratorFramework CuratorFrameworkFactory]))

(defrecord BookKeeperLog [client ledger-handle next-ledger-handle batch-ch])

(defn open-ledger ^LedgerHandle [^BookKeeper client id digest-type password]
  (.openLedger client id digest-type password))

(defn create-ledger ^LedgerHandle [^BookKeeper client ensemble-size quorum-size digest-type password]
  (.createLedger client ensemble-size quorum-size digest-type password))

(defn close-handle [^LedgerHandle ledger-handle]
  (.close ledger-handle))

(defn bookkeeper
  ([opts]
   (bookkeeper (:zookeeper/address opts)
               (zk/ledgers-path (:onyx/id opts))
               (arg-or-default :onyx.bookkeeper/client-timeout opts)
               (arg-or-default :onyx.bookkeeper/client-throttle opts)))
  ([zk-addr zk-root-path timeout throttle]
   (let [conf (doto (ClientConfiguration.)
                (.setZkServers zk-addr)
                (.setZkTimeout timeout)
                (.setThrottleValue throttle)
                (.setZkLedgersRootPath zk-root-path))]
     (BookKeeper. conf))))

(def digest-type 
  (BookKeeper$DigestType/MAC))

(defn password [peer-opts]
  (.getBytes ^String (arg-or-default :onyx.bookkeeper/ledger-password peer-opts)))

(defn new-ledger ^LedgerHandle [client peer-opts]
  (let [ensemble-size (arg-or-default :onyx.bookkeeper/ledger-ensemble-size peer-opts)
        quorum-size (arg-or-default :onyx.bookkeeper/ledger-quorum-size peer-opts)]
    (create-ledger client ensemble-size quorum-size digest-type (password peer-opts))))


(def HandleWriteCallback
  (reify AsyncCallback$AddCallback
    (addComplete [this rc lh entry-id callback-fn]
      (callback-fn))))

(defn compaction-transition 
  "Transitions to a new compacted ledger, plus a newly created ledger created
  earlier.  For example, if there were ledgers [1, 2, 3, 4], we've created a
  ledger id 5 to start writing to, making [1, 2, 3, 4, 5], then we create a compacted
  ledger 6, write the updated state to it, and swap [1, 2, 3, 4] in the replica
  for 6, leaving [6, 5]"
  [{:keys [client ledger-handle next-ledger-handle] :as log}
   {:keys [onyx.core/peer-opts onyx.core/job-id onyx.core/replica
           onyx.core/id onyx.core/task-id onyx.core/monitoring onyx.core/window-state onyx.core/outbox-ch] 
    :as event}]
  (info "Transitioning to new handle after gc" (.getId ^LedgerHandle @next-ledger-handle))
  (let [previous-handle @ledger-handle
        start-time (System/currentTimeMillis)
        slot-id (peer-slot-id event)
        extent-snapshot (:state @window-state)
        ;; Deref future later so that we can immediately return and continue processing
        filter-snapshot (state-extensions/snapshot-filter (:filter @window-state) event)
        current-ids (get-in @replica [:state-logs job-id task-id slot-id])]
    (reset! ledger-handle @next-ledger-handle)
    (reset! next-ledger-handle nil)
    ;; Don't throw an exception, maybe we can give the next GC a chance to succeed
    ;; Log is still in a known good state, we have transitioned to a ledger that is in the replica
    (if-not (= (last current-ids) (.getId ^LedgerHandle @ledger-handle))
      (warn "Could not swap compacted log. Next ledger handle is no longer the next published ledger" 
            {:job-id job-id :task-id task-id :slot-id slot-id 
             :ledger-handle (.getId ^LedgerHandle @ledger-handle) :current-ids current-ids})
      (future 
        (close-handle previous-handle)
        (let [compacted {:type :compacted
                         :filter-snapshot @filter-snapshot
                         :extent-state extent-snapshot}
              compacted-ledger (new-ledger client peer-opts)
              compacted-ledger-id (.getId compacted-ledger)
              compacted-serialized ^bytes (nippy/window-log-compress compacted)]
          (.asyncAddEntry compacted-ledger 
                          compacted-serialized
                          HandleWriteCallback
                          (fn []
                            (emit-latency-value :window-log-compaction monitoring (- (System/currentTimeMillis) start-time))
                            (>!! outbox-ch
                                 {:fn :compact-bookkeeper-log-ids
                                  :args {:job-id job-id
                                         :task-id task-id
                                         :slot-id slot-id
                                         :peer-id id
                                         :prev-ledger-ids (vec (butlast current-ids))
                                         :new-ledger-ids [compacted-ledger-id]}}))))))))

(defn ch->type [ch batch-ch timeout-ch kill-ch task-kill-ch]
  (cond (= ch timeout-ch)
        :timeout
        (or (= ch kill-ch) (= ch task-kill-ch))
        :shutdown
        :else
        :read))

(defn read-batch [peer-opts batch-ch kill-ch task-kill-ch]
  (let [batch-size (arg-or-default :onyx.bookkeeper/write-batch-size peer-opts)
        timeout-ms (arg-or-default :onyx.bookkeeper/write-batch-timeout peer-opts)
        timeout-ch (timeout timeout-ms)]
    (loop [entries [] ack-fns [] i 0]
      (if (< i batch-size)
        (let [[[entry ack-fn] ch] (alts!! [batch-ch timeout-ch kill-ch task-kill-ch])]
          (if entry 
            (recur (conj entries entry) 
                   (conj ack-fns ack-fn) 
                   (inc i))
            (let [msg-type (ch->type ch batch-ch timeout-ch kill-ch task-kill-ch)]
              [msg-type entries ack-fns])))
        [:read entries ack-fns]))))

(defn process-batches [{:keys [ledger-handle next-ledger-handle batch-ch] :as log} 
                       {:keys [onyx.core/kill-ch onyx.core/task-kill-ch onyx.core/peer-opts] :as event}]
  (thread 
    (loop [[result batch ack-fns] (read-batch peer-opts batch-ch kill-ch task-kill-ch)]
      ;; Safe point to transition to the next ledger handle
      (when @next-ledger-handle
        (compaction-transition log event))
      (when-not (empty? batch) 
        (.asyncAddEntry ^LedgerHandle @ledger-handle 
                        ^bytes (nippy/window-log-compress batch)
                        HandleWriteCallback
                        (fn [] (run! (fn [f] (f)) ack-fns))))
      (if-not (= :shutdown result)
        (recur (read-batch peer-opts batch-ch kill-ch task-kill-ch))))
    (info "BookKeeper: shutting down batch processing")))

(defn assign-bookkeeper-log-id-spin [{:keys [onyx.core/replica onyx.core/peer-opts
                                             onyx.core/job-id onyx.core/task-id
                                             onyx.core/kill-ch onyx.core/task-kill-ch
                                             onyx.core/outbox-ch] :as event}
                                     new-ledger-id]
  (let [slot-id (peer-slot-id event)]
    (>!! outbox-ch
         {:fn :assign-bookkeeper-log-id
          :args {:job-id job-id
                 :task-id task-id
                 :slot-id slot-id
                 :ledger-id new-ledger-id}})
    (while (and (first (alts!! [kill-ch task-kill-ch] :default true))
                (not= new-ledger-id
                      (last (get-in @replica [:state-logs job-id task-id slot-id]))))
      (info "New ledger id has not been published yet. Backing off.")
      (Thread/sleep (arg-or-default :onyx.bookkeeper/ledger-id-written-back-off peer-opts)))))

(defmethod state-extensions/initialize-log :bookkeeper [log-type {:keys [onyx.core/peer-opts] :as event}] 
  (let [bk-client (bookkeeper peer-opts)
        ledger-handle (new-ledger bk-client peer-opts)
        new-ledger-id (.getId ledger-handle)
        batch-ch (chan (arg-or-default :onyx.bookkeeper/write-buffer-size peer-opts))
        next-ledger-handle nil] 
    (assign-bookkeeper-log-id-spin event new-ledger-id)
    (info "Ledger id" new-ledger-id "published")
    (doto (->BookKeeperLog bk-client (atom ledger-handle) (atom next-ledger-handle) batch-ch)
      (process-batches event)))) 

(defn playback-windows-extents [state entry {:keys [onyx.core/windows] :as event}]
  (let [grouped-task? (operation/grouped-task? event)
        id->apply-state-update (into {}
                                     (map (juxt :window/id :aggregate/apply-state-update)
                                          windows))]
    (reduce (fn [state' [window-entries {:keys [window/id] :as window}]]
              (reduce (fn [state'' [extent entry grp-key]]
                        (update-in state'' 
                                   [:state id extent]
                                   (fn [ext-state] 
                                     (let [state-value (-> (if grouped-task? (get ext-state grp-key) ext-state)
                                                           (agg/default-state-value window))
                                           apply-fn (id->apply-state-update id)
                                           _ (assert apply-fn (str "Apply fn does not exist for window-id " id))
                                           new-state-value (apply-fn state-value entry)] 
                                       (if grouped-task?
                                         (assoc ext-state grp-key new-state-value)
                                         new-state-value)))))
                      state'
                      window-entries))
            state
            (map list (rest entry) windows))))

(defn compacted-reset? [entry]
  (and (map? entry)
       (= (:type entry) :compacted)))

(defn unpack-compacted [state {:keys [filter-snapshot extent-state]} event]
  (-> state
      (assoc :state extent-state)
      (update :filter state-extensions/restore-filter event filter-snapshot)))

(defn playback-entry [state entry event]
  (let [unique-id (first entry)
        _ (trace "Playing back entries for segment with id:" unique-id)
        new-state (playback-windows-extents state entry event)]
    (if unique-id
      (update new-state :filter state-extensions/apply-filter-id event unique-id)
      new-state)))

(defn playback-batch-entry [state batch event]
  (reduce (fn [state entry]
            (playback-entry state entry event))
            state
            batch)) 

(defn playback-entries-chunk [state ^LedgerHandle lh start end event]
  (let [entries (.readEntries lh start end)]
    (if (.hasMoreElements entries)
      (loop [state' state element ^LedgerEntry (.nextElement entries)]
        (let [entry-val (nippy/window-log-decompress ^bytes (.getEntry element))
              state' (if (compacted-reset? entry-val)
                         (unpack-compacted state' entry-val event)
                         (playback-batch-entry state' entry-val event))] 
          (if (.hasMoreElements entries)
            (recur state' (.nextElement entries))
            state')))
      state)))

(defn playback-ledger [state ^LedgerHandle lh last-confirmed {:keys [onyx.core/peer-opts] :as event}]
  (let [chunk-size (arg-or-default :onyx.bookkeeper/read-batch-size peer-opts)]
    (if-not (neg? last-confirmed)
      (loop [loop-state state start 0 end (min chunk-size last-confirmed)] 
        (let [new-state (playback-entries-chunk loop-state lh start end event)]
          (if (= end last-confirmed)
            new-state
            (recur new-state 
                   (inc end) 
                   (min (+ chunk-size end) last-confirmed)))))
      state)))

(defn playback-ledgers [bk-client peer-opts state ledger-ids event]
  (let [pwd (password peer-opts)]
    (reduce (fn [state' ledger-id]
              (let [lh (open-ledger bk-client ledger-id digest-type pwd)]
                (try
                  (let [last-confirmed (.getLastAddConfirmed lh)]
                    (info "Opened ledger:" ledger-id "last confirmed:" last-confirmed)
                    (playback-ledger state' lh last-confirmed event))
                  (finally
                    (close-handle lh)))))
            state
            ledger-ids)))

(defmethod state-extensions/playback-log-entries onyx.state.log.bookkeeper.BookKeeperLog
  [{:keys [client] :as log} 
   {:keys [onyx.core/monitoring onyx.core/replica 
           onyx.core/peer-opts onyx.core/job-id onyx.core/task-id] :as event} 
   state]
  (emit-latency :window-log-playback 
                monitoring
                (fn [] 
                  (let [slot-id (peer-slot-id event)
                        ;; Don't play back the final ledger id because we just created it
                        prev-ledger-ids (butlast (get-in @replica [:state-logs job-id task-id slot-id]))]
                    (info "Playing back ledgers for" job-id task-id slot-id "ledger-ids" prev-ledger-ids)
                    (playback-ledgers client peer-opts state prev-ledger-ids event)))))

(defmethod state-extensions/compact-log onyx.state.log.bookkeeper.BookKeeperLog
  [{:keys [client ledger-handle next-ledger-handle]} 
   {:keys [onyx.core/peer-opts] :as event} 
   _] 
  (future
    (let [new-ledger-handle (new-ledger client peer-opts)
          new-ledger-id (.getId new-ledger-handle)] 
      (assign-bookkeeper-log-id-spin event new-ledger-id)
      (reset! next-ledger-handle new-ledger-handle))))

(defmethod state-extensions/close-log onyx.state.log.bookkeeper.BookKeeperLog
  [{:keys [client ledger-handle next-ledger-handle]} event] 
  (close-handle @ledger-handle)
  (when @next-ledger-handle
    (close-handle @next-ledger-handle))
  (.close ^BookKeeper client))

(defmethod state-extensions/store-log-entry onyx.state.log.bookkeeper.BookKeeperLog
  [{:keys [ledger-handle next-ledger-handle batch-ch] :as log} event ack-fn entry]
  (>!! batch-ch (list entry ack-fn)))