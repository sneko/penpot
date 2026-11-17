;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.viewport.token-scopes
  "Outlines of the token scopes on the canvas: a selected shape with a
  `:token-scope` gets an outline and a badge with its themes. Clicking a
  badge opens a menu to toggle the themes of the scope or remove it.
  Like Figma, which shows no mode on the canvas, the shapes that are
  not selected show nothing: a design system sets contexts on most of
  its components."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.workspace.tokens.scopes :as dwts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.hooks :as hooks]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn visible?
  "Whether the scope outlines show: with the flag, whatever the sidebars
  show."
  []
  (contains? cf/flags :token-scoped-themes))

;; Width of a badge's box in screen pixels; the label is cut to it.
(def ^:private badge-width 400)

;; Distance in screen pixels from a shape's bottom edge to its badge. A
;; selected shape has its size label there (8px gap, 16px high), so its
;; badge goes below that.
(def ^:private badge-gap 8)
(def ^:private badge-gap-selected 30)

(mf/defc scope-badge*
  "A badge centered on the canvas point `x`, `y`, at the same size on
  screen whatever the zoom. Clicking it opens a menu with every theme as
  \"Group / Theme\", checked when it is in `themes`; picking one calls
  `on-toggle` with it and whether it was checked. The last entry calls
  `on-remove`."
  [{:keys [x y zoom label tokens-lib themes on-toggle on-remove]}]
  (let [menu*     (mf/use-state nil)
        menu      (deref menu*)
        container (hooks/use-portal-container :popup)

        on-pointer-down
        (mf/use-fn
         (fn [event]
           ;; Keep the viewport from starting a selection or a drag
           (dom/prevent-default event)
           (dom/stop-propagation event)))

        on-click
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (let [rect (dom/get-bounding-rect (dom/get-current-target event))]
             (reset! menu* {:top (+ (:bottom rect) 4)
                            :left (:left rect)}))))

        on-close
        (mf/use-fn #(reset! menu* nil))

        on-remove'
        (mf/use-fn
         (mf/deps on-remove)
         (fn [event]
           (dom/stop-propagation event)
           (reset! menu* nil)
           (on-remove)))

        on-toggle'
        (fn [theme checked?]
          (fn [event]
            (dom/stop-propagation event)
            (reset! menu* nil)
            (on-toggle theme checked?)))]

    [:g {:transform (dm/fmt "translate(%, %) scale(%)" x y (/ 1 zoom))}
     [:foreignObject {:x (- (/ badge-width 2))
                      :width badge-width
                      :height 24
                      :class (stl/css :badge-wrapper)}
      [:button {:class (stl/css-case :badge true
                                     :badge-open (some? menu))
                :on-pointer-down on-pointer-down
                :on-click on-click}
       label]]

     (when menu
       (mf/portal
        (mf/html
         [:div {:class (stl/css :menu-portal)
                :style {:top (:top menu) :left (:left menu)}}
          [:& dropdown {:show true :on-close on-close}
           [:ul {:class (stl/css :menu) :role "menu"}
            (for [[_ group-themes] (ctob/get-theme-tree-no-hidden tokens-lib)
                  [_ theme]        group-themes
                  :let [checked? (contains? themes (:id theme))]]
              [:li {:key (dm/str (:id theme))
                    :class (stl/css :menu-item)
                    :role "menuitemcheckbox"
                    :aria-checked checked?
                    :on-click (on-toggle' theme checked?)}
               [:span {:class (stl/css :menu-item-label)} (dwts/theme-path theme)]
               [:> icon* {:icon-id i/tick
                          :size "s"
                          :class (stl/css-case :menu-check true
                                               :menu-check-visible checked?)}]])
            [:li {:class (stl/css :menu-separator)}]
            [:li {:class (stl/css :menu-item)
                  :role "menuitem"
                  :on-click on-remove'}
             (tr "workspace.token-scope-area.remove")]]]])
        container))]))

(mf/defc shape-scope-area*
  [{:keys [shape zoom label tokens-lib is-selected]}]
  (let [shape-id  (:id shape)
        {:keys [x y width height]} (dm/get-prop shape :selrect)

        on-toggle
        (mf/use-fn
         (mf/deps shape-id tokens-lib)
         (fn [theme checked?]
           (st/emit! (dwts/update-token-scopes
                      [shape-id]
                      #(dwts/set-group-theme % tokens-lib (:group theme)
                                             (when-not checked? (:id theme)))))))

        on-remove (mf/use-fn
                   (mf/deps shape-id)
                   #(st/emit! (dwts/set-shape-token-scope shape-id nil)))]
    [:g {:transform (gsh/transform-str shape)}
     [:rect {:class (stl/css :outline)
             :x x
             :y y
             :width width
             :height height}]
     [:> scope-badge* {:x (+ x (/ width 2))
                       :y (+ y height (/ (if is-selected badge-gap-selected badge-gap) zoom))
                       :zoom zoom
                       :label label
                       :tokens-lib tokens-lib
                       :themes (get-in shape [:token-scope :themes])
                       :on-toggle on-toggle
                       :on-remove on-remove}]]))

(mf/defc token-scope-areas*
  {::mf/wrap [mf/memo]}
  [{:keys [objects selected zoom]}]
  (let [tokens-lib (mf/deref refs/tokens-lib)
        shapes     (mf/with-memo [objects selected]
                     (into []
                           (comp (keep #(get objects %))
                                 (filter (fn [shape]
                                           (and (some? (:token-scope shape))
                                                (not= (:id shape) uuid/zero)
                                                (not (:hidden shape))))))
                           selected))]
    (when tokens-lib
      [:g {:class (stl/css :token-scope-areas)}
       (for [shape shapes]
         [:> shape-scope-area* {:key (dm/str "token-scope-" (:id shape))
                                :shape shape
                                :zoom zoom
                                :tokens-lib tokens-lib
                                :is-selected (contains? selected (:id shape))
                                :label (dwts/scope-label tokens-lib (:token-scope shape))}])])))
