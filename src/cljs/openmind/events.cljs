(ns openmind.events
  (:require [cljs.core.async :as async]
            [cljs.spec.alpha :as s]
            [clojure.edn :as edn]
            goog.net.XhrIo
            [openmind.db :as db]
            [openmind.spec.extract :as extract-spec]
            [re-frame.core :as re-frame]
            [taoensso.sente :as sente]
            [taoensso.timbre :as log])
  (:require-macros [cljs.core.async.macros :refer [go]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Other
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(re-frame/reg-event-db
 ::initialise-db
 (fn [_ _]
   db/default-db))

(re-frame/reg-event-db
 ::open-menu
 (fn [db _]
   (assoc db :menu-open? true)))

(re-frame/reg-event-db
 ::close-menu
 (fn [db _]
   (assoc db :menu-open? false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Extract Creation tags
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Server Comms
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;; login

(re-frame/reg-event-fx
 ::login-check
 [(re-frame/inject-cofx :storage/get :orcid)]
 (fn [cofx _]
   {:dispatch [::try-send [:openmind/verify-login]]}))

(re-frame/reg-event-fx
 :openmind/identity
 (fn [cofx [_ id]]
   (when-not (empty? id)
     {:storage/set [:orcid id]
      :db          (assoc (:db cofx) :login-info id)})))

(re-frame/reg-event-fx
 ::logout
 (fn [cofx _]
   {:storage/remove :orcid
    ::server-logout nil
    :dispatch [:navigate {:route :search}]}))

(re-frame/reg-fx
 ::server-logout
 (fn [_]
   (goog.net.XhrIo/send "/logout"
                        ;; TODO: Timeout and handle failure to logout.
                        (fn [e]
                          (when (= 200 (-> e .-target .getStatus))
                            (re-frame/dispatch [::complete-logout]))))))

(re-frame/reg-event-db
 ::complete-logout
 (fn [db _]
   (dissoc db :login-info)))

;;;;; sessionStorage

(re-frame/reg-fx
 :storage/set
 (fn [[k v]]
   (.setItem (.-sessionStorage js/window) (str k) (str v))))

(re-frame/reg-fx
 :storage/remove
 (fn [k]
   (.removeItem (.-sessionStorage js/window) (str k)))

 (re-frame/reg-cofx
  :storage/get
  (fn [cofx k]
    (assoc cofx
           :storage/get
           (-> js/window
               .-sessionStorage
               (.getItem (str k))
               edn/read-string)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Tag tree (taxonomy)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(re-frame/reg-event-fx
 ::update-tag-tree
 (fn [{{:keys [chsk openmind.db/domain]} :db} _]
   {:dispatch [::try-send [:openmind/tag-tree domain]]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Connection management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(re-frame/reg-event-fx
 ::try-send
 (fn [{{:keys [chsk]} :db} [_ ev]]
   (if (and (satisfies? IDeref (:state chsk))
            (:open? @(:state chsk)))
     {::send! {:ev ev :send-fn (:send-fn chsk)}}
     {::connect-chsk! true
      :dispatch       [::enqueue-request ev]})))

(re-frame/reg-event-db
 ::enqueue-request
 (fn [db [_ ev]]
   (update db :request-queue conj ev)))

(re-frame/reg-fx
 ::send!
 (fn [{:keys [send-fn ev]}]
   (send-fn ev 10000 re-frame/dispatch)))

(re-frame/reg-event-fx
 ::server-connection
 (fn [{:keys [db]} [_ chsk]]
   (let [pending (:request-queue db)]
     (merge {:db (assoc db :chsk chsk :request-queue [] :connecting? false)
             :dispatch-n
             (into [[::login-check]]
                   (when (seq pending)
                     (map (fn [ev] [::try-send ev]) pending)))}))))

(let [connecting? (atom false)]
  (re-frame/reg-fx
   ::connect-chsk!
   (fn [_]
     (when-not @connecting?
       (reset! connecting? true)
       (log/info "Connecting to server...")
       (let [csrf-ch (async/promise-chan)]
         (goog.net.XhrIo/send "/elmyr"
                              (fn [e]
                                (->> e
                                     .-target
                                     .getResponseText
                                     (async/put! csrf-ch))))
         ;; TODO: timeout, retry, backoff.
         (go
           (let [token (async/<! csrf-ch)
                 chsk  (sente/make-channel-socket-client!
                        "/chsk" token {:type :auto})]
             ;; Wait for a message so that we know the channel is open.
             (async/<! (:ch-recv chsk))
             (reset! connecting? false)
             (sente/start-client-chsk-router! (:ch-recv chsk)
              (fn [message]
                (re-frame/dispatch (:event message))))
             (re-frame/dispatch-sync [::server-connection chsk]))))))))

;;;;; Sente internal events.

(re-frame/reg-event-fx
 :chsk/handshake
 (fn [_ _]))

(re-frame/reg-event-fx
 :chsk/state
 (fn [_ _]))

(re-frame/reg-event-fx
 :chsk/ping
 (fn [_ _]))

(re-frame/reg-event-fx
 :chsk/timeout
 (fn [_ _]
   (log/warn "Server websocker connection timed out.")))

(re-frame/reg-event-fx
 :chsk/recv
 (fn [_ e]
   (log/error "Received broadcast message from server"
              e
              "Broadcast support is not currently implemented.")))
