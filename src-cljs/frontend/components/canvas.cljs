(ns frontend.components.canvas
  (:require [cljs.core.async :refer [put!]]
            [clojure.set :as set]
            [clojure.string :as str]
            [datascript :as d]
            [frontend.auth :as auth]
            [frontend.camera :as cameras]
            [frontend.colors :as colors]
            [frontend.components.common :as common]
            [frontend.cursors :as cursors]
            [frontend.datascript :as ds]
            [frontend.layers :as layers]
            [frontend.models.layer :as layer-model]
            [frontend.settings :as settings]
            [frontend.state :as state]
            [frontend.svg :as svg]
            [frontend.utils :as utils :include-macros true]
            [goog.dom]
            [goog.style]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [sablono.core :refer (html)])
  (:import [goog.ui IdGenerator]))

(def tools-templates
  {:circle {:type "ellipse"
            :path "M128,0v128l110.9-64C216.7,25.7,175.4,0,128,0z"
            :hint "Ellipse Tool (L)"
            :icon :stroke-ellipse}
   :rect   {:type "rectangle"
            :path "M238.9,192c10.9-18.8,17.1-40.7,17.1-64s-6.2-45.2-17.1-64 L128,128L238.9,192z"
            :hint "Rectangle Tool (M)"
            :icon :stroke-rectangle}
   :line   {:type "line"
            :path "M238.9,192L128,128v128C175.4,256,216.7,230.3,238.9,192z"
            :hint "Line Tool (\\)"
            :icon :stroke-line}
   :pen    {:type "pencil"
            :path "M17.1,192c22.1,38.3,63.5,64,110.9,64V128L17.1,192z"
            :hint "Pencil Tool (N)"
            :icon :stroke-pencil}
   :text   {:type "text"
            :path "M17.1,64C6.2,82.8,0,104.7,0,128s6.2,45.2,17.1,64L128,128 L17.1,64z"
            :hint "Text Tool (T)"
            :icon :stroke-text}
   :select {:type "select"
            :path "M128,0C80.6,0,39.3,25.7,17.1,64L128,128V0z"
            :hint "Select Tool (V)"
            :icon :stroke-cursor}})

(defn radial-menu [app owner]
  (reify
    om/IDisplayName (display-name [_] "Radial Menu")
    om/IRender
    (render [_]
      (let [{:keys [cast! handlers]} (om/get-shared owner)]
        (html
         [:a.radial-menu {:style {:top  (- (get-in app [:menu :y]) 128)
                                  :left (- (get-in app [:menu :x]) 128)}}
          [:svg.radial-buttons {:width "256" :height "256"}
           (for [[tool template] tools-templates]
             (html
              [:g.radial-button {:class (str "tool-" (:type template))}
               [:title (:hint template)]
               [:path.radial-pie {:d (:path template)
                                  :key tool
                                  :on-mouse-up #(do (cast! :tool-selected [tool]))
                                  :on-touch-end #(do (cast! :tool-selected [tool]))}]
               [:path.radial-icon {:class (str "shape-" (:type template))
                                   :d (get common/icon-paths (:icon template))}]]))
           [:circle.radial-point {:cx "128" :cy "128" :r "4"}]]])))))

(defn radial-hint [app owner]
  (reify
    om/IDisplayName (display-name [_] "Radial Hint")
    om/IRender
    (render [_]
      (let [mouse (cursors/observe-mouse owner)]
        (html
         [:div.radial-hint {:style {:top  (+ (or (:y mouse) (- 1000)) 16)
                                    :left (+ (or (:x mouse) (- 1000)) 16)}}
          (if (= :touch (get-in app [:mouse-type]))
            "Tap and hold to select tool"
            "Right-click.")])))))

;; layers are always denominated in absolute coordinates
;; transforms are applied to handle panning and zooming

(defmulti svg-element (fn [layer] (:layer/type layer)))

(defmethod svg-element :default
  [layer]
  (print "No svg element for " layer))

(defn maybe-add-selected [svg-layer layer]
  (if (:selected? layer)
    (update-in svg-layer [:className] #(str % " selected"))
    svg-layer))

(defn maybe-add-deleted [svg-layer layer]
  (if (:layer/deleted layer)
    (update-in svg-layer [:className] #(str % " layer-deleted"))
    svg-layer))

(defmethod svg-element :layer.type/rect
  [layer]
  (-> (svg/layer->svg-rect layer)
    (maybe-add-selected layer)
    (maybe-add-deleted layer)
    (clj->js)
    (dom/rect)))

(defmethod svg-element :layer.type/text
  [layer]
  (let [text-props (svg/layer->svg-text layer)]
    (-> text-props
      (maybe-add-selected layer)
      (maybe-add-deleted layer)
      (clj->js)
      (#(apply dom/text % (reduce (fn [tspans text]
                                    (conj tspans (dom/tspan
                                                  #js {:dy (if (seq tspans) "1em" "0")
                                                       :x (:x text-props)}
                                                  text)))
                                  [] (str/split (:layer/text layer) #"\n")))))))

(defmethod svg-element :layer.type/line
  [layer]
  (-> (svg/layer->svg-line layer)
    (merge layer)
    (update-in [:className] #(str % " shape-layer"))
    (maybe-add-selected layer)
    (maybe-add-deleted layer)
    (clj->js)
    (dom/line)))

(defmethod svg-element :layer.type/path
  [layer]
  (-> (merge (dissoc layer :points) (svg/layer->svg-path layer))
    (update-in [:className] #(str % " shape-layer"))
    (maybe-add-selected layer)
    (maybe-add-deleted layer)
    (clj->js)
    (dom/path)))

(defmethod svg-element :layer.type/group
  [layer]
  (print "Nothing to do for groups, yet."))

(defn handles [layer owner]
  (reify
    om/IDisplayName (display-name [_] "Canvas Handles")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner [:cast!])
            width (js/Math.abs (- (:layer/start-x layer) (:layer/end-x layer)))
            height (js/Math.abs (- (:layer/start-y layer) (:layer/end-y layer)))
            ;; easier than calcing width, b/c we can just multiply by 2
            handle-offset (max 1
                               (min 4
                                    (int (/ (Math/sqrt (+ (* width width) (* height height)))
                                            6))))]
        (apply dom/g #js {:className "edit-handles"}
               (for [[x y] (layers/endpoints layer)]
                 (dom/rect #js {:className (str "edit-handle "
                                                (if (= x (max (:layer/start-x layer) (:layer/end-x layer)))
                                                  "right "
                                                  "left ")
                                                (if (= y (max (:layer/start-y layer) (:layer/end-y layer)))
                                                  "bottom "
                                                  "top "))
                                :x (- x handle-offset)
                                :y (- y handle-offset)
                                :width (* 2 handle-offset)
                                :height (* 2 handle-offset)
                                :onMouseDown #(do (.stopPropagation %)
                                                  (cast! :drawing-edited {:layer layer
                                                                          :x x
                                                                          :y y}))
                                :key (str "edit-handle-" x "-" y)})))))))

(defn layer-group [{:keys [layer tool selected? part-of-group? live?]} owner]
  (reify
    om/IDisplayName (display-name [_] "Layer Group")
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            invalid? (and (:layer/ui-target layer)
                          (not (pos? (layer-model/count-by-ui-id @db (:layer/ui-target layer)))))
            show-handles? (and (not part-of-group?)
                               selected?
                               (contains? #{:layer.type/rect :layer.type/line} (:layer/type layer))
                               (or (= :select tool)
                                   (and (= :circle tool) (layers/circle? layer))
                                   (and (= :line tool) (= :layer.type/line (:layer/type layer)))
                                   (and (= :rect tool) (not (layers/circle? layer)))))]
        (dom/g #js {:className (str "layer "
                                    (when (= :select tool) "selectable-group ")
                                    (when invalid? "invalid "))
                    :key (str (:db/id layer) live?)}

          (when (and show-handles? (layers/circle? layer))
            (let [layer (layers/normalized-abs-coords layer)]
              (dom/rect #js {:className "handle-outline"
                             :x (:layer/start-x layer)
                             :y (:layer/start-y layer)
                             :width (- (:layer/end-x layer) (:layer/start-x layer))
                             :height (- (:layer/end-y layer) (:layer/start-y layer))
                             :fill "none"
                             :strokeWidth 1})))

          (svg-element (assoc layer
                              :selected? selected?
                              :className (str "selectable-layer layer-handle "
                                              (when (and (= :layer.type/text (:layer/type layer))
                                                         (= :text tool)) "editable ")
                                              (when (:layer/signup-button layer)
                                                " signup-layer"))
                              :key (str "selectable-" (:db/id layer))
                              :onClick (when (:layer/signup-button layer)
                                         #(do
                                            (.preventDefault %)
                                            (set! js/window.location (auth/auth-url :source "prcrsr-bot-drawing"))))
                              :onMouseDown
                              (when-not (:layer/signup-button layer)
                                #(do
                                   (.stopPropagation %)
                                   (let [group? part-of-group?]

                                     (cond
                                       (and (= :text tool)
                                            (= :layer.type/text (:layer/type layer)))
                                       (cast! :text-layer-re-edited layer)

                                       (not= :select tool) nil

                                       (or (= (.-button %) 2)
                                           (and (= (.-button %) 0) (.-ctrlKey %)))
                                       (cast! :layer-properties-opened {:layer layer
                                                                        :x (first (cameras/screen-event-coords %))
                                                                        :y (second (cameras/screen-event-coords %))})


                                       (and (.-altKey %) group?)
                                       (cast! :group-duplicated
                                              {:x (first (cameras/screen-event-coords %))
                                               :y (second (cameras/screen-event-coords %))})

                                       (and (.-altKey %) (not group?))
                                       (cast! :layer-duplicated
                                              {:layer layer
                                               :x (first (cameras/screen-event-coords %))
                                               :y (second (cameras/screen-event-coords %))})

                                       (and (.-shiftKey %) selected?)
                                       (cast! :layer-deselected {:layer layer})


                                       group?
                                       (cast! :group-selected {:x (first (cameras/screen-event-coords %))
                                                               :y (second (cameras/screen-event-coords %))})

                                       :else
                                       (cast! :layer-selected {:layer layer
                                                               :x (first (cameras/screen-event-coords %))
                                                               :y (second (cameras/screen-event-coords %))
                                                               :append? (.-shiftKey %)})))))))
          (when-not (= :layer.type/text (:layer/type layer))
            (svg-element (assoc layer
                                :selected? selected?
                                :className (str "layer-outline ")
                                :key (:db/id layer))))
          ;; TODO: figure out what to do with this title
          ;; (when invalid?
          ;;   (dom/title nil
          ;;              (str "This action links to \""  (:layer/ui-target layer) "\", but no shapes have that name."
          ;;                   " Right-click on a shape's border to name it " (:layer/ui-target layer))))

          (when show-handles?
            (om/build handles layer))

          (when (:layer/ui-target layer)
            (svg-element (assoc layer
                                :selected? selected?
                                :padding 4 ;; only works for rects right now
                                :onMouseDown #(when (= tool :select)
                                                (.stopPropagation %)
                                                (cond
                                                  (or (= (.-button %) 2)
                                                      (and (= (.-button %) 0) (.-ctrlKey %)))
                                                  (cast! :layer-properties-opened {:layer layer
                                                                                   :x (first (cameras/screen-event-coords %))
                                                                                   :y (second (cameras/screen-event-coords %))})

                                                  selected?
                                                  (cast! :group-selected
                                                         {:x (first (cameras/screen-event-coords %))
                                                          :y (second (cameras/screen-event-coords %))})

                                                  :else
                                                  (cast! :canvas-aligned-to-layer-center
                                                         {:ui-id (:layer/ui-target layer)
                                                          :canvas-size (utils/canvas-size)})))
                                :onTouchStart (fn [event]
                                                (when (= (.-length (.-touches event)) 1)
                                                  (utils/stop-event event)
                                                  (cast! :canvas-aligned-to-layer-center
                                                         {:ui-id (:layer/ui-target layer)
                                                          :canvas-size (utils/canvas-size)})))

                                :className (str "action interactive-fill "
                                                (when part-of-group?
                                                  "selected-group ")
                                                (when invalid?
                                                  "invalid"))
                                :key (str "action-" (:db/id layer))))))))))

(defn svg-layers [{:keys [tool] :as data} owner]
  (reify
    om/IDisplayName (display-name [_] "SVG Layers")
    om/IInitState
    (init-state [_]
      {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when (seq (:tx-data tx-report))
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :db) (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            selected-eids (:selected-eids (cursors/observe-selected-eids owner))
            selected-group? (< 1 (count selected-eids))
            sub-eids (:entity-ids (cursors/observe-subscriber-entity-ids owner))
            editing-eids (:editing-eids (cursors/observe-editing-eids owner))
            editing-eids (set/union sub-eids editing-eids)
            layers (ds/touch-all '[:find ?t :where [?t :layer/name]] @db)
            renderable-layers (remove #(contains? editing-eids (:db/id %)) layers)
            {idle-layers false live-layers true} (group-by (comp boolean :layer/ui-target)
                                                           renderable-layers)]
        ;; TODO: this should probably be split into a couple of components
        (dom/g #js {:className (if (= :select tool)
                                 "interactive"
                                 "static")}
          (apply dom/g #js {:className "layers idle"}
                 (om/build-all layer-group (mapv (fn [l]
                                                   (let [selected? (contains? selected-eids (:db/id l))
                                                         part-of-group? (and selected? selected-group?)]
                                                     {:live? false
                                                      :tool tool
                                                      :selected? selected?
                                                      :part-of-group? part-of-group?
                                                      :layer l}))
                                                 idle-layers)))
          (apply dom/g #js {:className "layers live"}
                 (om/build-all layer-group (mapv (fn [l]
                                                   (let [selected? (contains? selected-eids (:db/id l))
                                                         part-of-group? (and selected? selected-group?)]
                                                     {:live? true
                                                      :tool tool
                                                      :selected? selected?
                                                      :part-of-group? part-of-group?
                                                      :layer l}))
                                                 live-layers))))))))

(defn subscriber-cursor-icon [tool]
  (case (name tool)
    "pen" :crosshair
    "line" :crosshair
    "rect" :crosshair
    "circle" :crosshair

    "text" :ibeam

    "select" :cursor))

(defn cursor [{:keys [subscriber uuid->cust]} owner]
  (reify
    om/IDisplayName (display-name [_] "Canvas Cursor")
    om/IRender

    (render [_]
      (if (and (:tool subscriber)
               (:show-mouse? subscriber))
        (common/svg-icon (subscriber-cursor-icon (:tool subscriber))
                         {:svg-props {:height 16 :width 16
                                      :className "mouse-tool"
                                      :x (- (first (:mouse-position subscriber)) 8)
                                      :y (- (last (:mouse-position subscriber)) 8)
                                      :key (:client-id subscriber)}
                          :path-props {:className (name
                                                   (colors/find-color
                                                    uuid->cust
                                                    (:cust/uuid subscriber)
                                                    (:client-id subscriber)))}})
        (dom/circle #js {:cx 0 :cy 0 :r 0})))))

(defn cursors [{:keys [client-id uuid->cust]} owner]
  (reify
    om/IDisplayName (display-name [_] "Canvas Cursors")
    om/IRender
    (render [_]
      (let [subscribers (cursors/observe-subscriber-mice owner)]
        (apply dom/g nil
               (for [subscriber (vals (dissoc subscribers client-id))]
                 (om/build cursor {:subscriber subscriber
                                   :uuid->cust uuid->cust}
                           {:react-key (:client-id subscriber)})))))))

(defn single-subscriber-layers [{:keys [subscriber uuid->cust]} owner]
  (reify
    om/IDisplayName (display-name [_] "Single Subscriber Layers")
    om/IRender
    (render [_]
      (apply dom/g nil
             (mapv (fn [l] (svg-element (merge l {:layer/end-x (:layer/current-x l)
                                                  :layer/end-y (:layer/current-y l)
                                                  :strokeDasharray "5,5"
                                                  :layer/fill "none"
                                                  :fillOpacity "0.5"
                                                  :className (name (colors/find-color uuid->cust (:cust/uuid subscriber) (:client-id subscriber)))
                                                  :key (str (:db/id l) "-subscriber-layer-" (:client-id subscriber))}
                                               (when (= :layer.type/text (:layer/type l))
                                                 {:layer/stroke "none"}))))
                   (:layers subscriber))))))

(defn subscribers-layers [{:keys [client-id uuid->cust]} owner]
  (reify
    om/IDisplayName (display-name [_] "Subscribers Layers")
    om/IRender
    (render [_]
      (let [subscribers (cursors/observe-subscriber-layers owner)]
        (apply dom/g nil
               (for [subscriber (vals (dissoc subscribers client-id))]
                 (om/build single-subscriber-layers {:subscriber subscriber
                                                     :uuid->cust uuid->cust} {:react-key (:client-id subscriber)})))))))

(defn text-input [layer owner]
  (reify
    om/IDisplayName (display-name [_] "Canvas Text Input")
    om/IDidMount
    (did-mount [_]
      (om/set-state! owner
                     :input-min-width
                     (.-width (.getBoundingClientRect (om/get-node owner "text-size-helper"))))
      (.focus (om/get-node owner "input")))
    om/IDidUpdate
    (did-update [_ _ _]
      (utils/maybe-set-state! owner :input-min-width
                              (.-width (.getBoundingClientRect (om/get-node owner "text-size-helper"))))
      (.focus (om/get-node owner "input")))
    om/IInitState
    (init-state [_]
      {:input-min-width 0})
    om/IRender
    (render [_]
      (let [{:keys [cast!]} (om/get-shared owner)
            text-style {:font-size (:layer/font-size layer 20)}]
        (dom/g #js {:key "text-input-group"}
          (svg-element (assoc layer
                              :className "text-size-helper"
                              :ref "text-size-helper"))
          (dom/foreignObject #js {:width "100%"
                                  :height "100%"
                                  :x (:layer/start-x layer)
                                  ;; TODO: defaults for each layer when we create them
                                  :y (- (:layer/start-y layer) (:layer/font-size layer 22))}
            (dom/form #js {:className "svg-text-form"
                           :onMouseDown #(.stopPropagation %)
                           :onWheel #(.stopPropagation %)
                           :onSubmit (fn [e]
                                       (let [bbox (.getBoundingClientRect (om/get-node owner "text-size-helper"))]
                                         (cast! :text-layer-finished {:bbox {:width (.-width bbox)
                                                                             :height (.-height bbox)}})
                                         (utils/stop-event e)))
                           :onMouseMove (when-not (:moving? layer)
                                          #(.stopPropagation %))
                           :onKeyDown #(cond (= "Enter" (.-key %))
                                             (let [bbox (.getBoundingClientRect (om/get-node owner "text-size-helper"))]
                                               (cast! :text-layer-finished {:bbox {:width (.-width bbox)
                                                                                   :height (.-height bbox)}})
                                               (utils/stop-event %))

                                             (= "Escape" (.-key %))
                                             (do (cast! :cancel-drawing)
                                                 (utils/stop-event %))

                                             :else nil)}
                      ;; TODO: experiment with a contentEditable div
                      (dom/input #js {:type "text"
                                      :className "text-layer-input"
                                      ;; Don't let the user accidentally select the text when they're dragging it
                                      :placeholder "Type something..."
                                      :value (or (:layer/text layer) "")
                                      ;; TODO: defaults for each layer when we create them
                                      :style (clj->js (merge text-style
                                                             {:width (+ 50 (max 160 (om/get-state owner :input-min-width)))}))
                                      :ref "input"
                                      :onChange #(let [bbox (.getBoundingClientRect (om/get-node owner "text-size-helper"))]
                                                   ;; this will always be a letter behind, but we sometimes
                                                   ;; call text-layer-finished from a place that doesn't
                                                   ;; have access to the DOM
                                                   ;; TODO: can we save on focus-out instead?
                                                   (cast! :text-layer-edited {:value (.. % -target -value)
                                                                              :bbox {:width (.-width bbox)
                                                                                     :height (.-height bbox)}}))}))))))))

(defn layer-properties [{:keys [layer x y]} owner]
  (reify
    om/IDisplayName (display-name [_] "Canvas Layer Props")
    om/IInitState (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))
                                   :input-expanded false})
    om/IDidMount
    (did-mount [_]
      (.focus (om/get-node owner "id-input"))
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when (some #(= (:a %) :layer/ui-id) (:tx-data tx-report))
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :db) (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            ;; TODO: figure out how to handle nils in datascript
            targets (->> (d/q '{:find [?id]
                                :where [[_ :layer/ui-id ?id]]}
                              @db)
                         (map first)
                         (remove nil?)
                         sort)]
        (dom/foreignObject #js {:width "100%"
                                :height "100%"
                                :x x
                                ;; TODO: defaults for each layer when we create them
                                :y y}
          (dom/form #js {:className "layer-properties"
                         :onMouseDown #(.stopPropagation %)
                         :onMouseUp #(.stopPropagation %)
                         :onWheel #(.stopPropagation %)
                         :onSubmit (fn [e]
                                     (cast! :layer-properties-submitted)
                                     (utils/stop-event e))
                         :onKeyDown #(when (= "Enter" (.-key %))
                                       (cast! :layer-properties-submitted)
                                       (utils/stop-event %))}
                    (dom/input #js {:type "text"
                                    :ref "id-input"
                                    :className "layer-property-id"
                                    :onClick #(.focus (om/get-node owner "id-input"))
                                    :required "true"
                                    :data-adaptive ""
                                    :value (or (:layer/ui-id layer) "")
                                    ;; TODO: defaults for each layer when we create them
                                    :onChange #(cast! :layer-ui-id-edited {:value (.. % -target -value)})})
                    (dom/label #js {:data-placeholder "name"
                                    :data-placeholder-nil "define a name"
                                    :data-placeholder-busy "defining name"})
                    (when-not (= :layer.type/line (:layer/type layer))
                      (dom/input #js {:type "text"
                                      :ref "target-input"
                                      :className (if (om/get-state owner :input-expanded)
                                                   "layer-property-target expanded"
                                                   "layer-property-target")
                                      :required "true"
                                      :data-adaptive ""
                                      :value (or (:layer/ui-target layer) "")
                                      :onClick #(.focus (om/get-node owner "target-input"))
                                      :onChange #(cast! :layer-ui-target-edited {:value (.. % -target -value)})}))
                    (when-not (= :layer.type/line (:layer/type layer))
                      (dom/label #js {:data-placeholder "is targeting"
                                      :data-placeholder-nil "define a target"
                                      :data-placeholder-busy "defining target"}))
                    (when-not (= :layer.type/line (:layer/type layer))
                      (when (seq targets)
                        (dom/button #js {:className "layer-property-button"
                                         :onClick #(do (om/update-state! owner :input-expanded not)
                                                       (utils/stop-event %))}
                                    "...")))
                    (apply dom/div #js {:className (if (om/get-state owner :input-expanded)
                                                     "property-dropdown-targets expanded"
                                                     "property-dropdown-targets")}
                           (for [target targets]
                             (dom/a #js {:className "property-dropdown-target"
                                         :role "button"
                                         :onClick #(do (cast! :layer-ui-target-edited {:value target})
                                                       (om/set-state! owner :input-expanded false)
                                                       (.focus (om/get-node owner "target-input")))}
                                    target)))))))))

(defn in-progress [{:keys [layer-properties-menu mouse-down]} owner]
  (reify
    om/IDisplayName (display-name [_] "In Progress Layers")
    om/IRender
    (render [_]
      (let [drawing (cursors/observe-drawing owner)]
        (dom/g nil
          (when (and (:in-progress? drawing)
                     (= :layer.type/text (get-in drawing [:layers 0 :layer/type])))
            (om/build text-input (assoc (get-in drawing [:layers 0])
                                        :moving? mouse-down)))

          (when (:opened? layer-properties-menu)
            (om/build layer-properties {:layer (:layer layer-properties-menu)
                                        :x (:x layer-properties-menu)
                                        :y (:y layer-properties-menu)}))

          (when-let [sels (cond
                            (:moving? drawing) (:layers drawing)
                            (= :layer.type/text (get-in drawing [:layers 0 :layer/type])) nil
                            (:in-progress? drawing) (:layers drawing)
                            :else nil)]
            (apply dom/g #js {:className "layers"}
                   (map (fn [sel]
                          (let [sel (if (:force-even? sel)
                                      (layers/force-even sel)
                                      sel)
                                sel (merge sel
                                           {:layer/end-x (:layer/current-x sel)
                                            :layer/end-y (:layer/current-y sel)}
                                           (when (or (:moving? drawing)
                                                     (not= :layer.type/text (:layer/type sel)))
                                             {:className "layer-in-progress"})
                                           (when (= :layer.type/group (:layer/type sel))
                                             {:layer/type :layer.type/rect
                                              :className "layer-in-progress selection"
                                              :strokeDasharray "2,3"}))]
                            (svg-element (assoc sel :key (str (:db/id sel) "-in-progress")))))
                        sels))))))))



(defn canvas-grid [camera]
  (dom/defs nil
    (dom/pattern #js {:id           "small-grid"
                      :width        (str (cameras/grid-width camera))
                      :height       (str (cameras/grid-height camera))
                      :patternUnits "userSpaceOnUse"}
                 (dom/path #js {:d           (str "M " (cameras/grid-width camera) " 0 L 0 0 0 " (cameras/grid-width camera))
                                :className   "grid-lines-small"}))
    (dom/pattern #js {:id               "grid"
                      :width            (str (* 10 (cameras/grid-width camera)))
                      :height           (str (* 10 (cameras/grid-height camera)))
                      :patternUnits     "userSpaceOnUse"
                      :patternTransform (str "translate(" (:x camera) "," (:y camera) ")")}
                 (dom/rect #js {:width  (str (* 10 (cameras/grid-width camera)))
                                :height (str (* 10 (cameras/grid-height camera)))
                                :fill   "url(#small-grid)"})
                 (dom/path #js {:d           (str "M " (str (* 10 (cameras/grid-width camera))) " 0 L 0 0 0 " (str (* 10 (cameras/grid-width camera))))
                                :className   "grid-lines-large"}))))

(defn touches->clj [touches]
  (mapv (fn [t]
          {:client-x (aget t "clientX")
           :client-y (aget t "clientY")
           :page-x (aget t "pageX")
           :page-y (aget t "pageY")
           :identifier (aget t "identifier")})
        ;; touches aren't seqable
        (js/Array.prototype.slice.call touches)))

(defn measure [[x1 y1] [x2 y2]]
  (js/Math.sqrt (+ (js/Math.pow (- x2 x1) 2)
                   (js/Math.pow (- y2 y1) 2))))

(defn center [[x1 y1] [x2 y2]]
  [(/ (+ x2 x1) 2)
   (/ (+ y2 y1) 2)])

(defn svg-canvas [app owner]
  (reify
    om/IDisplayName (display-name [_] "SVG Canvas")
    om/IInitState (init-state [_]
                    ;; use an atom for performance, don't want to constantly
                    ;; re-render when we set-state!
                    {:touches (atom nil)})
    om/IRender
    (render [_]
      (let [{:keys [cast! handlers]} (om/get-shared owner)
            camera (cursors/observe-camera owner)
            in-progress? (settings/drawing-in-progress? app)
            tool (get-in app state/current-tool-path)
            mouse-down? (get-in app [:mouse-down])]
        (dom/svg #js {:width "100%"
                      :height "100%"
                      :id "svg-canvas"
                      :xmlns "http://www.w3.org/2000/svg"
                      :className (str "canvas-frame " (str "tool-" (name tool)
                                                           (when (and mouse-down?
                                                                      (keyword-identical? :text tool)
                                                                      in-progress?)
                                                             " tool-text-move")))
                      :onTouchStart (fn [event]
                                      (let [touches (.-touches event)]
                                        (cond
                                          (= (.-length touches) 1)
                                          (do
                                            (.preventDefault event)
                                            (js/clearInterval (om/get-state owner :touch-timer))
                                            (om/set-state! owner :touch-timer (js/setTimeout #(cast! :menu-opened) 500))
                                            ((:handle-mouse-down handlers) (aget touches "0")))

                                          (= (.-length touches) 2)
                                          (do
                                            (js/clearInterval (om/get-state owner :touch-timer))
                                            (cast! :cancel-drawing)
                                            (reset! (om/get-state owner :touches) (touches->clj touches)))

                                          :else (js/clearInterval (om/get-state owner :touch-timer)))))
                      :onTouchEnd (fn [event]
                                    (.preventDefault event)
                                    (js/clearInterval (om/get-state owner :touch-timer))
                                    (if (= (.-length (aget event "changedTouches")) 1)
                                      ((:handle-mouse-up handlers) event)
                                      (cast! :cancel-drawing)))
                      :onTouchMove (fn [event]
                                     (let [touches (.-touches event)]
                                       (cond (= (.-length touches) 1)
                                             (do
                                               (.preventDefault event)
                                               (.stopPropagation event)
                                               (js/clearInterval (om/get-state owner :touch-timer))
                                               ((:handle-mouse-move handlers) (aget touches "0")))

                                             (= (.-length touches) 2)
                                             (do
                                               (.preventDefault event)
                                               (.stopPropagation event)
                                               (js/clearInterval (om/get-state owner :touch-timer))
                                               (when in-progress?
                                                 (cast! :cancel-drawing))
                                               (let [touches-atom (om/get-state owner :touches)
                                                     [p-a p-b :as previous-touches] @touches-atom
                                                     [c-a c-b :as current-touches] (touches->clj touches)

                                                     p-center (center [(:page-x p-a) (:page-y p-a)]
                                                                      [(:page-x p-b) (:page-y p-b)])

                                                     c-center (center [(:page-x c-a) (:page-y c-a)]
                                                                      [(:page-x c-b) (:page-y c-b)])

                                                     drift-x (- (first c-center) (first p-center))
                                                     drift-y (- (second c-center) (second p-center))

                                                     spread (- (measure [(:page-x p-a) (:page-y p-a)]
                                                                        [(:page-x p-b) (:page-y p-b)])
                                                               (measure [(:page-x c-a) (:page-y c-a)]
                                                                        [(:page-x c-b) (:page-y c-b)]))]
                                                 (reset! touches-atom current-touches)
                                                 (om/transact! camera (fn [camera]
                                                                        (-> camera
                                                                          (cameras/set-zoom c-center
                                                                                            (partial + (* -0.004 spread)))
                                                                          (cameras/move-camera drift-x drift-y))))))
                                             :else nil)))
                      :onMouseDown (fn [event]
                                     ((:handle-mouse-down handlers) event)
                                     (.stopPropagation event))
                      :onMouseUp (fn [event]
                                   ((:handle-mouse-up handlers) event)
                                   (.stopPropagation event))
                      :onMouseMove (fn [event]
                                     ((:handle-mouse-move handlers) event)
                                     (.preventDefault event)
                                     (.stopPropagation event))
                      :onWheel (fn [event]
                                 (let [dx (- (aget event "deltaX"))
                                       dy (aget event "deltaY")]
                                   (om/transact! camera (fn [c]
                                                          (if (aget event "altKey")
                                                            (cameras/set-zoom c (cameras/screen-event-coords event) (partial + (* -0.002 dy)))
                                                            (cameras/move-camera c dx (- dy))))))
                                 (utils/stop-event event))}
                 (canvas-grid camera)

                 (when (cameras/show-grid? camera)
                   (dom/rect #js {:id        "background-grid"
                                  :className "grid-lines-pattern"
                                  :width     "100%"
                                  :height    "100%"
                                  :fill      "url(#grid)"}))

                 (dom/g
                   #js {:transform (cameras/->svg-transform camera)}
                   (om/build cursors {:client-id (:client-id app)
                                      :uuid->cust (get-in app [:cust-data :uuid->cust])}
                             {:react-key "cursors"})

                   (om/build svg-layers {:tool tool} {:react-key "svg-layers"})

                   (om/build subscribers-layers {:client-id (:client-id app)
                                                 :uuid->cust (get-in app [:cust-data :uuid->cust])}
                             {:react-key "subscribers-layers"})

                   (om/build in-progress (select-keys app [:layer-properties-menu :mouse-down]) {:react-key "in-progress"})))))))

(defn canvas [app owner]
  (reify
    om/IDisplayName (display-name [_] "Canvas")
    om/IRender
    (render [_]
      (let [right-click-learned? (get-in app state/right-click-learned-path)]
        (html
         [:div.canvas
          {:onContextMenu (fn [e]
                            (.preventDefault e)
                            (.stopPropagation e))}
          [:div.canvas-background]
          (om/build svg-canvas app)
          (when (get-in app [:menu :open?])
            (om/build radial-menu (utils/select-in app [[:menu]])))])))))
