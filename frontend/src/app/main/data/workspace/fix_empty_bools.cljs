;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.fix-empty-bools
  "Boolean shapes are rendered from their stored content, which is only
  computed when they are created or their children change. A boolean
  imported without content (the Figma API does not always give the
  geometry of instance layers) renders nothing, and as a mask it hides
  everything, so its content is computed when its page is opened."
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.types.path :as path]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn- empty-bool?
  [shape]
  (and (cfh/bool-shape? shape)
       (d/not-empty? (:shapes shape))
       ;; A path data is never `empty?`, its size tells
       (zero? (count (:content shape)))))

(defn fix-empty-bools-for-page
  [file-id page-id]
  (ptk/reify ::fix-empty-bools-for-page
    ptk/WatchEvent
    (watch [it state _]
      (let [objects (:objects (dsh/lookup-page state file-id page-id))
            ;; The deepest first, so a boolean inside another one has
            ;; its content when its parent is computed
            ids     (->> (vals objects)
                         (filter empty-bool?)
                         (sort-by #(- (count (cfh/get-parent-ids objects (:id %)))))
                         (mapv :id))]
        (when (seq ids)
          (let [objects  (reduce (fn [objects id]
                                   (update objects id path/update-bool-shape objects))
                                 objects
                                 ids)
                changes  (-> (pcb/empty-changes it page-id)
                             (pcb/with-objects (:objects (dsh/lookup-page state file-id page-id)))
                             (pcb/set-save-undo? false)
                             (pcb/update-shapes ids #(get objects (:id %))))]
            (rx/of (dch/commit-changes changes))))))))
