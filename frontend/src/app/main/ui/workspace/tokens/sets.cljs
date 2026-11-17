;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.tokens.sets
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.files.tokens :as cfo]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.workspace.tokens.sets.helpers :as sets-helpers]
   [app.main.ui.workspace.tokens.sets.lists :refer [controlled-sets-list*]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc context-banner*
  "Tells why the set checkboxes are locked: they show the sets of the
  token context the selection is in, set from the design tab."
  {::mf/private true}
  [{:keys [scope-chain]}]
  (let [objects  (mf/deref refs/workspace-page-objects)
        selected (mf/deref refs/selected-shapes)
        origin   (:origin (last scope-chain))
        name     (if (= :page origin)
                   (tr "workspace.tokens.contexts.banner-page")
                   (get-in objects [origin :name]))
        on-deselect (mf/use-fn #(st/emit! (dws/deselect-all)))]
    [:div {:class (stl/css :context-banner)}
     [:p {:class (stl/css :context-banner-text)}
      (tr "workspace.tokens.contexts.banner" name)]
     (when (seq selected)
       [:button {:class (stl/css :context-banner-button)
                 :on-click on-deselect}
        (tr "workspace.tokens.contexts.banner-deselect")])]))

(defn- on-select-token-set-click [id]
  (st/emit! (dwtl/clear-tokens-paths)
            (dwtl/set-selected-token-set-id id)))

(defn- on-toggle-token-set-click [id]
  (st/emit! (dwtl/toggle-token-set id)))

(defn- on-toggle-token-set-group-click [path]
  (st/emit! (dwtl/toggle-token-set-group path)))

(mf/defc sets-list*
  [{:keys [selected new-path edition-id]}]

  (let [tokens-lib
        (mf/use-ctx ctx/tokens-lib)

        tokens-status
        (mf/use-ctx ctx/tokens-status)

        scope-chain
        (mf/deref refs/selection-token-scope-chain)

        ;; The status of the selection's context, nil in the global one.
        ;; The checkboxes show its sets, and do not change them: a context
        ;; holds themes, not sets.
        context-status
        (mf/with-memo [tokens-status tokens-lib scope-chain]
          (when (and (seq scope-chain) tokens-status tokens-lib)
            (cfo/effective-tokens-status tokens-status tokens-lib scope-chain)))

        token-set-in-context?
        (mf/with-memo [context-status]
          (when context-status
            (fn [set-id]
              (ctos/set-active? context-status set-id))))

        token-sets
        (some-> tokens-lib (ctob/get-set-tree))

        can-edit-tokens?
        (mf/use-ctx ctx/can-edit-tokens?)

        token-set-active?
        (mf/use-fn
         (mf/deps tokens-status context-status)
         (fn [set-id]
           (ctos/set-active? (or context-status tokens-status) set-id)))

        token-set-group-active?
        (mf/use-fn
         (mf/deps tokens-lib context-status)
         (fn [group-path]
           (if context-status
             (cfo/sets-at-path-all-active? context-status tokens-lib group-path)
             ;; FIXME
             @(refs/token-sets-at-path-all-active group-path))))

        on-reset-edition
        (mf/use-fn
         (mf/deps can-edit-tokens?)
         (fn [_]
           (when can-edit-tokens?
             (st/emit! (dwtl/clear-token-set-edition)
                       (dwtl/clear-token-set-creation)))))

        on-start-edition
        (mf/use-fn
         (mf/deps can-edit-tokens?)
         (fn [id]
           (when can-edit-tokens?
             (st/emit! (dwtl/start-token-set-edition id)))))]

    [:*
     (when context-status
       [:> context-banner* {:scope-chain scope-chain}])
     [:> controlled-sets-list*
     {:tokens-lib tokens-lib
      :token-sets token-sets

      :is-token-set-active token-set-active?
      :is-token-set-group-active token-set-group-active?
      :is-token-set-in-context token-set-in-context?
      :is-toggle-disabled (some? context-status)
      :on-select on-select-token-set-click

      :selected selected
      :new-path new-path
      :edition-id edition-id

      :origin "set-panel"
      :on-start-edition on-start-edition
      :on-reset-edition on-reset-edition

      :on-toggle-token-set on-toggle-token-set-click
      :on-toggle-token-set-group on-toggle-token-set-group-click
      :on-update-token-set (partial sets-helpers/on-update-token-set tokens-lib)
      :on-update-token-set-group sets-helpers/on-update-token-set-group
      :on-create-token-set sets-helpers/on-create-token-set}]]))
