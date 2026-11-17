;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.token-scope-sync-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- tokens-lib
  []
  (-> (ctob/make-tokens-lib)
      (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :primary) :name "Primary" :group "Action"))
      (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :secondary) :name "Secondary" :group "Action"))
      (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :medium) :name "Medium" :group "Size"))
      (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :large) :name "Large" :group "Size"))))

(defn- set-scope
  [file label scope]
  (let [page    (thf/current-page file)
        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                            #{(thi/id label)}
                                            #(assoc % :token-scope scope)
                                            (:objects page)
                                            {})]
    (thf/apply-changes file changes)))

(defn- sync
  [file]
  (thf/apply-changes file
                     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id file)
                                                     (thi/id :component1)
                                                     (:id file)
                                                     {(:id file) file}
                                                     (:id file))))

(defn- setup
  []
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-simple-component-with-copy :component1 :main-root :main-child :copy-root))
        lib  (tokens-lib)]
    (-> file
        (assoc-in [:data :tokens-lib] lib)
        (assoc-in [:data :tokens-status] (ctos/make-tokens-status))
        (set-scope :main-root {:themes #{(thi/id :primary) (thi/id :medium)}})
        (sync))))

(t/deftest test-copy-follows-main-for-the-groups-it-does-not-override
  (let [file  (-> (setup)
                  ;; The copy chooses Secondary
                  (set-scope :copy-root {:themes #{(thi/id :secondary) (thi/id :medium)}
                                         :overrides #{"Action"}})
                  ;; The main goes Large
                  (set-scope :main-root {:themes #{(thi/id :primary) (thi/id :large)}})
                  (sync))
        scope (:token-scope (ths/get-shape file :copy-root))]
    (t/is (= #{(thi/id :secondary) (thi/id :large)} (:themes scope)))
    (t/is (= #{"Action"} (:overrides scope)))))

(t/deftest test-copy-without-overrides-follows-main
  (let [file  (-> (setup)
                  (set-scope :copy-root {:themes #{(thi/id :primary) (thi/id :medium)} :overrides #{}})
                  (set-scope :main-root {:themes #{(thi/id :secondary) (thi/id :large)}})
                  (sync))
        scope (:token-scope (ths/get-shape file :copy-root))]
    (t/is (= #{(thi/id :secondary) (thi/id :large)} (:themes scope)))))

(t/deftest test-touched-copy-without-overrides-list-keeps-its-scope
  ;; Scopes synced from Figma, or older files: the whole scope is kept
  (let [file  (-> (setup)
                  (set-scope :copy-root {:themes #{(thi/id :secondary)}})
                  (set-scope :main-root {:themes #{(thi/id :primary) (thi/id :large)}})
                  (sync))
        scope (:token-scope (ths/get-shape file :copy-root))]
    (t/is (= #{(thi/id :secondary)} (:themes scope)))))

(t/deftest test-untouched-copy-follows-main
  (let [file  (-> (setup)
                  (set-scope :main-root {:themes #{(thi/id :secondary) (thi/id :large)}})
                  (sync))
        scope (:token-scope (ths/get-shape file :copy-root))]
    (t/is (= #{(thi/id :secondary) (thi/id :large)} (:themes scope)))))
