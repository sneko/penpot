;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-wasm-text-test
  (:require
   [app.common.geom.rect :as grc]
   [app.common.types.shape :as cts]
   [app.main.data.workspace.wasm-text :as dwwt]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.wasm :as thw]))

(t/use-fixtures :each
  {:before thw/setup-wasm-mocks!
   :after  thw/teardown-wasm-mocks!})

(t/deftest resize-modifiers-of-a-zero-width-text
  (t/testing "An auto-width text without width (an empty text imported as is) is not scaled on that axis"
    ;; The mocked WASM measures any text as 100x20. `make-rect` gives at
    ;; least 0.01, an imported file can store a zero width
    (let [shape  (-> (cts/setup-shape {:type :text :x 0 :y 0 :width 10 :height 10 :grow-type :auto-width})
                     (assoc :selrect (assoc (grc/make-rect 0 0 10 10) :width 0 :x2 0)))
          result (dwwt/resize-wasm-text-modifiers shape (:content shape))]
      (t/is (some? (get-in result [(:id shape) :modifiers]))))))
