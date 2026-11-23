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
   [app.common.data :as d]
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
   [beicon.v2.core :as rx]
   [clojure.string :as cstr]
   [potok.v2.core :as ptk]))

;; A scope without themes: a context that inherits everything
(def ^:private empty-scope {:themes #{}})

(defn- update-scope
  "`f` of `scope` (empty when nil); still nil when there was none and `f`
  leaves it empty, so that choosing Auto does not create a context."
  [scope f]
  (let [scope' (f (or scope empty-scope))]
    (when-not (and (nil? scope) (= scope' empty-scope))
      scope')))

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

(defn unmasked-theme-groups
  "The theme groups defining directly a token applied to the shapes of
  `ids` or their children, without those that every such use already
  gets from a context set below the shapes: choosing them on the shapes
  would change nothing, the nearest context wins (an isolated one masks
  every group)."
  [objects ids tokens-lib]
  (let [scope-groups
        (fn [{:keys [themes mode]}]
          (if (= mode :isolated)
            ::all
            (into #{} (keep #(:group (ctob/get-theme tokens-lib %))) themes)))

        walk
        (fn walk [acc id masked root?]
          (let [shape  (get objects id)
                scope  (when-not root? (:token-scope shape))
                masked (cond
                         (or (= masked ::all) (nil? scope)) masked
                         :else (let [groups (scope-groups scope)]
                                 (if (= groups ::all) ::all (into masked groups))))
                acc    (if (= masked ::all)
                         acc
                         (into acc
                               (remove masked)
                               (cfo/direct-theme-groups tokens-lib
                                                        (set (vals (:applied-tokens shape))))))]
            (reduce #(walk %1 %2 masked false) acc (:shapes shape))))]
    (reduce #(walk %1 %2 #{} true) #{} ids)))

(defn theme-groups
  "The theme groups of `tokens-lib` that have themes, in library order."
  [tokens-lib]
  (into [] (remove #(empty? (ctob/get-themes-in-group tokens-lib %)))
        (ctob/get-theme-groups tokens-lib)))

(defn group-themes
  "The themes of `group` a context can choose (not the hidden one)."
  [tokens-lib group]
  (into [] (comp (keep #(ctob/get-theme tokens-lib %))
                 (remove ctob/hidden-theme?))
        (ctob/get-themes-in-group tokens-lib group)))

(defn switchable-group?
  "Whether choosing a theme of `group` can change anything: a group with
  a single theme has nothing to switch."
  [tokens-lib group]
  (> (count (ctob/get-themes-in-group tokens-lib group)) 1))

(defn toggle-isolated
  "The scope cut from the contexts above, or inheriting them again."
  [scope]
  (if (= :isolated (:mode scope))
    (dissoc scope :mode)
    (assoc scope :mode :isolated)))

(defn set-group-theme
  "The scope with the theme `theme-id` as the theme of `group`, or with
  no theme of that group when `theme-id` is nil."
  [scope tokens-lib group theme-id]
  (let [in-group? (fn [id] (= group (:group (ctob/get-theme tokens-lib id))))]
    (assoc scope :themes (cond-> (into #{} (remove in-group?) (:themes scope))
                           theme-id (conj theme-id)))))

;; --- Groups that change nothing

(def ^:private reference-re #"\{([^{}]+)\}")

(defn- resolve-value
  "`value` with its references replaced by the resolved values of the
  tokens `lookup` finds by name, recursively, as strings. Expressions
  stay unevaluated: two values equal once resolved are equal, the
  opposite may not hold, which only makes a group look effective."
  [lookup value depth]
  (cond
    (> depth 32)        value
    (string? value)     (if (and (cstr/starts-with? value "{")
                                 (cstr/ends-with? value "}")
                                 (= 1 (count (re-seq reference-re value))))
                          ;; A plain alias keeps the type of its target
                          (let [name (subs value 1 (dec (count value)))]
                            (if-let [token (lookup name)]
                              (resolve-value lookup (:value token) (inc depth))
                              value))
                          (cstr/replace value reference-re
                                        (fn [[match name]]
                                          (if-let [token (lookup name)]
                                            (str (resolve-value lookup (:value token) (inc depth)))
                                            match))))
    (map? value)        (update-vals value #(resolve-value lookup % (inc depth)))
    (sequential? value) (mapv #(resolve-value lookup % (inc depth)) value)
    :else               value))

(defn- subtree-token-uses
  "The token names applied in the subtree of `root-id`, by the chain of
  the contexts set below the root that their shapes resolve under (the
  root's own context aside)."
  [objects root-id]
  (let [walk (fn walk [acc id chain root?]
               (let [shape (get objects id)
                     scope (when-not root? (:token-scope shape))
                     chain (cond-> chain scope (conj scope))
                     names (vals (:applied-tokens shape))
                     acc   (cond-> acc
                             (seq names) (update chain (fnil into #{}) names))]
                 (reduce #(walk %1 %2 chain false) acc (:shapes shape))))]
    (walk {} root-id [] true)))

;; Results of `theme-group-effect` for one group, by tokens library,
;; tokens status, objects and roots (the same selection shown again)
(defonce ^:private effect-cache (atom {:key nil :by-group {}}))

(declare ineffective-theme-groups masked-group-tokens)

(defn theme-group-effect
  "What choosing a theme of `group` on the roots does: `:ineffective?`
  when it changes nothing (see `ineffective-theme-groups`), and
  `:masked`, the tokens used below that the group defines but another
  group gives the value of (see `masked-group-tokens`). Cached while the
  tokens, the page objects and the roots stay the same."
  [tokens-status tokens-lib objects roots group]
  (let [key   [tokens-status tokens-lib objects (mapv (juxt :id :scope :chain-above) roots)]
        ;; The tokens and page objects by identity (comparing them by value
        ;; would cost more), the roots by value (rebuilt on each render)
        same? (fn [other]
                (and other
                     (identical? (nth key 0) (nth other 0))
                     (identical? (nth key 1) (nth other 1))
                     (identical? (nth key 2) (nth other 2))
                     (= (nth key 3) (nth other 3))))
        cache @effect-cache]
    (if (and (same? (:key cache)) (contains? (:by-group cache) group))
      (get-in cache [:by-group group])
      (let [result {:ineffective? (contains? (ineffective-theme-groups tokens-status tokens-lib objects roots [group]) group)
                    :masked       (masked-group-tokens tokens-status tokens-lib objects roots group)}]
        (swap! effect-cache
               (fn [cache]
                 (if (same? (:key cache))
                   (assoc-in cache [:by-group group] result)
                   {:key key :by-group {group result}})))
        result))))

(defn- set-owners
  "The name to show for the owner of each set id: the group of the first
  theme using it (the theme itself for a theme without a group), or the
  set name when no theme uses it."
  [tokens-lib]
  (let [by-theme (into {}
                       (comp (remove ctob/hidden-theme?)
                             (mapcat (fn [theme]
                                       (let [owner (if (seq (:group theme)) (:group theme) (:name theme))]
                                         (keep #(some-> (ctob/get-set-by-name tokens-lib %)
                                                        ctob/get-id
                                                        (vector owner))
                                               (:sets theme))))))
                       (reverse (ctob/get-themes tokens-lib)))]
    (fn [set-id]
      (or (get by-theme set-id)
          (some-> (ctob/get-set tokens-lib set-id) ctob/get-name)))))

(defn masked-group-tokens
  "The tokens used below the roots that a theme of `group` defines, but
  whose value comes from a set of another group whichever theme the
  roots choose: a nearer context, or a set later in the list at the same
  level. A map of token name to the names of those groups (see
  `set-owners`)."
  [tokens-status tokens-lib objects roots group]
  (let [scoped-set-ids (cfo/make-scoped-set-ids tokens-status tokens-lib)
        set-tokens     (into {}
                             (map (fn [set] [(ctob/get-id set) (ctob/get-tokens- set)]))
                             (ctob/get-sets tokens-lib))
        owner          (set-owners tokens-lib)
        theme-sets     (fn [theme-id]
                         (into #{}
                               (keep #(some-> (ctob/get-set-by-name tokens-lib %) ctob/get-id))
                               (:sets (ctob/get-theme tokens-lib theme-id))))
        themes         (vec (ctob/get-themes-in-group tokens-lib group))
        ;; For one theme and chain, each name its sets define: who gives
        ;; its value, `::own` when they do
        winners        (fn [chain own names]
                         (let [ids (vec (rseq (scoped-set-ids chain)))]
                           (into {}
                                 (keep (fn [name]
                                         (when (some #(contains? (get set-tokens %) name) own)
                                           (let [winner (d/seek #(contains? (get set-tokens %) name) ids)]
                                             [name (if (contains? own winner) ::own (owner winner))]))))
                                 names)))]
    (->> (for [{:keys [id scope chain-above]} roots
               [below names] (subtree-token-uses objects id)
               theme-id themes
               :let [own (theme-sets theme-id)]
               :when (seq own)]
           (winners (-> (vec chain-above)
                        (conj (set-group-theme (or scope empty-scope) tokens-lib group theme-id))
                        (into below))
                    own names))
         (map #(update-vals % hash-set))
         (apply merge-with into {})
         ;; Masked whichever theme is chosen, and wherever it is used
         (into {} (remove (fn [[_ owners]] (contains? owners ::own)))))))

(defn ineffective-theme-groups
  "The groups of `groups` whose choice on the roots changes no value of
  the tokens used below them: every theme of the group, and Auto, gives
  the same resolved values, given the contexts above each root and
  those set below it (the nearest context wins). `roots` are maps of
  `:id`, `:scope` (its context, nil without) and `:chain-above` (the
  contexts above it, see `cfo/scope-chain`). Resolving costs one pass
  per distinct chain of contexts and theme, not per shape."
  [tokens-status tokens-lib objects roots groups]
  (let [fast-set-ids (cfo/make-scoped-set-ids tokens-status tokens-lib)
        ;; The tokens of each set by name, read once
        set-tokens (into {}
                         (map (fn [set] [(ctob/get-id set) (ctob/get-tokens- set)]))
                         (ctob/get-sets tokens-lib))
        set-ids*   (volatile! {})
        set-ids    (fn [chain]
                     (or (get @set-ids* chain)
                         (let [ids (vec (rseq (fast-set-ids chain)))]
                           (vswap! set-ids* assoc chain ids)
                           ids)))
        ;; Resolved values by sets and name: the variants of a group
        ;; often share their sets with another one
        resolved*  (volatile! {})
        resolve    (fn [ids name]
                     (let [key [ids name]]
                       (if-let [[_ v] (find @resolved* key)]
                         v
                         (let [lookup (fn [name] (some #(get (get set-tokens %) name) ids))
                               v      (resolve-value lookup (some-> (lookup name) :value) 0)]
                           (vswap! resolved* assoc key v)
                           v))))
        uses    (mapv (fn [{:keys [id] :as root}]
                        (assoc root :uses (subtree-token-uses objects id)))
                      roots)

        ineffective-for-root?
        (fn [group {:keys [scope chain-above uses]}]
          (let [scope    (or scope empty-scope)
                variants (cons nil (ctob/get-themes-in-group tokens-lib group))]
            (every? (fn [[below names]]
                      (->> variants
                           (map (fn [theme-id]
                                  (let [ids (set-ids (-> (vec chain-above)
                                                         (conj (set-group-theme scope tokens-lib group theme-id))
                                                         (into below)))]
                                    (mapv #(resolve ids %) names))))
                           (apply =)))
                    uses)))

        ;; A group reaching none of the tokens used, even through
        ;; references, changes nothing: no need to resolve
        reachable
        (cfo/relevant-theme-groups tokens-lib
                                   (into #{} (comp (mapcat (comp vals :uses)) cat) uses))]
    (into #{}
          (filter (fn [group]
                    (or (not (contains? reachable group))
                        (every? #(ineffective-for-root? group %) uses))))
          groups)))

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
            scope   (:token-scope page)
            scope'  (update-scope scope f)]
        (when (not= scope scope')
          (rx/of (dwtp/with-propagation
                   (dch/commit-changes
                    (-> (pcb/empty-changes it)
                        (pcb/with-page page)
                        (pcb/mod-page {:token-scope scope'})))
                   {:page-id (:id page)})))))))

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
        (rx/of
         (dwtp/with-propagation
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
           {:page-id page-id :root-ids ids}))))))

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
          (rx/of (set-scopes ids #(update-scope (:token-scope %) f))))))))

(defn main-token-scope
  "The scope of the main of `shape` when it is a component copy: the
  context its component is designed in, whose groups stay relevant to
  the copy even when it does not choose them anymore."
  [state shape]
  (when (ctk/in-component-copy? shape)
    (:token-scope (ctf/find-ref-shape (dsh/lookup-file state)
                                      (dsh/lookup-page state)
                                      (dsh/lookup-libraries state)
                                      shape))))

(defn follow-main-group
  "Sets `group` back to Auto on the shapes of `shape-ids`, and
  propagates. On a component copy, Auto follows its main: the copy takes
  the theme its main chooses for the group (none when the main inherits
  it), and no longer overrides it, so it follows the main's later
  changes too."
  [shape-ids group]
  (ptk/reify ::follow-main-group
    ptk/WatchEvent
    (watch [_ state _]
      (let [tokens-lib (dsh/lookup-tokens-lib state)
            ids        (holder-ids state shape-ids)
            follow     (fn [shape]
                         (if (ctk/in-component-copy? shape)
                           (let [main-scope (main-token-scope state shape)
                                 theme-id   (d/seek #(= group (:group (ctob/get-theme tokens-lib %)))
                                                    (:themes main-scope))]
                             (assoc shape :token-scope
                                    (-> (or (:token-scope shape) empty-scope)
                                        (set-group-theme tokens-lib group theme-id)
                                        (update :overrides (fnil disj #{}) group))))
                           (let [scope (update-scope (:token-scope shape)
                                                     #(set-group-theme % tokens-lib group nil))]
                             (if scope
                               (assoc shape :token-scope scope)
                               (dissoc shape :token-scope)))))]
        (when (and tokens-lib (seq ids))
          (rx/of
           (dwtp/with-propagation
             (dwsh/update-shapes ids follow)
             {:page-id (:current-page-id state) :root-ids ids})))))))

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
        (rx/of
         (dwtp/with-propagation
           (dwsh/update-shapes shape-ids
                               (fn [shape]
                                 (if-let [scope (main-scope shape)]
                                   (assoc shape :token-scope scope)
                                   (dissoc shape :token-scope))))
           {:page-id page-id :root-ids shape-ids}))))))
