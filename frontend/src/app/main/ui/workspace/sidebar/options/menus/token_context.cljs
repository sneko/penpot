;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.options.menus.token-context
  "The token context section of the design tab: one select per theme
  group a context of the
  selection chooses, Auto (inherited, with where it comes from) or one of
  its themes. The other groups are added from the \"+\" menu."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.files.tokens :as cfo]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.workspace.tokens.scopes :as dwts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.hooks :as hooks]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [clojure.string :as cstr]
   [rumext.v2 :as mf]))

;; Option ids of the selects. Theme ids are uuids, so they cannot collide.
(def ^:private auto-option-id "auto")
(def ^:private mixed-option-id "mixed")

;; The groups added by hand to choose a theme of, for the selection of
;; `:key`: they
;; stay listed until the selection changes, also to the same shapes
;; again. Shared by the two places the section can render in (see
;; `token-context-menu*`).
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

(defn- common-inherited
  "For each group, what the roots inherit for it (see
  `cfo/theme-origin-by-group`) when they all inherit the same."
  [tokens-status tokens-lib roots]
  (let [[first-root & others] (map #(cfo/theme-origin-by-group tokens-status tokens-lib (:chain-above %))
                                   roots)]
    (into {}
          (filter (fn [[group inherited]]
                    (every? #(= inherited (get % group)) others)))
          first-root)))

(defn- on-menu-key-down
  "Activates a menu item from the keyboard, as a click does."
  [on-activate]
  (fn [event]
    (when (or (kbd/enter? event) (kbd/space? event))
      (.preventDefault event)
      (on-activate))))

(defn- group-theme-id
  "The id of the theme of `group` that `scope` chooses, if any."
  [tokens-lib scope group]
  (d/seek #(= group (:group (ctob/get-theme tokens-lib %))) (:themes scope)))

(mf/defc group-row*
  {::mf/private true}
  [{:keys [tokens-lib group scopes inherited objects on-select effect]}]
  (let [themes   (dwts/group-themes tokens-lib group)
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
        ;; A group whose sets define no token cannot change anything (an
        ;; imported file can refer to sets it lacks)
        no-tokens? (not-any? (fn [theme]
                               (some #(some-> (ctob/get-set-by-name tokens-lib %) ctob/get-tokens- seq)
                                     (:sets theme)))
                             themes)

        is-ineffective (:ineffective? effect)
        masked         (:masked effect)
        ;; The groups giving the value of the tokens this one defines,
        ;; and those tokens, for the hint of the label
        masked-by      (when (seq masked)
                         (cstr/join ", " (sort (into #{} cat (vals masked)))))
        masked-names   (when (seq masked)
                         (let [names (sort (keys masked))]
                           (cond-> (cstr/join ", " (take 5 names))
                             (> (count names) 5) (str ", …"))))
        masked-hint    (when (seq masked)
                         (tr "workspace.options.token-context.masked-hint"
                             group masked-names masked-by))

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
                      masked-hint    masked-hint
                      is-ineffective (tr "workspace.options.token-context.ineffective-hint" group)
                      :else          group)}
      [:span {:class (stl/css :row-label-name)} group]
      ;; Why the group changes nothing, or only some of its tokens, on
      ;; hover of the label: no text, the select takes the room
      (cond
        (or no-tokens? is-ineffective)
        [:> i/icon* {:icon-id i/help
                     :size "s"
                     :class (stl/css :row-label-icon)}]

        masked-hint
        [:> i/icon* {:icon-id i/info
                     :size "s"
                     :class (stl/css :row-label-icon)}])]
     [:div {:class (stl/css :row-select)}
      [:> select* {:key (str group "-" selected)
                   :default-selected selected
                   :options options
                   :aria-label group
                   :on-change on-change}]]]))

(mf/defc token-context-menu*
  "`roots` are the selected holders (maps of `:id`, `:scope`, nil without
  one, and `:chain-above`, the scopes above it, see `cfo/scope-chain`),
  `main-scopes` the scopes of the mains of the selected component copies,
  `token-names` the tokens used in the selection, `direct-groups` the
  groups defining one of them that no context below sets for every use.
  The groups listed are those a scope of the selection or of a main
  chooses, and those added by hand; the others are in the \"+\" menu,
  the direct ones first, then those reached through references.
  `on-update` gets a fn of a scope, `on-remove` removes the contexts.
  The section renders where `placement` says it should: `:top` when the
  selection uses token contexts, `:bottom` otherwise, out of the way, as
  the sidebar renders it in both places. `selection-key` identifies the
  selection. Every vector given is expected memoized by its caller."
  [{:keys [roots main-scopes token-names direct-groups on-update on-remove
           placement selection-key]}]
  (let [tokens-lib    (mf/deref refs/tokens-lib)
        tokens-status (mf/deref refs/tokens-status)
        objects       (mf/deref refs/workspace-page-objects)
        scopes        (mf/with-memo [roots] (mapv :scope roots))
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
        ;; also when the same shapes are selected again (this component
        ;; is unmounted in between)
        _             (mf/with-effect [selection-key]
                        #(swap! added-groups* (fn [{:keys [key] :as state}]
                                                (if (= key selection-key)
                                                  {:key nil :groups []}
                                                  state))))
        ;; :add or :options, the dropdown open from the title bar
        menu*         (mf/use-state nil)
        menu          (deref menu*)
        ;; The group of the add menu whose themes are shown, and where:
        ;; fixed, as the menu scrolls and would clip it
        submenu*      (mf/use-state nil)
        submenu       (deref submenu*)

        groups
        (mf/with-memo [tokens-lib]
          (when tokens-lib
            (dwts/theme-groups tokens-lib)))

        ;; Listed: the groups a context of the selection or of
        ;; the main of a copy chooses (a component describes there what
        ;; its copies can switch). The others are in the add menu, those
        ;; changing a token used below first
        switchable?
        (mf/use-fn
         (mf/deps tokens-lib)
         #(dwts/switchable-group? tokens-lib %))

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
        (mf/with-memo [direct-groups switchable?]
          (into #{} (filter switchable?) direct-groups))

        indirect
        (mf/with-memo [tokens-lib token-names switchable?]
          (when tokens-lib
            (into #{}
                  (filter switchable?)
                  (cfo/relevant-theme-groups tokens-lib token-names))))

        ;; With several shapes selected, only what they all inherit
        inherited
        (mf/with-memo [tokens-status tokens-lib roots]
          (when (and tokens-status tokens-lib (seq roots))
            (common-inherited tokens-status tokens-lib roots)))

        has-context? (some some? scopes)
        isolated?    (and has-context? (every? #(= :isolated (:mode %)) scopes))
        other-groups
        (mf/with-memo [groups relevant added direct indirect]
          (let [added (set added)]
            (->> groups
                 (remove #(or (contains? relevant %) (contains? added %)))
                 (sort-by #(cond (contains? direct %) 0
                                 (contains? indirect %) 1
                                 :else 2))
                 (vec))))

        ;; In the order of the library, added groups included: a group
        ;; added by hand takes the place it keeps once reselected
        shown
        (mf/with-memo [groups relevant added]
          (let [added (set added)]
            (filterv #(or (contains? relevant %) (contains? added %)) groups)))

        on-select
        (mf/use-fn
         (mf/deps tokens-lib on-update selection-key)
         (fn [group theme-id]
           ;; Kept listed until the selection changes, also set back to
           ;; Auto: the row does not vanish under the pointer
           (add-group! group)
           (on-update #(dwts/set-group-theme % tokens-lib group theme-id))))

        on-toggle-inherit
        (mf/use-fn
         (mf/deps on-update)
         (fn []
           (reset! menu* nil)
           (on-update dwts/toggle-isolated)))

        on-remove'
        (mf/use-fn
         (mf/deps on-remove)
         (fn []
           (reset! menu* nil)
           (on-remove)))

        on-close-menu (mf/use-fn #(do (reset! menu* nil)
                                      (reset! submenu* nil)))

        on-open-add
        (mf/use-fn #(swap! menu* (fn [m] (when-not (= m :add) :add))))

        on-open-options
        (mf/use-fn #(swap! menu* (fn [m] (when-not (= m :options) :options))))

        used?         (or has-context? (seq shown))

        displayed?    (and (seq groups) (= placement (if used? :top :bottom)))

        ;; What choosing each listed group does (see
        ;; `dwts/theme-group-effect`): greyed when nothing, the groups
        ;; winning over its tokens named. Resolving each theme of each group costs up to a few hundred
        ;; milliseconds, so it runs after the section is shown (only
        ;; where it is shown) instead of delaying the selection
        effects*      (mf/use-state {})
        effects       (deref effects*)

        ;; One group per timer, so no long freeze; a result already
        ;; computed for the same tokens and subtree comes from the cache
        _             (mf/with-effect [tokens-status tokens-lib objects roots shown displayed?]
                        (when (and displayed? tokens-status tokens-lib (seq roots) (seq shown))
                          (let [timer     (volatile! nil)
                                cancelled (volatile! false)
                                step      (fn step [groups found]
                                            (when-not @cancelled
                                              (if-let [group (first groups)]
                                                (let [found (assoc found group
                                                                   (dwts/theme-group-effect tokens-status tokens-lib
                                                                                            objects roots group))]
                                                  (vreset! timer (js/setTimeout #(step (rest groups) found) 0)))
                                                ;; Only a different result renders again
                                                (when (not= found @effects*)
                                                  (reset! effects* found)))))]
                            (vreset! timer (js/setTimeout #(step shown {}) 50))
                            #(do (vreset! cancelled true)
                                 (js/clearTimeout @timer)))))]

    (when displayed?
      [:section {:class (stl/css :element-set)
                 :aria-label (tr "workspace.options.token-context.title")}
       [:> title-bar* {:collapsable false
                       :title (tr "workspace.options.token-context.title")}
        [:div {:class (stl/css :title-actions)}
         (when (seq other-groups)
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "workspace.options.token-context.add-group"
                                             (count other-groups))
                             :aria-haspopup "menu"
                             :aria-expanded (= menu :add)
                             :on-click on-open-add
                             :icon i/add}])
         (when has-context?
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "workspace.options.token-context.options")
                             :aria-haspopup "menu"
                             :aria-expanded (= menu :options)
                             :on-click on-open-options
                             :icon i/menu}])]]

       ;; The other groups in a menu, not a list: a design system can
       ;; have dozens of them
       (when (= menu :add)
         [:& dropdown {:show true :on-close on-close-menu}
          [:ul {:class (stl/css :menu) :role "menu"}
           (for [group other-groups]
             (let [;; Added on Auto, to choose its theme in its row
                   on-add  (fn []
                             (on-close-menu)
                             (add-group! group))
                   ;; Its themes beside it, to add it with one directly
                   on-open (fn [event]
                             (let [rect (.getBoundingClientRect (.-currentTarget event))]
                               (reset! submenu* {:group group
                                                 :top (.-top rect)
                                                 :right (- (.-innerWidth js/window) (.-left rect))})))]
               [:li {:key group
                     :class (stl/css-case :menu-item true
                                          :menu-item-open (= group (:group submenu)))
                     :role "menuitem"
                     :aria-haspopup "menu"
                     :aria-expanded (= group (:group submenu))
                     :tab-index 0
                     :on-click on-add
                     :on-mouse-enter on-open
                     :on-focus on-open
                     :on-key-down (on-menu-key-down on-add)}
                [:span {:class (stl/css :menu-item-label)} group]
                [:> i/icon* {:icon-id i/arrow-left :size "s"}]]))]
          (when-let [{:keys [group top right]} submenu]
            [:ul {:class (stl/css :submenu)
                  :role "menu"
                  :aria-label group
                  :style {:top top :right right}}
             (for [theme (dwts/group-themes tokens-lib group)]
               (let [on-choose (fn []
                                 (on-close-menu)
                                 (add-group! group)
                                 (on-select group (:id theme)))]
                 [:li {:key (str (:id theme))
                       :class (stl/css :menu-item)
                       :role "menuitem"
                       :tab-index 0
                       :on-click on-choose
                       :on-key-down (on-menu-key-down on-choose)}
                  [:span {:class (stl/css :menu-item-label)} (:name theme)]]))])])

       (when (= menu :options)
         [:& dropdown {:show true :on-close on-close-menu}
          [:ul {:class (stl/css :menu) :role "menu"}
           [:li {:class (stl/css :menu-item)
                 :role "menuitemcheckbox"
                 :aria-checked (not isolated?)
                 :tab-index 0
                 :on-click on-toggle-inherit
                 :on-key-down (on-menu-key-down on-toggle-inherit)}
            [:span {:class (stl/css :menu-item-label)}
             (tr "workspace.options.token-context.inherit")]
            [:> i/icon* {:icon-id i/tick
                         :size "s"
                         :class (stl/css-case :menu-check true
                                              :menu-check-visible (not isolated?))}]]
           [:li {:class (stl/css :menu-item)
                 :role "menuitem"
                 :tab-index 0
                 :on-click on-remove'
                 :on-key-down (on-menu-key-down on-remove')}
            [:span {:class (stl/css :menu-item-label)}
             (tr "workspace.options.token-context.remove")]]]])

       [:div {:class (stl/css :element-content)}
        ;; Visible without opening the options menu, where it is toggled
        (when isolated?
          [:p {:class (stl/css :hint)}
           (tr "workspace.options.token-context.isolated-hint")])

        (for [group shown]
          [:> group-row* {:key group
                          :tokens-lib tokens-lib
                          :group group
                          :scopes scopes
                          :inherited (when-not isolated? (get inherited group))
                          :objects objects
                          :effect (get effects group)
                          :on-select on-select}])]])))

(mf/defc shapes-token-context*
  {::mf/private true}
  [{:keys [shapes placement]}]
  (let [page       (mf/deref refs/workspace-page)
        objects    (mf/deref refs/workspace-page-objects)
        tokens-lib (mf/deref refs/tokens-lib)
        ;; Followed, so that a change in a library refreshes `main-scopes`
        libraries  (mf/deref refs/libraries)

        ;; By value: the parent gives new shapes on each render
        holders    (hooks/use-equal-memo (filterv dwts/scope-holder? shapes))
        all?       (= (count holders) (count shapes))
        ids        (mf/with-memo [holders] (mapv :id holders))
        ids-key    (str ids)

        token-names
        (mf/with-memo [objects ids]
          (dwts/applied-token-names objects ids))

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
        (mf/with-memo [objects holders libraries]
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

    (when (and (seq holders) all?)
      [:> token-context-menu* {:key ids-key
                               :roots roots
                               :placement placement
                               :selection-key ids-key
                               :main-scopes main-scopes
                               :direct-groups direct-groups
                               :token-names token-names
                               :on-update on-update
                               :on-remove on-remove}])))

(mf/defc shapes-token-context-menu*
  "The token context section of the selected boards, groups, booleans
  and component copies; nothing when another kind of shape is selected,
  or without the feature. `placement` as in `token-context-menu*`."
  [{:keys [shapes placement]}]
  (when (contains? cf/flags :token-scoped-themes)
    [:> shapes-token-context* {:shapes shapes :placement placement}]))

(mf/defc page-token-context*
  {::mf/private true}
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
                             :token-names token-names
                             :direct-groups direct-groups
                             :on-update on-update
                             :on-remove on-remove}]))

(mf/defc page-token-context-menu*
  "The token context section of the current page, nothing without the
  feature. `placement` as in `token-context-menu*`."
  [{:keys [placement]}]
  (when (contains? cf/flags :token-scoped-themes)
    [:> page-token-context* {:placement placement}]))
