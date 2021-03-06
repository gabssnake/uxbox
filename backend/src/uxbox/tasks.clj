;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.tasks
  "Async tasks abstraction (impl)."
  (:require
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [mount.core :as mount :refer [defstate]]
   [uxbox.common.spec :as us]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.metrics :as mtx]
   [uxbox.tasks.sendmail]
   [uxbox.tasks.gc]
   [uxbox.tasks.remove-media]
   [uxbox.tasks.delete-profile]
   [uxbox.tasks.delete-object]
   [uxbox.tasks.impl :as impl]
   [uxbox.util.time :as dt])
  (:import
   java.util.concurrent.ScheduledExecutorService
   java.util.concurrent.Executors))

;; --- Scheduler Executor Initialization

(defstate scheduler
  :start (Executors/newScheduledThreadPool (int 1))
  :stop (.shutdownNow ^ScheduledExecutorService scheduler))

;; --- State initialization

;; TODO: missing self maintanance task; when the queue table is full
;; of completed/failed task, the performance starts degrading
;; linearly, so after some arbitrary number of tasks is processed, we
;; need to perform a maintenance and delete some old tasks.

(def ^:private tasks
  {"delete-profile" #'uxbox.tasks.delete-profile/handler
   "delete-object" #'uxbox.tasks.delete-object/handler
   "remove-media" #'uxbox.tasks.remove-media/handler
   "sendmail" #'uxbox.tasks.sendmail/handler})

(def ^:private schedule
  [{:id "remove-deleted-media"
    :cron (dt/cron "1 1 */1 * * ? *")
    :fn #'uxbox.tasks.gc/remove-media}])

(defstate tasks-worker
  :start (impl/start-worker! {:tasks tasks
                              :xtor scheduler})
  :stop (impl/stop! tasks-worker))

(defstate scheduler-worker
  :start (impl/start-scheduler-worker! {:schedule schedule
                                        :xtor scheduler})
  :stop (impl/stop! scheduler-worker))

;; --- Public API

(defn submit!
  ([opts] (submit! db/pool opts))
  ([conn opts]
   (s/assert ::impl/task-options opts)
   (impl/submit! conn opts)))

(mtx/instrument-with-counter!
 {:var #'submit!
  :id "tasks__submit_counter"
  :help "Absolute task submit counter."})
