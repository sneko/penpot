;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.logic.token-scopes-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.config :as cf]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.data.workspace.tokens.scopes :as dwts]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [frontend-tests.tokens.helpers.state :as tohs]
   [potok.v2.core :as ptk]))

(def ^:private original-flags cf/flags)

;; The scoped resolution runs only with tokenscript and the scopes flag.
(t/use-fixtures :each
  {:before (fn []
             (thp/reset-idmap!)
             (thw/setup-wasm-mocks!)
             (set! cf/flags (conj original-flags :tokenscript :token-scoped-themes)))
   :after  (fn []
             (thw/teardown-wasm-mocks!)
             (set! cf/flags original-flags))})

(defn- setup-file
  "The sets `mobile` and `desktop` define `radius` as 8 and 16, activated
  by the themes Mobile and Desktop of the group Platform. Mobile is the
  file's theme. Two boards hold a rect each with `radius` applied; the
  Desktop board is scoped to its theme."
  []
  (-> (ctht/sample-file-with-tokens
       :lib-fn #(-> %
                    (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set-mobile) :name "mobile"))
                    (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set-desktop) :name "desktop"))
                    (ctob/add-theme (ctob/make-token-theme :id (cthi/new-id! :mobile)
                                                           :name "Mobile"
                                                           :group "Platform"
                                                           :sets #{"mobile"}))
                    (ctob/add-theme (ctob/make-token-theme :id (cthi/new-id! :desktop)
                                                           :name "Desktop"
                                                           :group "Platform"
                                                           :sets #{"desktop"}))
                    (ctob/add-token (cthi/id :set-mobile)
                                    (ctob/make-token {:name "radius" :value "8" :type :border-radius}))
                    (ctob/add-token (cthi/id :set-desktop)
                                    (ctob/make-token {:name "radius" :value "16" :type :border-radius})))
       :status-fn #(ctos/set-tokens-status % #{(cthi/id :mobile)} #{(cthi/id :set-mobile)}))
      (ctho/add-frame :board-mobile)
      (ctho/add-frame :board-desktop :token-scope {:themes #{(cthi/id :desktop)}})
      (ctho/add-rect :rect-mobile :parent-label :board-mobile :applied-tokens {:r1 "radius"})
      (ctho/add-rect :rect-desktop :parent-label :board-desktop :applied-tokens {:r1 "radius"})))

(defn- radius
  [state label]
  (-> (ths/get-file-from-state state)
      (cths/get-shape label)
      (:r1)))

(t/deftest test-propagate-scoped-board
  (t/testing "a board scoped to a theme resolves its shapes with that theme"
    (t/async
      done
      (let [file  (setup-file)
            store (ths/setup-store file)]
        (tohs/run-store-async
         store done [(dwtp/propagate-workspace-tokens)]
         (fn [state]
           (t/is (= 8 (radius state :rect-mobile)))
           (t/is (= 16 (radius state :rect-desktop)))))))))

(t/deftest test-propagate-without-flag
  (t/testing "without the flag every shape resolves with the file's themes"
    (t/async
      done
      (let [file  (setup-file)
            store (ths/setup-store file)]
        (set! cf/flags (disj cf/flags :token-scoped-themes))
        (tohs/run-store-async
         store done [(dwtp/propagate-workspace-tokens)]
         (fn [state]
           (t/is (= 8 (radius state :rect-mobile)))
           (t/is (= 8 (radius state :rect-desktop)))))))))

(t/deftest test-set-shape-token-scope
  (t/testing "scoping a board re-resolves its shapes, and only them"
    (t/async
      done
      (let [file  (setup-file)
            store (ths/setup-store file)
            board (cths/get-shape file :board-mobile)]
        (tohs/run-store-async
         ;; The whole file first: scoping a board propagates its subtree
         store done [(dwtp/propagate-workspace-tokens)
                     (dwts/set-shape-token-scope (:id board) {:themes #{(cthi/id :desktop)}})]
         (fn [state]
           (let [board' (cths/get-shape (ths/get-file-from-state state) :board-mobile)]
             (t/is (= {:themes #{(cthi/id :desktop)}} (:token-scope board')))
             (t/is (= 16 (radius state :rect-mobile)))
             (t/is (= 16 (radius state :rect-desktop))))))))))

(t/deftest test-set-page-token-scope
  (t/testing "a page scope applies to every shape without a nearer scope"
    (t/async
      done
      (let [file  (setup-file)
            store (ths/setup-store file)]
        (tohs/run-store-async
         store done [(dwts/set-page-token-scope {:themes #{(cthi/id :desktop)}})
                     (dwts/set-shape-token-scope (:id (cths/get-shape file :board-desktop))
                                                 {:themes #{(cthi/id :mobile)}})]
         (fn [state]
           (t/is (= 16 (radius state :rect-mobile)))
           (t/is (= 8 (radius state :rect-desktop)))))))))

(t/deftest test-rename-theme-group-moves-overrides
  (t/testing "the overrides of a context follow a theme group renamed theme by theme"
    (t/async
      done
      (let [file     (-> (setup-file)
                         ;; A copy that chose Desktop, another that chose Auto
                         (ctho/add-frame :board-copy :token-scope {:themes #{(cthi/id :desktop)}
                                                                   :overrides #{"Platform"}})
                         (ctho/add-frame :board-auto :token-scope {:themes #{}
                                                                   :overrides #{"Platform"}}))
            store    (ths/setup-store file)
            lib      (ctht/get-tokens-lib file)
            move     (fn [label]
                       (let [theme (ctob/get-theme lib (cthi/id label))]
                         (dwtl/update-token-theme (:id theme) (assoc theme :group "Device"))))]
        (tohs/run-store-async
         store done [(move :desktop) (move :mobile)]
         (fn [state]
           (let [file' (ths/get-file-from-state state)]
             (t/is (= #{"Device"} (get-in (cths/get-shape file' :board-copy) [:token-scope :overrides])))
             (t/is (= #{"Device"} (get-in (cths/get-shape file' :board-auto) [:token-scope :overrides]))))))))))

(t/deftest test-masked-group-tokens
  (t/testing "a group whose tokens another group defines later at the same level names that group"
    (let [file    (-> (setup-file)
                      (ctht/update-tokens-lib
                       #(-> %
                            (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set-modal) :name "modal"))
                            (ctob/add-theme (ctob/make-token-theme :id (cthi/new-id! :modal-default)
                                                                   :name "Default"
                                                                   :group "Modal"
                                                                   :sets #{"modal"}))
                            (ctob/add-token (cthi/id :set-modal)
                                            (ctob/make-token {:name "radius" :value "4" :type :border-radius})))))
          lib     (ctht/get-tokens-lib file)
          status  (ctht/get-tokens-status file)
          objects (:objects (cthf/current-page file))
          root    {:id (cthi/id :board-desktop)
                   :scope {:themes #{(cthi/id :desktop) (cthi/id :modal-default)}}
                   :chain-above []}]
      (t/is (= {"radius" #{"Modal"}}
               (dwts/masked-group-tokens status lib objects [root] "Platform")))
      (t/is (= {} (dwts/masked-group-tokens status lib objects [root] "Modal")))
      (t/is (= {} (dwts/masked-group-tokens status lib objects
                                            [(update root :scope assoc :themes #{(cthi/id :desktop)})]
                                            "Platform"))))))

(t/deftest ^:async test-undo-theme-change-at-once
  (t/testing "one undo brings back the active theme and the values it propagated"
    (let [file  (setup-file)
          store (ths/setup-store file)
          state #(deref store)
          theme #(-> (ths/get-file-from-state (state)) ctht/get-tokens-status ctos/get-active-theme-ids)]
      (await
       (mock/with-mocks* {st/state store st/stream (ptk/input-stream store)}
         (st/emit! (tohs/watch-undo-stack)
                   (dwtp/propagate-workspace-tokens))
         (await (async/wait-for #(= 8 (radius (state) :rect-mobile)) "file propagated"))
         (st/emit! dwu/reinitialize-undo
                   (dwtl/set-token-theme-active (cthi/id :desktop) true))
         (await (async/wait-for #(and (= 16 (radius (state) :rect-mobile))
                                      (seq (get-in (state) [:workspace-undo :items])))
                                "theme changed"))
         (t/is (contains? (theme) (cthi/id :desktop)))
         (t/is (not (contains? (theme) (cthi/id :mobile))))
         (st/emit! dwu/undo)
         (await (async/wait-for #(= 8 (radius (state) :rect-mobile)) "undone"))
         (t/is (contains? (theme) (cthi/id :mobile)))
         (t/is (not (contains? (theme) (cthi/id :desktop)))))))))
