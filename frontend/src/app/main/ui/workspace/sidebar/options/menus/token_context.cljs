;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.options.menus.token-context
  "The token context section of the design tab, like the variable modes
  of Figma's right sidebar: one select per theme group, Auto (inherited,
  with where it comes from) or one of its themes. The groups that change
  a token used in the selection come first, the others on demand."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.files.tokens :as cfo]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.tokens.scopes :as dwts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.controls.switch :refer [switch*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; Option ids of the selects. Theme ids are uuids, so they cannot collide.
(def ^:private auto-option-id "auto")
(def ^:private mixed-option-id "mixed")
(def ^:private add-option-id "add")

(defn- origin-label
  [origin objects]
  (case origin
    :file (tr "workspace.options.token-context.origin-file")
    :page (tr "workspace.options.token-context.origin-page")
    (get-in objects [origin :name])))

(defn- auto-label
  "The Auto option: what the group resolves with when the context does
  not choose, and where it comes from."
  [inherited tokens-lib objects]
  (if-let [{:keys [theme-id origin]} inherited]
    (tr "workspace.options.token-context.auto-inherited"
        (:name (ctob/get-theme tokens-lib theme-id))
        (origin-label origin objects))
    (tr "workspace.options.token-context.auto")))

(defn- group-theme-id
  "The id of the theme of `group` that `scope` chooses, if any."
  [tokens-lib scope group]
  (d/seek #(= group (:group (ctob/get-theme tokens-lib %))) (:themes scope)))

(mf/defc group-row*
  {::mf/private true}
  [{:keys [tokens-lib group scopes inherited objects on-select]}]
  (let [themes   (into [] (comp (keep #(ctob/get-theme tokens-lib %))
                                (remove ctob/hidden-theme?))
                       (ctob/get-themes-in-group tokens-lib group))
        chosen   (into #{} (map #(group-theme-id tokens-lib % group)) scopes)
        mixed?   (> (count chosen) 1)
        theme-id (first chosen)
        selected (cond mixed?          mixed-option-id
                       (some? theme-id) (str theme-id)
                       :else           auto-option-id)
        options  (cond-> [{:id auto-option-id
                           :label (auto-label inherited tokens-lib objects)}]
                   mixed? (conj {:id mixed-option-id
                                 :label (tr "workspace.options.token-context.mixed")})
                   :always (into (map (fn [theme] {:id (str (:id theme)) :label (:name theme)}))
                                 themes))
        ;; A group whose sets define no token cannot change anything,
        ;; as when a Figma file refers to library variables it lacks
        no-tokens? (not-any? (fn [theme]
                             (some #(some-> (ctob/get-set-by-name tokens-lib %) ctob/get-tokens- seq)
                                   (:sets theme)))
                           themes)

        on-change
        (mf/use-fn
         (mf/deps group on-select)
         (fn [option-id]
           (when-not (= option-id mixed-option-id)
             (on-select group (when-not (= option-id auto-option-id)
                                (uuid/parse option-id))))))]
    [:div {:class (stl/css :row)}
     [:span {:class (stl/css :row-label)
             :title (if no-tokens?
                      (tr "workspace.options.token-context.empty-group-hint" group)
                      group)}
      group
      (when no-tokens?
        [:span {:class (stl/css :row-label-empty)}
         (tr "workspace.options.token-context.empty-group")])]
     [:div {:class (stl/css :row-select)}
      [:> select* {:key (str group "-" selected)
                   :default-selected selected
                   :options options
                   :aria-label group
                   :on-change on-change}]]]))

(mf/defc token-context-menu*
  "`scopes` are the scopes of the selected holders (nil for those without
  one), `chain-above` the scopes above the first one (see
  `cfo/scope-chain`), `token-names` the tokens used in the selection.
  The groups listed are those that change one of these tokens or that a
  scope of the selection or above chooses; the others can be added from
  a select.
  `on-update` gets a fn of a scope, `on-remove` removes the contexts."
  [{:keys [scopes chain-above token-names on-update on-remove]}]
  (let [tokens-lib    (mf/deref refs/tokens-lib)
        tokens-status (mf/deref refs/tokens-status)
        objects       (mf/deref refs/workspace-page-objects)
        ;; The groups added by hand to choose a theme of, as Figma's
        ;; "Apply variable mode" adds a collection: they stay listed
        ;; until the selection changes (the parent keys this component
        ;; by selection)
        added*        (mf/use-state [])
        added         (deref added*)

        groups
        (mf/with-memo [tokens-lib]
          (when tokens-lib
            (into [] (remove #(empty? (ctob/get-themes-in-group tokens-lib %)))
                  (ctob/get-theme-groups tokens-lib))))

        ;; The groups that change a token used in the selection, and
        ;; those the selection or a container above already chooses
        ;; (a Figma file sets the modes of collections whose
        ;; variables may be missing)
        ;; Listed: the groups a scope of the selection or above
        ;; chooses, and those defining a token the selection uses
        ;; itself, as Figma lists the collections whose modes a layer
        ;; sets. A group with a single theme has nothing to switch
        switchable? #(> (count (ctob/get-themes-in-group tokens-lib %)) 1)

        relevant
        (mf/with-memo [tokens-lib token-names scopes chain-above]
          (when tokens-lib
            (into (into #{}
                        (filter switchable?)
                        (cfo/direct-theme-groups tokens-lib token-names))
                  (keep #(:group (ctob/get-theme tokens-lib %)))
                  (mapcat :themes (concat scopes chain-above)))))

        ;; The groups reached only through references come first in the
        ;; select: they change these tokens too, further down an alias
        ;; chain
        indirect
        (mf/with-memo [tokens-lib token-names]
          (when tokens-lib
            (into #{}
                  (filter switchable?)
                  (cfo/relevant-theme-groups tokens-lib token-names))))

        inherited
        (mf/with-memo [tokens-status tokens-lib chain-above]
          (when (and tokens-status tokens-lib)
            (cfo/theme-origin-by-group tokens-status tokens-lib chain-above)))

        has-context? (some some? scopes)
        isolated?    (and has-context? (every? #(= :isolated (:mode %)) scopes))
        main-groups  (filterv #(contains? relevant %) groups)
        other-groups (filterv #(not (contains? relevant %)) groups)
        other-groups (->> other-groups
                          (filterv #(not (contains? (set added) %)))
                          (sort-by #(if (contains? indirect %) 0 1)))
        shown        (into main-groups (remove (set main-groups)) added)

        on-select
        (mf/use-fn
         (mf/deps tokens-lib on-update)
         (fn [group theme-id]
           (on-update #(dwts/set-group-theme % tokens-lib group theme-id))))

        on-toggle-inherit
        (mf/use-fn
         (mf/deps on-update)
         (fn [inherit?]
           (on-update #(if inherit? (dissoc % :mode) (assoc % :mode :isolated)))))

        on-add
        (mf/use-fn
         (fn [group]
           (when-not (= group add-option-id)
             (swap! added* conj group))))]

    (when (seq groups)
      [:section {:class (stl/css :element-set)
                 :aria-label (tr "workspace.options.token-context.title")}
       [:div {:class (stl/css :element-title)}
        [:> title-bar* {:collapsable false
                        :title (tr "workspace.options.token-context.title")}
         (when has-context?
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "workspace.options.token-context.remove")
                             :on-click on-remove
                             :icon i/remove}])]]

       [:div {:class (stl/css :element-content)}
        (when (empty? main-groups)
          [:p {:class (stl/css :hint)}
           (tr "workspace.options.token-context.no-relevant-groups")])

        (for [group shown]
          [:> group-row* {:key group
                          :tokens-lib tokens-lib
                          :group group
                          :scopes scopes
                          :inherited (when-not isolated? (get inherited group))
                          :objects objects
                          :on-select on-select}])

        ;; The other groups through a select, not a list: a design
        ;; system can have dozens of them
        (when (seq other-groups)
          [:div {:class (stl/css :row)}
           [:div {:class (stl/css :row-add)}
            [:> select* {:key (str "add-" (count added))
                         :default-selected add-option-id
                         :options (into [{:id add-option-id
                                          :label (tr "workspace.options.token-context.add-group"
                                                     (count other-groups))}]
                                        (map (fn [group] {:id group :label group}))
                                        other-groups)
                         :aria-label (tr "workspace.options.token-context.add-group"
                                         (count other-groups))
                         :on-change on-add}]]])

        (when has-context?
          [:div {:class (stl/css :row)}
           [:span {:class (stl/css :row-label :row-label-wide)}
            (tr "workspace.options.token-context.inherit")]
           [:div {:class (stl/css :row-switch)}
            [:> switch* {:key (str "inherit-" isolated?)
                         :default-checked (not isolated?)
                         :aria-label (tr "workspace.options.token-context.inherit")
                         :on-change on-toggle-inherit}]]])]])))

(mf/defc shapes-token-context-menu*
  "The token context section of the selected boards, groups, booleans
  and component copies; nothing when another kind of shape is selected."
  [{:keys [shapes]}]
  (let [page    (mf/deref refs/workspace-page)
        objects (mf/deref refs/workspace-page-objects)
        holders (filterv dwts/scope-holder? shapes)
        ids     (mapv :id holders)

        chain-above
        (mf/with-memo [page objects holders]
          (when-let [parent-id (:parent-id (first holders))]
            (cfo/scope-chain page objects parent-id)))

        token-names
        (mf/with-memo [objects ids]
          (dwts/applied-token-names objects ids))

        on-update
        (mf/use-fn
         (mf/deps ids)
         #(st/emit! (dwts/update-token-scopes ids %)))

        on-remove
        (mf/use-fn
         (mf/deps ids)
         #(st/emit! (dwts/remove-token-scopes ids)))]

    (when (and (seq holders) (= (count holders) (count shapes)))
      [:> token-context-menu* {:key (str ids)
                               :scopes (mapv :token-scope holders)
                               :chain-above (if (= 1 (count holders)) chain-above [])
                               :token-names token-names
                               :on-update on-update
                               :on-remove on-remove}])))

(mf/defc page-token-context-menu*
  "The token context section of the current page."
  []
  (let [page    (mf/deref refs/workspace-page)
        objects (mf/deref refs/workspace-page-objects)

        token-names
        (mf/with-memo [objects]
          (dwts/applied-token-names objects [uuid/zero]))

        on-update
        (mf/use-fn #(st/emit! (dwts/update-page-token-scope nil %)))

        on-remove
        (mf/use-fn #(st/emit! (dwts/set-page-token-scope nil)))]
    [:> token-context-menu* {:scopes [(:token-scope page)]
                             :chain-above []
                             :token-names token-names
                             :on-update on-update
                             :on-remove on-remove}]))
