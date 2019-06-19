(ns openmind.routes
  (:require [clojure.core.async :as async]
            [clojure.walk :as walk]
            [openmind.elastic :as es]))


(defn search-req [query]
  (es/search es/index (es/search->elastic query)))

(def ^:private top-level-tags (atom nil))

(defn get-top-level-tags []
  (async/go
    (if @top-level-tags
      @top-level-tags
      (let [t (into {}
                    (map
                     (fn [{id :_id {tag :tag-name} :_source}]
                       [tag id]))
                    (-> (es/top-level-tags es/tag-index)
                        es/request<!))]
        (reset! top-level-tags t)
        t))))

(def ^:private tags
  "Tag cache (this is going to be looked up a lot)."
  (atom {}))

(defn lookup-tags [root]
  (async/go
    (->> (es/subtag-lookup es/tag-index root)
         es/request<!
         (map (fn [{:keys [_id _source]}]
                [_id _source]))
         (into {}))))

(defn get-tag-tree [root]
  (async/go
    (if (contains? @tags root)
      (get @tags root)
      ;; Wasteful, but at least it's consistent
      (let [v (async/<! (lookup-tags root))]
        (swap! tags assoc root v)
        v))))

(defn parse-search-response [res]
  (mapv :_source (:hits (:hits res))))

(defn reconstruct [root re]
  (assoc root :children (mapv (fn [c] (reconstruct c re)) (get re (:id root)))))

(defn invert-tag-tree [tree root-node]
  (let [id->node (into {} tree)
        parent->children
        (->> tree
             (map (fn [[id x]] (assoc x :id id)))
             (group-by :parents)
             (map (fn [[k v]] [(last k) (mapv #(dissoc % :parents) v)]))
             (into {}))]
    (reconstruct root-node parent->children)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; routing table
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti dispatch (fn [e] (first (:event e))))

(defmethod dispatch :chsk/ws-ping
  [_])

(defmethod dispatch :chsk/uidport-open
  [_])

(defmethod dispatch :default
  [e]
  (println "Unhandled client event:" e)
  ;; REVIEW: Dropping unhandled messages is suboptimal.
  nil)

(defmethod dispatch :openmind/search
  [{[_  {:keys [user search]}] :event :keys [send-fn ?reply-fn uid]}]
  (let [nonce (:nonce search)]
    (async/go
      (let [res   (parse-search-response (es/request<! (search-req search)))
            event [:openmind/search-response {:results res :nonce nonce}]]
        (cond
          (fn? ?reply-fn)                    (?reply-fn event)
          (not= :taoensso.sente/nil-uid uid) (send-fn uid event)

          ;; TODO: Logging
          :else (println "No way to return response to sender."))))))

(defn prepare-doc [doc]
  (let [formatter (java.text.SimpleDateFormat. "YYYY-MM-dd'T'HH:mm:ss.SSSXXX")]
    (walk/prewalk
     (fn [x] (if (inst? x) (.format formatter x) x))
     doc)))

(defmethod dispatch :openmind/index
  [{:keys [client-id send-fn] [_ doc] :event}]
  (async/go
    (async/<! (es/send-off! (es/index-req es/index (prepare-doc doc))))))

(defmethod dispatch :openmind/tag-tree
  [{:keys [send-fn ?reply-fn] [_ root] :event}]
  (async/go
    (let [root-id (get (async/<! (get-top-level-tags)) root)
          tree    (async/<! (get-tag-tree root-id))
          event   [:openmind/tag-tree (invert-tag-tree tree {:tag-name root
                                                             :id       root-id})]]
      (when ?reply-fn
        (?reply-fn event)))))
