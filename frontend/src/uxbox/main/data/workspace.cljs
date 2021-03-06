;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.data.workspace
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [potok.core :as ptk]
   [uxbox.common.data :as d]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.pages :as cp]
   [uxbox.common.spec :as us]
   [uxbox.common.uuid :as uuid]
   [uxbox.config :as cfg]
   [uxbox.main.constants :as c]
   [uxbox.main.data.workspace.common :as dwc]
   [uxbox.main.data.workspace.notifications :as dwn]
   [uxbox.main.data.workspace.persistence :as dwp]
   [uxbox.main.data.workspace.texts :as dwtxt]
   [uxbox.main.data.workspace.transforms :as dwt]
   [uxbox.main.data.workspace.selection :as dws]
   [uxbox.main.repo :as rp]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as ms]
   [uxbox.main.worker :as uw]
   [uxbox.common.geom.matrix :as gmt]
   [uxbox.common.geom.point :as gpt]
   [uxbox.common.geom.shapes :as geom]
   [uxbox.common.math :as mth]
   [uxbox.util.router :as rt]
   [uxbox.util.transit :as t]
   [uxbox.util.webapi :as wapi]))

;; --- Specs

(s/def ::shape-attrs ::cp/shape-attrs)

(s/def ::set-of-uuid
  (s/every uuid? :kind set?))

(s/def ::set-of-string
  (s/every string? :kind set?))

;; --- Expose inner functions

(defn interrupt? [e] (= e :interrupt))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace Initialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare initialized)
(declare initialize-group-check)

;; --- Initialize Workspace

(def default-layout
  #{:sitemap
    :sitemap-pages
    :layers
    :element-options
    :rules
    :dynamic-alignment
    :display-grid
    :snap-grid})

(s/def ::options-mode #{:design :prototype})

(def workspace-default
  {:zoom 1
   :flags #{}
   :selected #{}
   :expanded {}
   :drawing nil
   :drawing-tool nil
   :tooltip nil
   :options-mode :design
   :draw-interaction-to nil})

(def initialize-layout
  (ptk/reify ::initialize-layout
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-layout default-layout))))

(defn initialize
  [project-id file-id]
  (us/verify ::us/uuid project-id)
  (us/verify ::us/uuid file-id)

  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-presence {}))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/merge
       (rx/of (dwp/fetch-bundle project-id file-id))

       (->> stream
            (rx/filter (ptk/type? ::dwp/bundle-fetched))
            (rx/mapcat (fn [_] (rx/of (dwn/initialize file-id))))
            (rx/first))

       (->> stream
            (rx/filter (ptk/type? ::dwp/bundle-fetched))
            (rx/map deref)
            (rx/map dwc/setup-selection-index)
            (rx/first))

       (->> stream
            (rx/filter #(= ::dwc/index-initialized %))
            (rx/map (constantly
                     (initialized project-id file-id))))))))

(defn- initialized
  [project-id file-id]
  (ptk/reify ::initialized
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-file
              (fn [file]
                (if (= (:id file) file-id)
                  (assoc file :initialized true)
                  file))))))

(defn finalize
  [project-id file-id]
  (ptk/reify ::finalize
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :workspace-file :workspace-project))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (dwn/finalize file-id)))))


(defn initialize-page
  [page-id]
  (ptk/reify ::initialize-page
    ptk/UpdateEvent
    (update [_ state]
      (let [page  (get-in state [:workspace-pages page-id])
            local (get-in state [:workspace-cache page-id] workspace-default)]
        (-> state
            (assoc :current-page-id page-id   ; mainly used by events
                   :workspace-local local
                   :workspace-page (dissoc page :data))
            (assoc-in [:workspace-data page-id] (:data page)))))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (dwp/initialize-page-persistence page-id)
             initialize-group-check))))

(defn finalize-page
  [page-id]
  (us/verify ::us/uuid page-id)
  (ptk/reify ::finalize-page
    ptk/UpdateEvent
    (update [_ state]
      (let [local (:workspace-local state)]
        (-> state
            (assoc-in [:workspace-cache page-id] local)
            (update :workspace-data dissoc page-id))))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of ::dwp/finalize))))

(declare adjust-group-shapes)

(def initialize-group-check
  (ptk/reify ::initialize-group-check
    ptk/WatchEvent
    (watch [_ state stream]
      ;; TODO: add stoper
      (->> stream
           (rx/filter #(satisfies? dwc/IUpdateGroup %))
           (rx/map #(adjust-group-shapes (dwc/get-ids %)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace State Manipulation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Viewport Sizing

(declare zoom-to-fit-all)

(defn initialize-viewport
  [{:keys [width height] :as size}]
  (letfn [(update* [{:keys [vbox vport] :as local}]
            (let [wprop (/ (:width vport) width)
                  hprop (/ (:height vport) height)]
              (-> local
                  (assoc :vport size)
                  (update :vbox (fn [vbox]
                                    (-> vbox
                                        (update :width #(/ % wprop))
                                        (update :height #(/ % hprop))))))))

          (initialize [state local]
            (let [page-id (get-in state [:workspace-page :id])
                  objects (get-in state [:workspace-data page-id :objects])
                  shapes  (cp/select-toplevel-shapes objects {:include-frames? true})
                  srect   (geom/selection-rect shapes)
                  local   (assoc local :vport size)]
              (cond
                (or (not (mth/finite? (:width srect)))
                    (not (mth/finite? (:height srect))))
                (assoc local :vbox (assoc size :x 0 :y 0))

                (or (> (:width srect) width)
                    (> (:height srect) height))
                (let [srect (geom/adjust-to-viewport size srect {:padding 40})
                      zoom  (/ (:width size) (:width srect))]
                  (-> local
                      (assoc :zoom zoom)
                      (update :vbox merge srect)))

                :else
                (assoc local :vbox (assoc size
                                          :x (- (:x srect) 40)
                                          :y (- (:y srect) 40))))))

          (setup [state local]
            (if (:vbox local)
              (update* local)
              (initialize state local)))]

    (ptk/reify ::initialize-viewport
      ptk/UpdateEvent
      (update [_ state]
        (update state :workspace-local
                (fn [local]
                  (setup state local)))))))

(defn update-viewport-position
  [{:keys [x y] :or {x identity y identity}}]
  (us/assert fn? x)
  (us/assert fn? y)
  (ptk/reify ::update-viewport-position
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :vbox]
                 (fn [vbox]
                   (-> vbox
                       (update :x x)
                       (update :y y)))))))

(defn update-viewport-size
  [{:keys [width height] :as size}]
  (ptk/reify ::update-viewport-size
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local
              (fn [{:keys [vbox vport] :as local}]
                (let [wprop (/ (:width vport) width)
                      hprop (/ (:height vport) height)]
                  (-> local
                      (assoc :vport size)
                      (update :vbox (fn [vbox]
                                      (-> vbox
                                          (update :width #(/ % wprop))
                                          (update :height #(/ % hprop))))))))))))

;; ---

(defn adjust-group-shapes
  [ids]
  (ptk/reify ::adjust-group-shapes
    dwc/IBatchedChange

    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            groups-to-adjust (->> ids
                                  (mapcat #(reverse (cp/get-all-parents % objects)))
                                  (map #(get objects %))
                                  (filter #(= (:type %) :group))
                                  (map #(:id %))
                                  distinct)
            update-group
            (fn [state group]
              (let [objects (get-in state [:workspace-data page-id :objects])
                    group-center (geom/center group)
                    group-objects (->> (:shapes group)
                                       (map #(get objects %))
                                       (map #(-> %
                                                 (assoc :modifiers
                                                        (dwt/rotation-modifiers group-center % (- (:rotation group 0))))
                                                 (geom/transform-shape))))
                    selrect (geom/selection-rect group-objects)]

                ;; Rotate the group shape change the data and rotate back again
                (-> group
                    (assoc-in [:modifiers :rotation] (- (:rotation group)))
                    (geom/transform-shape)
                    (merge (select-keys selrect [:x :y :width :height]))
                    (assoc-in [:modifiers :rotation] (:rotation group))
                    (geom/transform-shape))))

            reduce-fn
            #(update-in %1 [:workspace-data page-id :objects %2] (partial update-group %1))]

        (reduce reduce-fn state groups-to-adjust)))))

(defn start-pan [state]
  (-> state
      (assoc-in [:workspace-local :panning] true)))

(defn finish-pan [state]
  (-> state
      (update :workspace-local dissoc :panning)))


;; --- Toggle layout flag

(defn toggle-layout-flag
  [& flags]
  (ptk/reify ::toggle-layout-flag
    ptk/UpdateEvent
    (update [_ state]
      (let [reduce-fn
            (fn [state flag]
              (update state :workspace-layout
                      (fn [flags]
                        (if (contains? flags flag)
                          (disj flags flag)
                          (conj flags flag)))))]
        (reduce reduce-fn state flags)))))

;; --- Set element options mode

(defn set-options-mode
  [mode]
  (us/assert ::options-mode mode)
  (ptk/reify ::set-options-mode
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :options-mode] mode))))

;; --- Tooltip

(defn assign-cursor-tooltip
  [content]
  (ptk/reify ::assign-cursor-tooltip
    ptk/UpdateEvent
    (update [_ state]
      (if (string? content)
        (assoc-in state [:workspace-local :tooltip] content)
        (assoc-in state [:workspace-local :tooltip] nil)))))

;; --- Zoom Management

(defn- impl-update-zoom
  [{:keys [vbox vport] :as local} center zoom]
  (let [new-zoom (if (fn? zoom) (zoom (:zoom local)) zoom)
        old-zoom (:zoom local)
        center (if center center (geom/center vbox))
        scale (/ old-zoom new-zoom)
        mtx  (gmt/scale-matrix (gpt/point scale) center)
        vbox' (geom/transform vbox mtx)]
    (-> local
        (assoc :zoom new-zoom)
        (update :vbox merge (select-keys vbox' [:x :y :width :height])))))

(defn increase-zoom
  [center]
  (ptk/reify ::increase-zoom
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local
              #(impl-update-zoom % center (fn [z] (min (* z 1.1) 200)))))))

(defn decrease-zoom
  [center]
  (ptk/reify ::decrease-zoom
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local
              #(impl-update-zoom % center (fn [z] (max (* z 0.9) 0.01)))))))

(def reset-zoom
  (ptk/reify ::reset-zoom
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local
              #(impl-update-zoom % nil 1)))))

(def zoom-to-fit-all
  (ptk/reify ::zoom-to-fit-all
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (get-in state [:workspace-page :id])
            objects (get-in state [:workspace-data page-id :objects])
            shapes  (cp/select-toplevel-shapes objects {:include-frames? true})
            srect   (geom/selection-rect shapes)]

        (if (or (mth/nan? (:width srect))
                (mth/nan? (:height srect)))
          state
          (update state :workspace-local
                  (fn [{:keys [vbox vport] :as local}]
                    (let [srect (geom/adjust-to-viewport vport srect {:padding 40})
                          zoom  (/ (:width vport) (:width srect))]
                      (-> local
                          (assoc :zoom zoom)
                          (update :vbox merge srect))))))))))

(def zoom-to-selected-shape
  (ptk/reify ::zoom-to-selected-shape
    ptk/UpdateEvent
    (update [_ state]
      (let [selected (get-in state [:workspace-local :selected])]
        (if (empty? selected)
          state
          (let [page-id (get-in state [:workspace-page :id])
                objects (get-in state [:workspace-data page-id :objects])
                srect  (->> selected
                            (map #(get objects %))
                            (geom/selection-rect))]
            (update state :workspace-local
                    (fn [{:keys [vbox vport] :as local}]
                      (let [srect (geom/adjust-to-viewport vport srect {:padding 40})
                            zoom  (/ (:width vport) (:width srect))]
                        (-> local
                            (assoc :zoom zoom)
                            (update :vbox merge srect)))))))))))


;; --- Add shape to Workspace

(defn- retrieve-used-names
  [objects]
  (into #{} (map :name) (vals objects)))

(defn- extract-numeric-suffix
  [basename]
  (if-let [[match p1 p2] (re-find #"(.*)-([0-9]+)$" basename)]
    [p1 (+ 1 (d/parse-integer p2))]
    [basename 1]))

(defn- generate-unique-name
  "A unique name generator"
  [used basename]
  (s/assert ::set-of-string used)
  (s/assert ::us/string basename)
  (let [[prefix initial] (extract-numeric-suffix basename)]
    (loop [counter initial]
      (let [candidate (str prefix "-" counter)]
        (if (contains? used candidate)
          (recur (inc counter))
          candidate)))))

(declare start-edition-mode)

(defn add-shape
  [attrs]
  (us/verify ::shape-attrs attrs)
  (ptk/reify ::add-shape
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            objects (get-in state [:workspace-data page-id :objects])

            id       (uuid/next)
            shape    (geom/setup-proportions attrs)

            unames   (retrieve-used-names objects)
            name     (generate-unique-name unames (:name shape))

            frames   (cp/select-frames objects)

            frame-id (if (= :frame (:type shape))
                       uuid/zero
                       (dwc/calculate-frame-overlap frames shape))

            shape    (merge
                      (if (= :frame (:type shape))
                        cp/default-frame-attrs
                        cp/default-shape-attrs)
                      (assoc shape
                             :id id
                             :name name
                             :frame-id frame-id))

            rchange  {:type :add-obj
                      :id id
                      :frame-id frame-id
                      :obj shape}
            uchange  {:type :del-obj
                      :id id}]

        (rx/of (dwc/commit-changes [rchange] [uchange] {:commit-local? true})
               (dws/select-shapes #{id})
               (when (= :text (:type attrs))
                 (start-edition-mode id)))))))

(defn- calculate-centered-box
  [state aspect-ratio]
  (if (>= aspect-ratio 1)
    (let [vbox (get-in state [:workspace-local :vbox])
          width (/ (:width vbox) 2)
          height (/ width aspect-ratio)

          x (+ (:x vbox) (/ width 2))
          y (+ (:y vbox) (/ (- (:height vbox) height) 2))]

      [width height x y])

    (let [vbox (get-in state [:workspace-local :vbox])
          height (/ (:height vbox) 2)
          width (* height aspect-ratio)

          y (+ (:y vbox) (/ height 2))
          x (+ (:x vbox) (/ (- (:width vbox) width) 2))]

      [width height x y])))

(defn create-and-add-shape
  [type data aspect-ratio]
  (ptk/reify ::create-and-add-shape
    ptk/WatchEvent
    (watch [_ state stream]
      (let [[width height x y] (calculate-centered-box state aspect-ratio)
            shape (-> (cp/make-minimal-shape type)
                      (merge data)
                      (geom/resize width height)
                      (geom/absolute-move (gpt/point x y)))]

        (rx/of (add-shape shape))))))




;; --- Update Shape Attrs

(defn update-shape
  [id attrs]
  (us/verify ::us/uuid id)
  (us/verify ::shape-attrs attrs)
  (ptk/reify ::update-shape
    dwc/IBatchedChange
    dwc/IUpdateGroup
    (get-ids [_] [id])

    ptk/UpdateEvent
    (update [_ state]
      (let [pid (:current-page-id state)]
        (update-in state [:workspace-data pid :objects id] merge attrs)))))

(defn update-shape-recursive
  [id attrs]
  (us/verify ::us/uuid id)
  (us/verify ::shape-attrs attrs)
  (letfn [(update-shape [shape]
            (cond-> (merge shape attrs)
              (and (= :text (:type shape))
                   (string? (:fill-color attrs)))
              (dwtxt/impl-update-shape-attrs {:fill (:fill-color attrs)})))]
    (ptk/reify ::update-shape
      dwc/IBatchedChange
      dwc/IUpdateGroup
      (get-ids [_] [id])

      ptk/UpdateEvent
      (update [_ state]
        (let [page-id (:current-page-id state)
              grouped #{:frame :group}]
          (update-in state [:workspace-data page-id :objects]
                     (fn [objects]
                       (->> (d/concat [id] (cp/get-children id objects))
                            (map #(get objects %))
                            (remove #(grouped (:type %)))
                            (reduce #(update %1 (:id %2) update-shape) objects)))))))))

;; --- Update Page Options

(defn update-options
  [opts]
  (us/verify ::cp/options opts)
  (ptk/reify ::update-options
    dwc/IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (:current-page-id state)]
        (update-in state [:workspace-data pid :options] merge opts)))))

;; --- Update Selected Shapes attrs

(defn update-selected-shapes
  [attrs]
  (us/verify ::shape-attrs attrs)
  (ptk/reify ::update-selected-shapes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected (get-in state [:workspace-local :selected])]
        (rx/from (map #(update-shape % attrs) selected))))))

(defn update-color-on-selected-shapes
  [{:keys [fill-color stroke-color] :as attrs}]
  (us/verify ::shape-attrs attrs)
  (ptk/reify ::update-color-on-selected-shapes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected (get-in state [:workspace-local :selected])
            page-id  (get-in state [:workspace-page :id])]
        (->> (rx/from selected)
             (rx/map (fn [id]
                       (update-shape-recursive id attrs))))))))

;; --- Shape Movement (using keyboard shorcuts)

(declare initial-selection-align)

(defn- get-displacement-with-grid
  "Retrieve the correct displacement delta point for the
  provided direction speed and distances thresholds."
  [shape direction options]
  (let [grid-x (:grid-x options 10)
        grid-y (:grid-y options 10)
        x-mod (mod (:x shape) grid-x)
        y-mod (mod (:y shape) grid-y)]
    (case direction
      :up (gpt/point 0 (- (if (zero? y-mod) grid-y y-mod)))
      :down (gpt/point 0 (- grid-y y-mod))
      :left (gpt/point (- (if (zero? x-mod) grid-x x-mod)) 0)
      :right (gpt/point (- grid-x x-mod) 0))))

(defn- get-displacement
  "Retrieve the correct displacement delta point for the
  provided direction speed and distances thresholds."
  [shape direction]
  (case direction
    :up (gpt/point 0 (- 1))
    :down (gpt/point 0 1)
    :left (gpt/point (- 1) 0)
    :right (gpt/point 1 0)))

(s/def ::loc  #{:up :down :bottom :top})

;; --- Delete Selected
(defn- delete-shapes
  [ids]
  (us/assert (s/coll-of ::us/uuid) ids)
  (ptk/reify ::delete-shapes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            cpindex (cp/calculate-child-parent-map objects)

            del-change #(array-map :type :del-obj :id %)

            get-empty-parents
            (fn get-empty-parents [id]
              (let [parent (get objects (get cpindex id))]
                (if (and (= :group (:type parent))
                         (= 1 (count (:shapes parent))))
                  (lazy-seq (cons (:id parent)
                                  (get-empty-parents (:id parent))))
                  nil)))

            rchanges
            (reduce (fn [res id]
                      (let [chd (cp/get-children id objects)]
                        (into res (d/concat
                                   (mapv del-change (reverse chd))
                                   [(del-change id)]
                                   (map del-change (get-empty-parents id))))))
                    []
                    ids)

            uchanges
            (mapv (fn [id]
                    (let [obj (get objects id)]
                     {:type :add-obj
                      :id id
                      :frame-id (:frame-id obj)
                      :parent-id (get cpindex id)
                      :obj obj}))
                  (reverse (map :id rchanges)))]
        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))

(def delete-selected
  "Deselect all and remove all selected shapes."
  (ptk/reify ::delete-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            lookup   #(get-in state [:workspace-data page-id :objects %])
            selected (get-in state [:workspace-local :selected])

            shapes (map lookup selected)
            shape? #(not= (:type %) :frame)]
        (rx/of (delete-shapes selected)
               dws/deselect-all)))))


;; --- Rename Shape

(defn rename-shape
  [id name]
  (us/verify ::us/uuid id)
  (us/verify string? name)
  (ptk/reify ::rename-shape
    dwc/IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)]
        (update-in state [:workspace-data page-id :objects id] assoc :name name)))))

;; --- Shape Vertical Ordering

(defn vertical-order-selected
  [loc]
  (us/verify ::loc loc)
  (ptk/reify ::vertical-order-selected-shpes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            selected (seq (get-in state [:workspace-local :selected]))

            rchanges (mapv (fn [id]
                             (let [frame-id (get-in objects [id :frame-id])]
                               {:type :mod-obj
                                :id frame-id
                                :operations [{:type :rel-order :id id :loc loc}]}))
                           selected)
            uchanges (mapv (fn [id]
                             (let [frame-id (get-in objects [id :frame-id])
                                   shapes (get-in objects [frame-id :shapes])
                                   cindex (d/index-of shapes id)]
                               {:type :mod-obj
                                :id frame-id
                                :operations [{:type :abs-order :id id :index cindex}]}))
                           selected)]
        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))


;; --- Change Shape Order (D&D Ordering)

(defn relocate-shape
  [id parent-id to-index]
  (us/verify ::us/uuid id)
  (us/verify ::us/uuid parent-id)
  (us/verify number? to-index)

  (ptk/reify ::relocate-shape
    dwc/IUpdateGroup
    (get-ids [_] [id])

    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            parent (get objects (cp/get-parent id objects))
            current-index (d/index-of (:shapes parent) id)
            selected (get-in state [:workspace-local :selected])]
        (rx/of (dwc/commit-changes [{:type :mov-objects
                                     :parent-id parent-id
                                     :index to-index
                                     :shapes (vec selected)}]
                                   [{:type :mov-objects
                                     :parent-id (:id parent)
                                     :index current-index
                                     :shapes (vec selected)}]
                                   {:commit-local? true}))))))

;; --- Change Page Order (D&D Ordering)

(defn relocate-page
  [id index]
  (ptk/reify ::relocate-pages
    ptk/UpdateEvent
    (update [_ state]
      (let [pages (get-in state [:workspace-file :pages])
            [before after] (split-at index pages)
            p? (partial = id)
            pages' (d/concat []
                             (remove p? before)
                             [id]
                             (remove p? after))]
        (assoc-in state [:workspace-file :pages] pages')))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [file (:workspace-file state)]
        (->> (rp/mutation! :reorder-pages {:page-ids (:pages file)
                                           :file-id (:id file)})
             (rx/ignore))))))

;; --- Shape / Selection Alignment and Distribution

(declare align-object-to-frame)
(declare align-objects-list)

(defn align-objects
  [axis]
  (us/verify ::geom/align-axis axis)
  (ptk/reify :align-objects
    dwc/IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            selected (get-in state [:workspace-local :selected])
            moved-objs (if (= 1 (count selected))
                         (align-object-to-frame objects (first selected) axis)
                         (align-objects-list objects selected axis))
            updated-objs (merge objects (d/index-by :id moved-objs))]
        (assoc-in state [:workspace-data page-id :objects] updated-objs)))))

(defn align-object-to-frame
  [objects object-id axis]
  (let [object (get objects object-id)
        frame (get objects (:frame-id object))]
    (geom/align-to-rect object frame axis objects)))

(defn align-objects-list
  [objects selected axis]
  (let [selected-objs (map #(get objects %) selected)
        rect (geom/selection-rect selected-objs)]
    (mapcat #(geom/align-to-rect % rect axis objects) selected-objs)))

(defn distribute-objects
  [axis]
  (us/verify ::geom/dist-axis axis)
  (ptk/reify :align-objects
    dwc/IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            selected (get-in state [:workspace-local :selected])
            selected-objs (map #(get objects %) selected)
            moved-objs (geom/distribute-space selected-objs axis objects)
            updated-objs (merge objects (d/index-by :id moved-objs))]
        (assoc-in state [:workspace-data page-id :objects] updated-objs)))))

;; --- Start shape "edition mode"

(declare clear-edition-mode)

(defn start-edition-mode
  [id]
  (us/assert ::us/uuid id)
  (ptk/reify ::start-edition-mode
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :edition] id))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> stream
           (rx/filter interrupt?)
           (rx/take 1)
           (rx/map (constantly clear-edition-mode))))))

(def clear-edition-mode
  (ptk/reify ::clear-edition-mode
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :edition))))

;; --- Select for Drawing

(def clear-drawing
  (ptk/reify ::clear-drawing
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :drawing-tool :drawing))))

(defn select-for-drawing
  ([tool] (select-for-drawing tool nil))
  ([tool data]
   (ptk/reify ::select-for-drawing
     ptk/UpdateEvent
     (update [_ state]
       (update state :workspace-local assoc :drawing-tool tool :drawing data))

     ptk/WatchEvent
     (watch [_ state stream]
       (let [cancel-event? (fn [event]
                             (interrupt? event))
             stoper (rx/filter (ptk/type? ::clear-drawing) stream)]
         (->> (rx/filter cancel-event? stream)
              (rx/take 1)
              (rx/map (constantly clear-drawing))
              (rx/take-until stoper)))))))

;; --- Update Dimensions

(defn update-rect-dimensions
  [id attr value]
  (us/verify ::us/uuid id)
  (us/verify #{:width :height} attr)
  (us/verify ::us/number value)
  (ptk/reify ::update-rect-dimensions
    dwc/IBatchedChange
    dwc/IUpdateGroup
    (get-ids [_] [id])

    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)]
        (update-in state [:workspace-data page-id :objects id]
                   geom/resize-rect attr value)))))

;; --- Shape Proportions

(defn toggle-shape-proportion-lock
  [id]
  (ptk/reify ::toggle-shape-proportion-lock
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
            shape (get-in state [:workspace-data page-id :objects id])]
        (if (:proportion-lock shape)
          (assoc-in state [:workspace-data page-id :objects id :proportion-lock] false)
          (->> (geom/assign-proportions (assoc shape :proportion-lock true))
               (assoc-in state [:workspace-data page-id :objects id])))))))

;; --- Update Shape Position

(s/def ::x number?)
(s/def ::y number?)
(s/def ::position
  (s/keys :opt-un [::x ::y]))

(defn update-position
  [id position]
  (us/verify ::us/uuid id)
  (us/verify ::position position)
  (ptk/reify ::update-position
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            shape (get-in state [:workspace-data page-id :objects id])
            current-position (gpt/point (:x shape) (:y shape))
            position (gpt/point (or (:x position) (:x shape)) (or (:y position) (:y shape)))
            displacement (gmt/translate-matrix (gpt/subtract position current-position))]
        (rx/of (dwt/set-modifiers [id] {:displacement displacement})
               (dwt/apply-modifiers [id]))))))

;; --- Path Modifications

(defn update-path
  "Update a concrete point in the path shape."
  [id index delta]
  (us/verify ::us/uuid id)
  (us/verify ::us/integer index)
  (us/verify gpt/point? delta)
  (ptk/reify ::update-path
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)]
        (-> state
            (update-in [:workspace-data page-id :objects id :segments index] gpt/add delta)
            (update-in [:workspace-data page-id :objects id] geom/update-path-selrect))))))

;; --- Shape attrs (Layers Sidebar)

(defn toggle-collapse
  [id]
  (ptk/reify ::toggle-collapse
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :expanded id] not))))

(def collapse-all
  (ptk/reify ::collapse-all
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :expanded))))

(defn recursive-assign
  "A helper for assign recursively a shape attr."
  [id attr value]
  (ptk/reify ::recursive-assign
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (get-in state [:workspace-page :id])
            objects (get-in state [:workspace-data page-id :objects])
            childs (cp/get-children id objects)]
        (update-in state [:workspace-data page-id :objects]
                   (fn [objects]
                     (reduce (fn [objects id]
                               (assoc-in objects [id attr] value))
                             objects
                             (conj childs id))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Navigation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn navigate-to-project
  [project-id]
  (ptk/reify ::navigate-to-project
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-ids (get-in state [:projects project-id :pages])
            params {:project project-id :page (first page-ids)}]
        (rx/of (rt/nav :workspace/page params))))))

(defn go-to-page
  [page-id]
  (us/verify ::us/uuid page-id)
  (ptk/reify ::go-to-page
    ptk/WatchEvent
    (watch [_ state stream]
      (let [project-id (get-in state [:workspace-project :id])
            file-id (get-in state [:workspace-page :file-id])
            path-params {:file-id file-id :project-id project-id}
            query-params {:page-id page-id}]
        (rx/of (rt/nav :workspace path-params query-params))))))

(def go-to-file
  (ptk/reify ::go-to-file
    ptk/WatchEvent
    (watch [_ state stream]
      (let [file (:workspace-file state)

            file-id (:id file)
            project-id (:project-id file)
            page-ids (:pages file)

            path-params {:project-id project-id :file-id file-id}
            query-params {:page-id (first page-ids)}]
        (rx/of (rt/nav :workspace path-params query-params))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Context Menu
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::point gpt/point?)

(defn show-context-menu
  [{:keys [position] :as params}]
  (us/verify ::point position)
  (ptk/reify ::show-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :context-menu] {:position position}))))

(defn show-shape-context-menu
  [{:keys [position shape] :as params}]
  (us/verify ::point position)
  (us/verify ::cp/minimal-shape shape)
  (ptk/reify ::show-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (let [mdata {:position position
                   :shape shape
                   :selected (get-in state [:workspace-local :selected])}]
        (-> state
            (assoc-in [:workspace-local :context-menu] mdata))))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (dws/select-shape (:id shape))))))

(def hide-context-menu
  (ptk/reify ::hide-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :context-menu] nil))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Clipboard
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def copy-selected
  (letfn [(prepare-selected [objects selected]
            (let [data (reduce #(prepare %1 objects %2) {} selected)]
              {:type :copied-shapes
               :selected selected
               :objects data}))

          (prepare [result objects id]
            (let [obj (get objects id)]
              (as-> result $$
                (assoc $$ id obj)
                (reduce #(prepare %1 objects %2) $$ (:shapes obj)))))

          (on-copy-error [error]
            (js/console.error "Clipboard blocked:" error)
            (rx/empty))]

    (ptk/reify ::copy-selected
      ptk/WatchEvent
      (watch [_ state stream]
        (let [page-id (:current-page-id state)
              objects (get-in state [:workspace-data page-id :objects])
              selected (get-in state [:workspace-local :selected])
              cdata    (prepare-selected objects selected)]
          (->> (t/encode cdata)
               (wapi/write-to-clipboard)
               (rx/from)
               (rx/catch on-copy-error)
               (rx/ignore)))))))

(defn- paste-impl
  [{:keys [selected objects] :as data}]
  (ptk/reify ::paste-impl
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected-objs (map #(get objects %) selected)
            wrapper (geom/selection-rect selected-objs)
            orig-pos (gpt/point (:x1 wrapper) (:y1 wrapper))
            mouse-pos @ms/mouse-position
            delta (gpt/subtract mouse-pos orig-pos)

            page-id (:current-page-id state)
            unames (-> (get-in state [:workspace-data page-id :objects])
                       (retrieve-used-names))

            rchanges (dws/prepare-duplicate-changes objects unames selected delta)
            uchanges (mapv #(array-map :type :del-obj :id (:id %))
                           (reverse rchanges))

            selected (->> rchanges
                          (filter #(selected (:old-id %)))
                          (map #(get-in % [:obj :id]))
                          (into #{}))]
        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
               (dws/select-shapes selected))))))

(defn- image-uploaded
  [{:keys [id name] :as image}]
  (let [shape {:name name
               :metadata {:width (:width image)
                          :height (:height image)
                          :uri (:uri image)
                          :thumb-width (:thumb-width image)
                          :thumb-height (:thumb-height image)
                          :thumb-uri (:thumb-uri image)}}
        aspect-ratio (/ (:width image) (:height image))]
    (st/emit! (create-and-add-shape :image shape aspect-ratio))))

(defn- paste-image-impl
  [image]
  (ptk/reify ::paste-bin-impl
    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (dwp/upload-image image image-uploaded)))))

(def paste
  (ptk/reify ::paste
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (wapi/read-from-clipboard)
           (rx/map t/decode)
           (rx/filter #(= :copied-shapes (:type %)))
           (rx/map #(select-keys % [:selected :objects]))
           (rx/map paste-impl)
           (rx/catch (partial instance? js/SyntaxError)
             (fn [_]
               (->> (wapi/read-image-from-clipboard)
                    (rx/map paste-image-impl))))
           (rx/catch (fn [err]
                       (js/console.error "Clipboard error:" err)
                       (rx/empty)))))))


;; --- Change Page Order (D&D Ordering)

(defn change-page-order
  [{:keys [id index] :as params}]
  {:pre [(uuid? id) (number? index)]}
  (ptk/reify ::change-page-order
    ptk/UpdateEvent
    (update [_ state]
      (let [page (get-in state [:pages id])
            pages (get-in state [:projects (:project-id page) :pages])
            pages (into [] (remove #(= % id)) pages)
            [before after] (split-at index pages)
            pages (vec (concat before [id] after))]
        (assoc-in state [:projects (:project-id page) :pages] pages)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GROUPS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn group-shape
  [id frame-id selected selection-rect]
  {:id id
   :type :group
   :name (name (gensym "Group-"))
   :shapes []
   :frame-id frame-id
   :x (:x selection-rect)
   :y (:y selection-rect)
   :width (:width selection-rect)
   :height (:height selection-rect)})

(def group-selected
  (ptk/reify ::group-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (uuid/next)
            selected (get-in state [:workspace-local :selected])]
        (when (not-empty selected)
          (let [page-id (get-in state [:workspace-page :id])
                objects (get-in state [:workspace-data page-id :objects])

                selected-objects (map (partial get objects) selected)
                selrect  (geom/selection-rect selected-objects)
                frame-id (-> selected-objects first :frame-id)
                group    (-> (group-shape id frame-id selected selrect)
                             (geom/setup selrect))

                index    (->> (get-in objects [frame-id :shapes])
                              (map-indexed vector)
                              (filter #(selected (second %)))
                              (ffirst))

                rchanges [{:type :add-obj
                           :id id
                           :frame-id frame-id
                           :obj group
                           :index index}
                          {:type :mov-objects
                           :parent-id id
                           :shapes (vec selected)}]
                uchanges [{:type :mov-objects
                           :parent-id frame-id
                           :shapes (vec selected)}
                          {:type :del-obj
                           :id id}]]
            (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
                   (dws/select-shapes #{id}))))))))

(def ungroup-selected
  (ptk/reify ::ungroup-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects  (get-in state [:workspace-data page-id :objects])
            selected (get-in state [:workspace-local :selected])
            group-id (first selected)
            group    (get objects group-id)]
        (when (and (= 1 (count selected))
                   (= (:type group) :group))
          (let [shapes    (:shapes group)
                parent-id (cp/get-parent group-id objects)
                parent    (get objects parent-id)
                index-in-parent (->> (:shapes parent)
                                     (map-indexed vector)
                                     (filter #(#{group-id} (second %)))
                                     (ffirst))
                rchanges [{:type :mov-objects
                           :parent-id parent-id
                           :shapes shapes
                           :index index-in-parent}]
                uchanges [{:type :add-obj
                           :id group-id
                           :frame-id (:frame-id group)
                           :obj (assoc group :shapes [])}
                          {:type :mov-objects
                           :parent-id group-id
                           :shapes shapes}
                          {:type :mov-objects
                           :parent-id parent-id
                           :shapes [group-id]
                           :index index-in-parent}]]
            (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interactions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare move-create-interaction)
(declare finish-create-interaction)

(defn start-create-interaction
  []
  (ptk/reify ::start-create-interaction
    ptk/WatchEvent
    (watch [_ state stream]
      (let [initial-pos @ms/mouse-position
            selected (get-in state [:workspace-local :selected])
            stopper (rx/filter ms/mouse-up? stream)]
        (when (= 1 (count selected))
          (rx/concat
            (->> ms/mouse-position
                 (rx/take-until stopper)
                 (rx/map #(move-create-interaction initial-pos %)))
            (rx/of (finish-create-interaction initial-pos))))))))

(defn move-create-interaction
  [initial-pos position]
  (ptk/reify ::move-create-interaction
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            selected-shape-id (-> state (get-in [:workspace-local :selected]) first)
            selected-shape (get objects selected-shape-id)
            selected-shape-frame-id (:frame-id selected-shape)
            start-frame (get objects selected-shape-frame-id)
            end-frame (dwc/get-frame-at-point objects position)]
        (cond-> state
          (not= position initial-pos) (assoc-in [:workspace-local :draw-interaction-to] position)
          (not= start-frame end-frame) (assoc-in [:workspace-local :draw-interaction-to-frame] end-frame))))))

(defn finish-create-interaction
  [initial-pos]
  (ptk/reify ::finish-create-interaction
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :draw-interaction-to] nil)
          (assoc-in [:workspace-local :draw-interaction-to-frame] nil)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [position @ms/mouse-position
            page-id (:current-page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            frame (dwc/get-frame-at-point objects position)

            shape-id (first (get-in state [:workspace-local :selected]))
            shape (get objects shape-id)]

        (when-not (= position initial-pos)
          (if (and frame shape-id
                   (not= (:id frame) (:id shape))
                   (not= (:id frame) (:frame-id shape)))
            (rx/of (update-shape shape-id
                                 {:interactions [{:event-type :click
                                                  :action-type :navigate
                                                  :destination (:id frame)}]}))
            (rx/of (update-shape shape-id
                                 {:interactions []}))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CANVAS OPTIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn change-canvas-color
  [color]
  (ptk/reify ::change-canvas-color
    ptk/WatchEvent
    (watch [_ state stream]
      (let [pid (get state :current-page-id)
            current-color (get-in state [:workspace-data pid :options :background])]
        (rx/of (dwc/commit-changes
                [{:type :set-option
                  :option :background
                  :value color}]
                [{:type :set-option
                  :option :background
                  :value current-color}]
                {:commit-local? true}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Exports
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Transform

(def start-rotate dwt/start-rotate)
(def start-resize dwt/start-resize)
(def start-move-selected dwt/start-move-selected)
(def move-selected dwt/move-selected)

(def set-rotation dwt/set-rotation)
(def set-modifiers dwt/set-modifiers)
(def apply-modifiers dwt/apply-modifiers)

;; Persistence

(def upload-image dwp/upload-image)
(def rename-page dwp/rename-page)
(def delete-page dwp/delete-page)
(def create-empty-page dwp/create-empty-page)

;; Selection
(def select-shape dws/select-shape)
(def deselect-all dws/deselect-all)
(def select-shapes dws/select-shapes)
(def duplicate-selected dws/duplicate-selected)
(def handle-selection dws/handle-selection)
(def select-inside-group dws/select-inside-group)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shortcuts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Shortcuts impl https://github.com/ccampbell/mousetrap

(def shortcuts
  {"ctrl+m" #(st/emit! (toggle-layout-flag :sitemap))
   "ctrl+i" #(st/emit! (toggle-layout-flag :libraries))
   "ctrl+l" #(st/emit! (toggle-layout-flag :layers))
   "ctrl+shift+r" #(st/emit! (toggle-layout-flag :rules))
   "ctrl+a" #(st/emit! (toggle-layout-flag :dynamic-alignment))
   "ctrl+p" #(st/emit! (toggle-layout-flag :colorpalette))
   "ctrl+'" #(st/emit! (toggle-layout-flag :display-grid))
   "ctrl+shift+'" #(st/emit! (toggle-layout-flag :snap-grid))
   "+" #(st/emit! (increase-zoom nil))
   "-" #(st/emit! (decrease-zoom nil))
   "g" #(st/emit! group-selected)
   "shift+g" #(st/emit! ungroup-selected)
   "shift+0" #(st/emit! reset-zoom)
   "shift+1" #(st/emit! zoom-to-fit-all)
   "shift+2" #(st/emit! zoom-to-selected-shape)
   "d" #(st/emit! duplicate-selected)
   "ctrl+z" #(st/emit! dwc/undo)
   "ctrl+shift+z" #(st/emit! dwc/redo)
   "ctrl+y" #(st/emit! dwc/redo)
   "ctrl+q" #(st/emit! dwc/reinitialize-undo)
   "a" #(st/emit! (select-for-drawing :frame))
   "b" #(st/emit! (select-for-drawing :rect))
   "e" #(st/emit! (select-for-drawing :circle))
   "t" #(st/emit! (select-for-drawing :text))
   "ctrl+c" #(st/emit! copy-selected)
   "ctrl+v" #(st/emit! paste)
   "escape" #(st/emit! :interrupt deselect-all)
   "del" #(st/emit! delete-selected)
   "ctrl+up" #(st/emit! (vertical-order-selected :up))
   "ctrl+down" #(st/emit! (vertical-order-selected :down))
   "ctrl+shift+up" #(st/emit! (vertical-order-selected :top))
   "ctrl+shift+down" #(st/emit! (vertical-order-selected :bottom))
   "shift+up" #(st/emit! (dwt/move-selected :up true))
   "shift+down" #(st/emit! (dwt/move-selected :down true))
   "shift+right" #(st/emit! (dwt/move-selected :right true))
   "shift+left" #(st/emit! (dwt/move-selected :left true))
   "up" #(st/emit! (dwt/move-selected :up false))
   "down" #(st/emit! (dwt/move-selected :down false))
   "right" #(st/emit! (dwt/move-selected :right false))
   "left" #(st/emit! (dwt/move-selected :left false))})

