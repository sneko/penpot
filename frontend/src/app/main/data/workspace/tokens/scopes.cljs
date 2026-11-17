;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.tokens.scopes
  "Token scopes: the theme overrides pages and shapes carry in their
  `:token-scope` (see `cto/schema:token-scope`). The UI calls them token
  contexts."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.tokens :as cfo]
   [app.common.types.component :as ctk]
   [app.common.types.file :as ctf]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.data.workspace.undo :as dwu]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

;; A scope without themes: a context that inherits everything
(def ^:private empty-scope {:themes #{}})

(defn scope-holder?
  "Whether `shape` can have a token scope: a board (so also component
  mains and variants), a group, a boolean, or the head of a component
  copy whatever its type. Resolving costs the same whatever the type of
  the shapes that hold scopes: it depends on the number of distinct
  chains of scopes, not on where they are."
  [shape]
  (and (some? shape)
       (not= (:id shape) uuid/zero)
       (or (cfh/frame-shape? shape)
           (cfh/group-shape? shape)
           (cfh/bool-shape? shape)
           (ctk/instance-head? shape))))

(defn applied-token-names
  "The names of the tokens applied to the shapes of `ids` and their
  children: the groups that change one of them are the relevant ones
  (see `cfo/relevant-theme-groups`)."
  [objects ids]
  (into #{}
        (comp (mapcat #(cfh/get-children-ids-with-self objects %))
              (mapcat #(vals (get-in objects [% :applied-tokens]))))
        ids))

(defn set-group-theme
  "The scope with the theme `theme-id` as the theme of `group`, or with
  no theme of that group when `theme-id` is nil."
  [scope tokens-lib group theme-id]
  (let [in-group? (fn [id] (= group (:group (ctob/get-theme tokens-lib id))))]
    (assoc scope :themes (cond-> (into #{} (remove in-group?) (:themes scope))
                           theme-id (conj theme-id)))))

(defn theme-path
  "A theme as \"Group / Theme\", or its name when it has no group."
  [{:keys [group name]}]
  (if (seq group)
    (str group " / " name)
    name))

(defn- themes-label
  "The names of `themes` in theme group order, or a placeholder."
  [tokens-lib themes]
  (let [order (into {} (map-indexed (fn [i group] [group i])) (ctob/get-theme-groups tokens-lib))
        names (->> themes
                   (sort-by #(get order (:group %) -1))
                   (map :name)
                   (str/join " · "))]
    (if (str/blank? names)
      (tr "workspace.token-scope-area.no-themes")
      names)))

(defn scope-label
  "The names of the scope's themes, and a mark when it is isolated."
  [tokens-lib {:keys [themes mode]}]
  (let [names (themes-label tokens-lib (keep #(ctob/get-theme tokens-lib %) themes))]
    (if (= mode :isolated)
      (tr "workspace.token-scope-area.isolated" names)
      names)))

(defn- with-propagation
  "`event`, which changes scopes, and the propagation of the tokens that
  resolve differently because of it, as a single undo entry: undoing it
  brings the scopes back too, not only the values. Only the shapes of
  `ids-by-page` (see `dwtp/propagate-tokens`) and their children can
  resolve differently: a page mapped to nil is propagated whole."
  [event ids-by-page]
  (let [undo-id (js/Symbol)]
    (rx/of (dwu/start-undo-transaction undo-id)
           event
           (dwtp/propagate-workspace-tokens ids-by-page)
           (dwu/commit-undo-transaction undo-id))))

(defn update-page-token-scope
  "Sets the token scope of the page `page-id` (the current one with nil)
  to `f` of it, and propagates. A page without one starts empty, so it
  inherits every group from the file; `f` giving nil removes it."
  [page-id f]
  (ptk/reify ::update-page-token-scope
    ptk/WatchEvent
    (watch [it state _]
      (let [page    (if page-id
                      (dsh/lookup-page state page-id)
                      (dsh/lookup-page state))
            scope   (or (:token-scope page) empty-scope)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-page page)
                        (pcb/mod-page {:token-scope (f scope)}))]
        (with-propagation (dch/commit-changes changes) {(:id page) nil})))))

(defn set-page-token-scope
  "Sets the token scope of the page `page-id` (the current one when not
  given), or removes it with nil, and propagates."
  ([scope]
   (set-page-token-scope nil scope))
  ([page-id scope]
   (update-page-token-scope page-id (constantly scope))))

(defn- set-scopes
  "Sets the scopes of the shapes of `ids` to `f` of each shape (removes
  them where it gives nil) and propagates. On a component copy it is an
  override (`:token-scope-group` touched) whose `:overrides` list the
  groups the copy changed: it keeps them on sync and follows its main
  for the others. On a main it is the default of its copies."
  [ids f]
  (ptk/reify ::set-scopes
    ptk/WatchEvent
    (watch [_ state _]
      (let [tokens-lib (dsh/lookup-tokens-lib state)
            page-id    (:current-page-id state)]
        (with-propagation
          (dwsh/update-shapes ids
                              (fn [shape]
                                (let [before (:token-scope shape)
                                      after  (f shape)]
                                  (cond
                                    (nil? after)
                                    (dissoc shape :token-scope)

                                    (and (ctk/in-component-copy? shape) (some? tokens-lib))
                                    (assoc shape :token-scope
                                           (cfo/copy-scope-overrides tokens-lib before after))

                                    :else
                                    (assoc shape :token-scope after)))))
          ;; The copies of a main follow through the component sync,
          ;; which propagates the shapes it changes
          {page-id ids})))))

(defn set-shape-token-scope
  "Sets the token scope of a shape, or removes it with nil, and propagates
  the tokens."
  [shape-id scope]
  (ptk/reify ::set-shape-token-scope
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (set-scopes [shape-id] (constantly scope))))))

(defn- holder-ids
  [state shape-ids]
  (let [objects (dsh/lookup-page-objects state)]
    (into [] (filter #(scope-holder? (get objects %))) shape-ids)))

(defn update-token-scopes
  "Sets the scope of each shape of `shape-ids` that can hold one to `f`
  of its scope, and propagates. A shape without one starts empty: it
  inherits every group from the scopes above it and the file."
  [shape-ids f]
  (ptk/reify ::update-token-scopes
    ptk/WatchEvent
    (watch [_ state _]
      (let [ids (holder-ids state shape-ids)]
        (when (seq ids)
          (rx/of (set-scopes ids #(f (or (:token-scope %) empty-scope)))))))))

(defn remove-token-scopes
  "Removes the scopes of the shapes of `shape-ids` and propagates. A
  component copy goes back to the scope of its main instead: no
  overrides, it follows the main again."
  [shape-ids]
  (ptk/reify ::remove-token-scopes
    ptk/WatchEvent
    (watch [_ state _]
      (let [file      (dsh/lookup-file state)
            page      (dsh/lookup-page state)
            page-id   (:id page)
            libraries (dsh/lookup-libraries state)
            main-scope
            (fn [shape]
              (when (ctk/in-component-copy? shape)
                (let [ref-shape (ctf/find-ref-shape file page libraries shape)]
                  (-> (or (:token-scope ref-shape) empty-scope)
                      (dissoc :mode-overridden)
                      (assoc :overrides #{})))))]
        (with-propagation
          (dwsh/update-shapes shape-ids
                              (fn [shape]
                                (if-let [scope (main-scope shape)]
                                  (assoc shape :token-scope scope)
                                  (dissoc shape :token-scope))))
          {page-id shape-ids})))))
