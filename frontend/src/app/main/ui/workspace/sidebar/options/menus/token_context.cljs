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
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; Option ids of the selects. Theme ids are uuids, so they cannot collide.
(def ^:private auto-option-id "auto")
(def ^:private mixed-option-id "mixed")

;; The groups added by hand to choose a theme of, as Figma's "Apply
;; variable mode" adds a collection, for the selection of `:key`: they
;; stay listed until the selection changes. Shared by the two places the
;; section can render in (see `token-context-menu*`).
(defonce ^:private added-groups* (atom {:key nil :groups []}))

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
  [{:keys [tokens-lib group scopes inherited objects on-select is-ineffective]}]
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
     [:span {:class (stl/css-case :row-label true
                                  :row-label-ineffective (and is-ineffective (not no-tokens?)))
             :title (cond
                      no-tokens?     (tr "workspace.options.token-context.empty-group-hint" group)
                      is-ineffective (tr "workspace.options.token-context.ineffective-hint" group)
                      :else          group)}
      group
      (cond
        no-tokens?
        [:span {:class (stl/css :row-label-empty)}
         (tr "workspace.options.token-context.empty-group")]

        is-ineffective
        [:span {:class (stl/css :row-label-empty)}
         (tr "workspace.options.token-context.ineffective")])]
     [:div {:class (stl/css :row-select)}
      [:> select* {:key (str group "-" selected)
                   :default-selected selected
                   :options options
                   :aria-label group
                   :on-change on-change}]]]))

(mf/defc token-context-menu*
  "`scopes` are the scopes of the selected holders (nil for those without
  one), `chain-above` the scopes above the first one (see
  `cfo/scope-chain`), `main-scopes` those of the mains of the selected
  component copies, `token-names` the tokens used in the selection.
  The groups listed are those that change one of these tokens or that a
  scope of the selection or above chooses; the others can be added from
  a select.
  `on-update` gets a fn of a scope, `on-remove` removes the contexts.
  The section renders where `placement` says it should: `:top` when the
  selection uses token contexts, `:bottom` otherwise, out of the way, as
  the sidebar renders it in both places. `selection-key` identifies the
  selection."
  [{:keys [scopes chain-above main-scopes token-names direct-groups on-update on-remove
           placement selection-key roots]}]
  (let [tokens-lib    (mf/deref refs/tokens-lib)
        tokens-status (mf/deref refs/tokens-status)
        objects       (mf/deref refs/workspace-page-objects)
        added-state   (mf/deref added-groups*)
        added         (if (= (:key added-state) selection-key) (:groups added-state) [])
        add-group!    (fn [group]
                        (swap! added-groups*
                               (fn [{:keys [key groups]}]
                                 (let [groups (if (= key selection-key) groups [])]
                                   {:key selection-key
                                    :groups (cond-> groups
                                              (not (some #{group} groups)) (conj group))}))))
        ;; The added groups are forgotten when the selection changes,
        ;; also when the same shapes are selected again
        _             (mf/with-effect [selection-key]
                        (when (not= (:key @added-groups*) selection-key)
                          (reset! added-groups* {:key selection-key :groups []})))
        ;; :add or :options, the dropdown open from the title bar
        menu*         (mf/use-state nil)
        menu          (deref menu*)

        groups
        (mf/with-memo [tokens-lib]
          (when tokens-lib
            (into [] (remove #(empty? (ctob/get-themes-in-group tokens-lib %)))
                  (ctob/get-theme-groups tokens-lib))))

        ;; Listed, as Figma lists on a layer the collections whose
        ;; modes it sets: the groups a context of the selection or of
        ;; the main of a copy chooses (a component describes there what
        ;; its copies can switch). The others are in the add menu, those
        ;; changing a token used below first
        switchable? #(> (count (ctob/get-themes-in-group tokens-lib %)) 1)

        relevant
        (mf/with-memo [tokens-lib scopes main-scopes]
          (when tokens-lib
            (into #{}
                  (keep #(:group (ctob/get-theme tokens-lib %)))
                  (mapcat :themes (concat scopes main-scopes)))))

        ;; In the add menu: first the groups defining a token used below
        ;; and not set by a context below, then those reached through
        ;; references
        direct
        (mf/with-memo [direct-groups]
          (into #{} (filter switchable?) direct-groups))

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
        other-groups (filterv #(not (contains? relevant %)) groups)
        other-groups (->> other-groups
                          (filterv #(not (contains? (set added) %)))
                          (sort-by #(cond (contains? direct %) 0
                                          (contains? indirect %) 1
                                          :else 2)))
        ;; In the order of the library, added groups included: a group
        ;; added by hand takes the place it keeps once reselected
        shown        (let [added (set added)]
                       (filterv #(or (contains? relevant %) (contains? added %)) groups))

        on-select
        (mf/use-fn
         (mf/deps tokens-lib on-update)
         (fn [group theme-id]
           (on-update #(dwts/set-group-theme % tokens-lib group theme-id))))

        on-toggle-inherit
        (mf/use-fn
         (mf/deps on-update isolated?)
         (fn []
           (reset! menu* nil)
           (on-update #(if isolated? (dissoc % :mode) (assoc % :mode :isolated)))))

        on-remove'
        (mf/use-fn
         (mf/deps on-remove)
         (fn []
           (reset! menu* nil)
           (on-remove)))

        on-close-menu (mf/use-fn #(reset! menu* nil))

        on-open-add
        (mf/use-fn #(swap! menu* (fn [m] (when-not (= m :add) :add))))

        on-open-options
        (mf/use-fn #(swap! menu* (fn [m] (when-not (= m :options) :options))))

        used?         (or has-context? (seq shown))

        displayed?    (and (seq groups) (= placement (if used? :top :bottom)))

        ;; The listed groups whose choice changes nothing below: greyed.
        ;; Resolving each theme of each group costs up to a few hundred
        ;; milliseconds, so it runs after the section is shown (only
        ;; where it is shown) instead of delaying the selection
        ineffective*  (mf/use-state #{})
        ineffective   (deref ineffective*)

        ;; One group per timer, so no long freeze; a result already
        ;; computed for the same tokens and subtree comes from the cache
        ;; By value: `roots` and `shown` are rebuilt on each render
        roots-key     (mapv (juxt :id :scope :chain-above) roots)

        _             (mf/with-effect [tokens-status tokens-lib objects roots-key shown displayed?]
                        (when (and displayed? tokens-status tokens-lib (seq roots) (seq shown))
                          (let [timer     (volatile! nil)
                                cancelled (volatile! false)
                                step      (fn step [groups found]
                                            (when-not @cancelled
                                              (if-let [group (first groups)]
                                                (let [found (cond-> found
                                                              (dwts/ineffective-theme-group? tokens-status tokens-lib
                                                                                             objects roots group)
                                                              (conj group))]
                                                  (vreset! timer (js/setTimeout #(step (rest groups) found) 0)))
                                                ;; Only a different result renders again
                                                (when (not= found @ineffective*)
                                                  (reset! ineffective* found)))))]
                            (vreset! timer (js/setTimeout #(step shown #{}) 50))
                            #(do (vreset! cancelled true)
                                 (js/clearTimeout @timer)))))]

    (when displayed?
      [:section {:class (stl/css :element-set)
                 :aria-label (tr "workspace.options.token-context.title")}
       [:div {:class (stl/css :element-title)}
        [:> title-bar* {:collapsable false
                        :title (tr "workspace.options.token-context.title")}
         [:div {:class (stl/css :title-actions)}
          (when (seq other-groups)
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "workspace.options.token-context.add-group"
                                             (count other-groups))
                             :on-click on-open-add
                             :icon i/add}])
          (when has-context?
            [:> icon-button* {:variant "ghost"
                              :aria-label (tr "workspace.options.token-context.options")
                              :on-click on-open-options
                              :icon i/menu}])]]]

       ;; The other groups in a menu, not a list: a design system can
       ;; have dozens of them
       (when (= menu :add)
         [:& dropdown {:show true :on-close on-close-menu}
          [:ul {:class (stl/css :menu) :role "menu"}
           (for [group other-groups]
             [:li {:key group
                   :class (stl/css :menu-item)
                   :role "menuitem"
                   :on-click (fn []
                               (reset! menu* nil)
                               (add-group! group))}
              [:span {:class (stl/css :menu-item-label)} group]])]])

       (when (= menu :options)
         [:& dropdown {:show true :on-close on-close-menu}
          [:ul {:class (stl/css :menu) :role "menu"}
           [:li {:class (stl/css :menu-item)
                 :role "menuitemcheckbox"
                 :aria-checked (not isolated?)
                 :on-click on-toggle-inherit}
            [:span {:class (stl/css :menu-item-label)}
             (tr "workspace.options.token-context.inherit")]
            [:> i/icon* {:icon-id i/tick
                         :size "s"
                         :class (stl/css-case :menu-check true
                                              :menu-check-visible (not isolated?))}]]
           [:li {:class (stl/css :menu-item)
                 :role "menuitem"
                 :on-click on-remove'}
            [:span {:class (stl/css :menu-item-label)}
             (tr "workspace.options.token-context.remove")]]]])

       [:div {:class (stl/css :element-content)}
        (for [group shown]
          [:> group-row* {:key group
                          :tokens-lib tokens-lib
                          :group group
                          :scopes scopes
                          :inherited (when-not isolated? (get inherited group))
                          :objects objects
                          :is-ineffective (contains? ineffective group)
                          :on-select on-select}])]])))

(mf/defc shapes-token-context-menu*
  "The token context section of the selected boards, groups, booleans
  and component copies; nothing when another kind of shape is selected.
  `placement` as in `token-context-menu*`."
  [{:keys [shapes placement]}]
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

        ;; Not the groups a context below already sets for every use:
        ;; choosing them here would change nothing
        tokens-lib
        (mf/deref refs/tokens-lib)

        direct-groups
        (mf/with-memo [objects ids tokens-lib]
          (when tokens-lib
            (dwts/unmasked-theme-groups objects ids tokens-lib)))

        roots
        (mf/with-memo [page objects holders]
          (mapv (fn [{:keys [id parent-id token-scope]}]
                  {:id id
                   :scope token-scope
                   :chain-above (cfo/scope-chain page objects parent-id)})
                holders))

        ;; The groups the component of a copy is designed with stay
        ;; listed, even back to Auto
        main-scopes
        (mf/with-memo [objects holders]
          (let [state @st/state]
            (into [] (keep #(dwts/main-token-scope state %)) holders)))

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
                               :roots roots
                               :placement placement
                               :selection-key (str ids)
                               :scopes (mapv :token-scope holders)
                               :chain-above (if (= 1 (count holders)) chain-above [])
                               :main-scopes main-scopes
                               :direct-groups direct-groups
                               :token-names token-names
                               :on-update on-update
                               :on-remove on-remove}])))

(mf/defc page-token-context-menu*
  "The token context section of the current page. `placement` as in
  `token-context-menu*`."
  [{:keys [placement]}]
  (let [page    (mf/deref refs/workspace-page)
        objects (mf/deref refs/workspace-page-objects)

        tokens-lib (mf/deref refs/tokens-lib)

        roots
        (mf/with-memo [page]
          [{:id uuid/zero :scope (:token-scope page) :chain-above []}])

        token-names
        (mf/with-memo [objects]
          (dwts/applied-token-names objects [uuid/zero]))

        direct-groups
        (mf/with-memo [objects tokens-lib]
          (when tokens-lib
            (dwts/unmasked-theme-groups objects [uuid/zero] tokens-lib)))

        on-update
        (mf/use-fn #(st/emit! (dwts/update-page-token-scope nil %)))

        on-remove
        (mf/use-fn #(st/emit! (dwts/set-page-token-scope nil)))]
    [:> token-context-menu* {:placement placement
                             :roots roots
                             :selection-key (str "page-" (:id page))
                             :scopes [(:token-scope page)]
                             :chain-above []
                             :token-names token-names
                             :direct-groups direct-groups
                             :on-update on-update
                             :on-remove on-remove}]))
