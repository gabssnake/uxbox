;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.rules
  (:require
   [rumext.alpha :as mf]
   [uxbox.util.object :as obj]))

(def STEP-SIZE 10)
(def STEP-PADDING 20)

(mf/defc horizontal-rule
  {::mf/wrap [mf/memo #(mf/throttle % 60)]}
  [{:keys [zoom size]}]
  (let [canvas (mf/use-ref)
        {:keys [x width]} size]
    (mf/use-layout-effect
     (mf/deps width x zoom)
     (fn []
       (let [node (mf/ref-val canvas)
             dctx (.getContext node "2d")

             btm (/ 100 zoom)
             mtm (/ 50 zoom)
             trx (- (* (- 0 x) zoom) 50)

             mm (mod x STEP-SIZE)
             min-val (+ x (js/Math.abs (- STEP-SIZE mm)))
             max-val (+ x width)]

         (obj/set! node "width" width)

         (obj/set! dctx "fillStyle" "#E8E9EA")
         (.fillRect dctx 0 0 width 20)

         (obj/set! dctx "font" "11px serif")
         (obj/set! dctx "fillStyle" "#7B7D85")
         (obj/set! dctx "strokeStyle" "#7B7D85")

         (.translate dctx trx 0)

         (loop [i min-val]
           (when (< i max-val)
             (let [pos (+ (* i zoom) 50)]
               (when (< (mod i btm) STEP-SIZE)
                 (.fillText dctx (str i) (+ pos 5) 12))
               (recur (+ i STEP-SIZE)))))

         (let [path (js/Path2D.)]
           (loop [i min-val]
             (if (> i max-val)
               (.stroke dctx path)
               (let [pos (+ (* i zoom) 50)]
                 (cond
                   (< (mod i btm) STEP-SIZE)
                   (do
                     (.moveTo path pos 5)
                     (.lineTo path pos STEP-PADDING)
                     (recur (+ i STEP-SIZE)))

                   (< (mod i mtm) STEP-SIZE)
                   (do
                     (.moveTo path pos 10)
                     (.lineTo path pos STEP-PADDING)
                     (recur (+ i STEP-SIZE)))

                   :else
                   (do
                     (.moveTo path pos 15)
                     (.lineTo path pos STEP-PADDING)
                     (recur (+ i STEP-SIZE)))))))))))

    [:canvas.horizontal-rule {:ref canvas :width (:width size) :height 20}]))


;; --- Vertical Rule (Component)

(mf/defc vertical-rule
  {::mf/wrap [mf/memo #(mf/throttle % 60)]}
  [{:keys [zoom size]}]
  (let [canvas (mf/use-ref)
        {:keys [y height]} size]
    (mf/use-layout-effect
     (mf/deps height y zoom)
     (fn []
       (let [node (mf/ref-val canvas)
             dctx (.getContext node "2d")

             btm (/ 100 zoom)
             mtm (/ 50 zoom)
             try (- (* (- 0 y) zoom) 50)

             mm (mod y STEP-SIZE)
             min-val (+ y (js/Math.abs (- STEP-SIZE mm)))
             max-val (+ y height)]

         (obj/set! node "height" height)

         (obj/set! dctx "fillStyle" "#E8E9EA")
         (.fillRect dctx 0 0 20 height)

         (obj/set! dctx "font" "11px serif")
         (obj/set! dctx "fillStyle" "#7B7D85")
         (obj/set! dctx "strokeStyle" "#7B7D85")

         (.translate dctx 0 try)

         (loop [i min-val]
           (when (< i max-val)
             (let [pos (+ (* i zoom) 50)]
               (when (< (mod i btm) STEP-SIZE)
                 (.save dctx)
                 (.translate dctx 12 (- pos 5))
                 (.rotate dctx (/ (* 270 js/Math.PI) 180))
                 (.fillText dctx (str i) 0 0)
                 (.restore dctx))
               (recur (+ i STEP-SIZE)))))

         (let [path (js/Path2D.)]
           (loop [i min-val]
             (if (> i max-val)
               (.stroke dctx path)
               (let [pos (+ (* i zoom) 50)]
                 (cond
                   (< (mod i btm) STEP-SIZE)
                   (do
                     (.moveTo path 5 pos)
                     (.lineTo path STEP-PADDING pos)
                     (recur (+ i STEP-SIZE)))

                   (< (mod i mtm) STEP-SIZE)
                   (do
                     (.moveTo path 10 pos)
                     (.lineTo path STEP-PADDING pos)
                     (recur (+ i STEP-SIZE)))

                   :else
                   (do
                     (.moveTo path 15 pos)
                     (.lineTo path STEP-PADDING pos)
                     (recur (+ i STEP-SIZE)))))))))))

    [:canvas.vertical-rule {:ref canvas :width 20 :height height}]))

