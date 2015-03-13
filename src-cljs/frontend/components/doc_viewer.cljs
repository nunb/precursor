(ns frontend.components.doc-viewer
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [cljs-time.core :as time]
            [datascript :as d]
            [frontend.async :refer [put!]]
            [frontend.auth :as auth]
            [frontend.components.common :as common]
            [frontend.datascript :as ds]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.date :refer (date->bucket)]
            [goog.date]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [sablono.core :refer (html)])
  (:import [goog.ui IdGenerator]))

(defn signup-prompt [app owner]
  (reify
    om/IDisplayName (display-name [_] "Doc Viewer Signup Prompt")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.menu-view
          {:class (str "menu-prompt-" "username")}
          [:article
           [:h2.make
            "Remember that one idea?"]
           [:p.make
            "Neither do we—well, not yet at least.
            Sign up and we'll remember your ideas for you.
            Never lose a great idea again!"]
           [:a.menu-button.make
            {:href (auth/auth-url :source "your-docs-overlay")
             :role "button"}
            "Sign Up"]]])))))

(defn docs-list [docs owner]
  (reify
    om/IDisplayName (display-name [_] "Doc Viewer Docs List")
    om/IRender
    (render [_]
      (html
       [:div
        (for [[time-bucket bucket-docs]
              (reverse (sort-by #(:last-updated-instant (first (last %)))
                                (group-by #(date->bucket (:last-updated-instant %)) docs)))]
          (list*
           (html [:div.recent-time-group.make
                  [:h2
                   time-bucket]])

           (for [doc bucket-docs]
             (html
              [:div.recent-doc.make
               [:a.recent-doc-thumb
                {:href (str "/document/" (:db/id doc))}
                [:img
                 {:src (str "/document/" (:db/id doc) ".svg")}]
                [:img.loading-image]
                [:i.loading-ellipses
                 [:i "."]
                 [:i "."]
                 [:i "."]]]
               [:div.recent-doc-title
                [:a
                 {:href (str "/document/" (:db/id doc))}
                 (str (:db/id doc))]]]))))]))))

(defn dummy-docs [current-doc-id doc-count]
  (repeat doc-count {:db/id current-doc-id
                     :last-update-instant (js/Date.)}))

(defn doc-viewer* [app owner {:keys [docs-path] :as opts}]
  (reify
    om/IDisplayName (display-name [_] "Doc Viewer*")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            app-docs (get-in app docs-path)
            display-docs (cond
                           (nil? app-docs)
                           (dummy-docs (:document/id app) 5) ;; loading state

                           (empty? app-docs) (dummy-docs (:document/id app) 1) ;; empty state

                           :else
                           (->> app-docs
                             (filter :last-updated-instant)
                             (sort-by :last-updated-instant)
                             (reverse)
                             (take 100)))]
        (html
         [:div.menu-view
          {:class (when (nil? app-docs) "loading")}
          [:article
           (if (seq display-docs)
             (om/build docs-list display-docs))]])))))

;; Four states
;; 1. Logged out
;; 2. Loading
;; 3. No docs
;; 4. Docs!

(defn doc-viewer [app owner]
  (reify
    om/IDisplayName (display-name [_] "Doc Viewer")
    om/IRender
    (render [_]
      (if (:cust app)
        (om/build doc-viewer* app {:opts {:docs-path [:cust :touched-docs]}}) ;; states 2, 3, 4
        (om/build signup-prompt app) ;; state 1
        ))))

(defn team-doc-viewer [app owner]
  (reify
    om/IDisplayName (display-name [_] "Team Doc Viewer")
    om/IRender
    (render [_]
      (om/build doc-viewer* app {:opts {:docs-path [:team :recent-docs]}}))))
