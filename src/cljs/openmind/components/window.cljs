(ns openmind.components.window
  (:require [openmind.components.search :as search]
            [openmind.events :as events]
            [openmind.subs :as subs]
            [re-frame.core :as re-frame]
            reitit.frontend.easy))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Page Level
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def href reitit.frontend.easy/href)

(defn login-link []
  [:a {:href (href :login)} "login"])

(defn logout-link []
  ;; TODO: Make this a real page.
  [:a {:on-click #(re-frame/dispatch [::events/logout])} "logout"])

(defn create-extract-link []
  [:a {:href (href :extract/create)} "create new extract"])

;;;;; Login

(re-frame/reg-sub
 ::stay-logged-in?
 (fn [db]
   (::stay-logged-in? db)))

(re-frame/reg-event-db
 ::set-stay-logged-in
 (fn [db [_ v]]
   (assoc db ::stay-logged-in? v)))

(re-frame/reg-event-fx
 ::login
 (fn [cofx [_ stay? service]]
   ;; Only option
   (when (= service :orcid)
     {::nav-out (str "/login/orcid?stay=" (boolean stay?))})))

(re-frame/reg-fx
 ::nav-out
 (fn [url]
   (-> js/document
       .-location
       (set! url))))

(defn login-page []
  (let [stay? @(re-frame/subscribe [::stay-logged-in?])]
    [:div.flex.flex-column.left.mt2.ml2
     [:button.p1 {:on-click #(re-frame/dispatch [::login stay? :orcid])}
      [:img {:src "images/orcid.png"
             :style {:vertical-align :bottom}}]
      [:span.pl1 " login with Orcid"]]
     [:button.mt1.p1 {:disabled true}
      [:span "login via Orcid is the only method available at present"]]
     [:div.mt2
      [:label.pr1 {:for "stayloggedin"} [:b "stay logged in?"]]
      [:input {:type     :checkbox
               :checked  stay?
               :on-click #(re-frame/dispatch
                           [::set-stay-logged-in (not stay?)])
               :id       "stayloggedin"}]
      [:p.pl2.pt1.smaller.justify {:style {:max-width "25rem"}}
       (str
        "This checkbox doesn't actually do anything right now."
        " It's only here because it's expected."
        " You will stay logged in until either you log out,"
        " or the server gets updated,"
        " whichever comes first.")
       #_(if stay?
         "You will remain logged in until you explicitly log out."
         "You will be logged out automatically in 12 hours.")]]
     [:p.small.mt2.justify {:style {:max-width "24.5rem"}}
      [:em
       "This site uses cookies solely to maintain login information."]]]))

(defn logged-in-menu-items []
  [[create-extract-link]
   [logout-link]])

(def anon-menu-items
  [[login-link]])

(defn fake-key [xs]
  (map-indexed (fn [i x]
                 (with-meta x (assoc (meta x) :key i)))
               xs))

(defn menu []
  (let [login @(re-frame/subscribe [::subs/login-info])]
    [:div.search-result.padded.absolute.bg-light-grey.wide.pb2.pl1.pr1
     {:style          {:top     5
                       :left    5
                       :opacity 0.95}
      :id             "nav-menu"
      :on-mouse-leave #(re-frame/dispatch [::events/close-menu])}
     [:div.mt4
      (when (seq login)
        [:span "welcome " (:name login)])]
     [:hr.mb1.mt1]
     (fake-key
      (interpose [:hr.mb1.mt1]
                 (if (seq login)
                   (logged-in-menu-items)
                   anon-menu-items)))]))


(defn title-bar []
  (let [search-term (-> (re-frame/subscribe [:route])
                        deref
                        :parameters
                        :query
                        :term)]
    [:div
     [:div.flex.space-between.mr2
      [:button.z100
       {:on-click #(re-frame/dispatch (if @(re-frame/subscribe [::subs/menu-open?])
                                        [::events/close-menu]
                                        [::events/open-menu]))}
       [:span.ham "Ξ"]]
      [:a.ctext.grow-1.pl1.pr1.xxl.pth.plain
       {:href (href :search)
        :style {:cursor :pointer}}
       "open" [:b "mind"]]
      ;; REVIEW: I think this component should be with the rest of the search
      ;; logic.
      [:input.grow-2 (merge {:type :text
                             :on-change (fn [e]
                                          (let [v (-> e .-target .-value)]
                                            (re-frame/dispatch
                                             [::search/update-term v])))}
                            (if (empty? search-term)
                              {:value nil
                               :placeholder "specific term"}
                              {:value search-term}))]]
     (when @(re-frame/subscribe [::subs/menu-open?])
       [menu])]))

(defn status-message-bar [{:keys [status message]}]
  [:div.pt1.pb1.pl1
   {:class (if (= status :success)
             "bg-green"
             "bg-red")}
   [:span message]])

(defn main [content]
  (let [status-message @(re-frame/subscribe [::subs/status-message])]
    [:div.padded
     [title-bar]
     (when status-message
       [:div.vspacer
        [status-message-bar status-message]])
     [:div.vspacer]
     [content]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Main Routing Table
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def other-routes
  [["/login" {:name      :login
              :component login-page}]])
