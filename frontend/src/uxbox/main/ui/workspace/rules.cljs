;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.rules
  (:require
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [uxbox.main.constants :as c]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as s]
   [uxbox.main.streams :as ms]
   [uxbox.main.ui.hooks :refer [use-rxsub]]
   ["./rules_impl.js" :as impl]
   [uxbox.util.dom :as dom]))

(def MIN-VAL -50000)
(def MAX-VAL +50000)

(def STEP-SIZE 10)
(def STEP-PADDING 20)

(defn- impl-make-vertical-ticks
  [zoom]
  (let [btm (/ 100 zoom)
        mtm (/ 50 zoom)
        labels #js []
        path   #js []]
    (loop [i   MIN-VAL]
      (if (> i MAX-VAL)
        (str "<path d=\"" (.join path "") "\"/>" (.join labels ""))
        (let [pos (+ (* i zoom) 50)]
          (cond
            (< (mod i btm) STEP-SIZE)
            (do
              (.push path (str "M 5 " pos " L " STEP-PADDING " " pos))
              (.push labels (str "<text y=\"" (- pos 3) "\" x=\"5\" fill=\"#9da2a6\" "
                                 "transform=\"rotate(90 0 " pos ")\" "
                                 "style=\"font-size: 12px\">" i "</text>"))
              (recur (+ i STEP-SIZE)))

            (< (mod i mtm) STEP-SIZE)
            (do
              (.push path (str "M 10 " pos " L " STEP-PADDING " " pos))
              (recur (+ i STEP-SIZE)))

            :else
            (do
              (.push path (str "M 15 " pos " L " STEP-PADDING " " pos))
              (recur (+ i STEP-SIZE)))))))))


(defn- impl-make-horizontal-ticks
  [zoom]
  (let [btm (/ 100 zoom)
        mtm (/ 50 zoom)
        labels #js []
        path   #js []]
    (loop [i   MIN-VAL]
      (if (> i MAX-VAL)
        (str "<path d=\"" (.join path "") "\"/>" (.join labels ""))
        (let [pos (+ (* i zoom) 50)]
          (cond
            (< (mod i btm) STEP-SIZE)
            (do
              (.push path (str "M " pos " 5 L " pos " " STEP-PADDING))
              (.push labels (str "<text x=\"" (+ pos 2) "\" y=\"13\" fill=\"#9da2a6\" "
                                 "style=\"font-size: 12px\">" i "</text>"))
              (recur (+ i STEP-SIZE)))

            (< (mod i mtm) STEP-SIZE)
            (do
              (.push path (str "M " pos " 10 L " pos " " STEP-PADDING))
              (recur (+ i STEP-SIZE)))

            :else
            (do
              (.push path (str "M " pos " 15 L " pos " " STEP-PADDING))
              (recur (+ i STEP-SIZE)))))))))


(def impl-make-horizontal-ticks' (memoize impl-make-horizontal-ticks))
(def impl-make-vertical-ticks' (memoize impl-make-vertical-ticks))


;; --- Horizontal Rule Ticks (Component)

(mf/defc horizontal-rule-ticks
  {:wrap [mf/memo]}
  [{:keys [zoom]}]
  [{:keys [zoom]}]
  (let [svg (time (impl-make-horizontal-ticks zoom))]
    [:g {:dangerouslySetInnerHTML #js {"__html" svg}}]))

;; --- Vertical Rule Ticks (Component)

(mf/defc vertical-rule-ticks
  {:wrap [mf/memo]}
  [{:keys [zoom]}]
  (let [svg (impl-make-vertical-ticks zoom)]
    [:g {:dangerouslySetInnerHTML #js {"__html" svg}}]))

;; --- Horizontal Rule (Component)

(mf/defc horizontal-rule
  {:wrap [mf/memo]}
  [{:keys [zoom size]}]
  (let [trx (- 0 (:x size))
        trx (* trx zoom)
        trx (- trx 50)]
    [:svg.horizontal-rule {:width (:width size) :height 20}
     [:rect {:x 0
             :y 0
             :width (:width size)
             :height 20}]
     [:g {:transform (str  "translate(" trx ", 0)")}
      [:& horizontal-rule-ticks {:zoom zoom}]]]))

;; --- Vertical Rule (Component)

(mf/defc vertical-rule
  {:wrap [mf/memo]}
  [{:keys [zoom size]}]
  (let [try (- 0 (:y size))
        try (* try zoom)
        try (- try 50)]
    [:svg.vertical-rule {:width 20 :height (:height size)}
     [:rect {:x 0
             :y 0
             :width 20
             :height (:height size)}]
     [:g {:transform (str  "translate(0, " try ")")}
      [:& vertical-rule-ticks {:zoom zoom}]]]))
