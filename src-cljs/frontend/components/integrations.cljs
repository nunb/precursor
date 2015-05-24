(ns frontend.components.integrations
  (:require [cljs.core.async :as async]
            [datascript :as d]
            [frontend.components.common :as common]
            [frontend.db :as fdb]
            [frontend.sente :as sente]
            [frontend.urls :as urls]
            [frontend.utils :as utils]
            [om.dom :as dom]
            [om.core :as om]
            [taoensso.sente])
  (:require-macros [sablono.core :refer (html)]
                   [cljs.core.async.macros :as am :refer [go]])
  (:import [goog.ui IdGenerator]))

(defn slack-form [app owner]
  (reify
    om/IDisplayName (display-name [_] "Slack Form")
    om/IInitState (init-state [_] {:channel-name nil :webhook-url nil})
    om/IRenderState
    (render-state [_ {:keys [channel-name webhook-url submitting? error]}]
      (let [submit-fn (fn []
                        (om/set-state! owner :submitting? true)
                        (go (let [resp (async/<! (sente/ch-send-msg (:sente app)
                                                                    [:team/create-slack-integration
                                                                     {:slack-hook/channel-name channel-name
                                                                      :slack-hook/webhook-url webhook-url
                                                                      :team/uuid (get-in app [:team :team/uuid])}]
                                                                    30000
                                                                    (async/promise-chan)))]
                              (if-not (taoensso.sente/cb-success? resp)
                                (om/update-state! owner (fn [s] (assoc s
                                                                       :submitting? false
                                                                       :error "The request timed out, please refresh and try again.")))
                                (do
                                  (om/update-state! owner #(assoc % :submitting? false :error nil))
                                  (if (= :success (:status resp))
                                    (do
                                      (om/update-state! owner #(assoc % :channel-name nil :webhook-url nil))
                                      (d/transact! (om/get-shared owner :team-db) (:entities resp)))
                                    (om/set-state! owner :error (:error-msg resp))))))))]
        (html
         [:div.content.make
          [:form {:on-submit submit-fn}
           [:div.adaptive.make
            [:input {:type "text"
                     :required "true"
                     :tabIndex 1
                     :value (or channel-name "")
                     :on-change #(om/set-state! owner :channel-name (.. % -target -value))
                     :on-key-down #(when (= "Enter" (.-key %))
                                     (.focus (om/get-node owner "webhook-input")))}]
            [:label {:data-label "Channel"
                     :data-placeholder "What's the channel name?"}]]
           [:div.adaptive.make
            [:input {:ref "webhook-input"
                     :type "text"
                     :required "true"
                     :tabIndex 1
                     :value (or webhook-url "")
                     :on-change #(om/set-state! owner :webhook-url (.. % -target -value))
                     :on-key-down #(when (= "Enter" (.-key %))
                                     (.focus (om/get-node owner "submit-button"))
                                     (submit-fn))}]
            [:label {:data-label "Webhook"
                     :data-placeholder "What's the webhook url?"}]]
           [:div.menu-buttons.make
            [:input.menu-button {:type "submit"
                                 :ref "submit-button"
                                 :tabIndex 1
                                 :value (if submitting?
                                          "Saving..."
                                          "Save webhook.")
                                 :on-click #(do (submit-fn)
                                              (utils/stop-event %))}]]]
          (when error
            [:div.slack-form-error error])])))))

(defn delete-slack-hook [{:keys [slack-hook team-uuid doc-id]} owner]
  (reify
    om/IDisplayName (display-name [_] "Delete slack hook")
    om/IInitState (init-state [_] {:submitting? nil})
    om/IRenderState
    (render-state [_ {:keys [submitting? submitted? error]}]
      (html
       [:a.fork {:class (when (or submitting? submitted?) "disabled")
                 :title "Remove channel from list."
                 :role "button"
                 :key (:db/id slack-hook)
                 :on-click (fn [e]
                             (om/set-state! owner :submitting? true)
                             (go (let [resp (async/<! (sente/ch-send-msg (om/get-shared owner :sente)
                                                                         [:team/delete-slack-integration
                                                                          {:slack-hook-id (:db/id slack-hook)
                                                                           :doc-id doc-id
                                                                           :team/uuid team-uuid}]
                                                                         30000
                                                                         (async/promise-chan)))]
                                   (if-not (taoensso.sente/cb-success? resp)
                                     (om/update-state! owner (fn [s] (assoc s
                                                                       :submitting? false
                                                                       :error "The request timed out, please try again.")))
                                     (do
                                       (om/update-state! owner #(assoc % :submitting? false :error nil))
                                       (if (= :success (:status resp))
                                         (do
                                           (om/set-state! owner :submitted? true))
                                         (om/set-state! owner :error (:error-msg resp))))))))}
        (cond submitting? "Deleting..."
              submitted? "Deleted"
              error error
              :else
              (common/icon :times))]))))

(defn post-to-slack-button [{:keys [slack-hook team-uuid doc-id]} owner]
  (reify
    om/IDisplayName (display-name [_] "Slack button")
    om/IInitState (init-state [_] {:submitting? nil})
    om/IRenderState
    (render-state [_ {:keys [submitting? submitted? error]}]
      (html
       [:div.vein-fork.make
        [:a.vein {:class (when (or submitting? submitted?) "disabled")
                  :title (str "Send preview of this doc to "
                              (when-not (= \# (first (:slack-hook/channel-name slack-hook))) "#")
                              (:slack-hook/channel-name slack-hook)
                              " in Slack.")
                  :role "button"
                  :key (:db/id slack-hook)
                  :on-click (fn [e]
                              (om/set-state! owner :submitting? true)
                              (go (let [resp (async/<! (sente/ch-send-msg (om/get-shared owner :sente)
                                                                          [:team/post-doc-to-slack
                                                                           {:slack-hook-id (:db/id slack-hook)
                                                                            :doc-id doc-id
                                                                            :team/uuid team-uuid}]
                                                                          30000
                                                                          (async/promise-chan)))]
                                    (if-not (taoensso.sente/cb-success? resp)
                                      (om/update-state! owner (fn [s] (assoc s
                                                                        :submitting? false
                                                                        :error "The request timed out, please try again.")))
                                      (do
                                        (om/update-state! owner #(assoc % :submitting? false :error nil))
                                        (if (= :success (:status resp))
                                          (do
                                            (om/set-state! owner :submitted? true)
                                            (js/setTimeout #(when (om/mounted? owner) (om/set-state! owner :submitted? nil))
                                                           1000))
                                          (om/set-state! owner :error (:error-msg resp))))))))}
         (common/icon :slack)
         [:span
          (cond submitting? "Sending..."
                submitted? "Sent!"
                error error
                :else
                (:slack-hook/channel-name slack-hook))]]
        (om/build delete-slack-hook {:slack-hook slack-hook
                                     :doc-id doc-id
                                     :team-uuid team-uuid})]))))

(defn slack-hooks [app owner]
  (reify
    om/IDisplayName (display-name [_] "Slack hooks")
    om/IInitState (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (fdb/add-attribute-listener (om/get-shared owner :team-db)
                                  :slack-hook/channel-name
                                  (om/get-shared owner :listener-key)
                                  #(om/refresh! owner)))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-attribute-listener (om/get-shared owner :team-db)
                                     :slack-hook/channel-name
                                     (om/get-shared owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! team-db]} (om/get-shared owner)]
        (html
         [:div.slack-channels
          [:div.vein-fork.make
          [:a.vein {:role "button"
                    :on-click (if (om/get-state owner :show-form?)
                                #(do
                                   (om/set-state! owner :show-form? false)
                                   (om/set-state! owner :show-info? false))
                                #(om/set-state! owner :show-form? true))}
           (common/icon :plus)
           [:span "Add a Channel"]]
          (when (om/get-state owner :show-form?)
          [:a.fork.make {:role "button"
                    :on-click (if (om/get-state owner :show-info?)
                                #(om/set-state! owner :show-info? false)
                                #(om/set-state! owner :show-info? true))}
           (common/icon :info)])]
          (when (om/get-state owner :show-info?)
            [:div.content.make
            [:p "Visit "
             [:a {:href "https://slack.com/services/new"
                  :target "_blank"
                  :role "button"}
              "Slack's integrations page"]
             ", then scroll to the bottom and add a new incoming webhook. "
             "Choose a channel, copy its webhook url, and then use it to fill out the following form: "]])
          (when (om/get-state owner :show-form?)
          (om/build slack-form app))
          (for [slack-hook (->> (d/datoms @team-db :aevt :slack-hook/channel-name)
                                (map :e)
                                (map #(d/entity @team-db %))
                                (sort-by :db/id))]
            (om/build post-to-slack-button {:doc-id (:document/id app)
                                            :team-uuid (get-in app [:team :team/uuid])
                                            :slack-hook slack-hook}
                      {:react-key (:db/id slack-hook)}))])))))


(defn slack [app owner]
  (reify
    om/IDisplayName (display-name [_] "Slack integration")
    om/IInitState (init-state [_] {:channel-name nil :webhook-url nil})
    om/IRender
    (render [_]
      (html
       [:section.menu-view.post-to-slack
        [:div.slack-comment.content.make
         [:div.adaptive
          [:textarea {:required "true"}]
          [:label {:data-placeholder "Add optional comment to Slack post?"
                   :data-focus "Add optional comment to Slack post"
                   :data-typing "Your comment looks great!"
                   :data-label "Your comment"}]]]
        (om/build slack-hooks app)]))))
