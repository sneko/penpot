;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.tokens.scoped-resolution
  "Resolution of the tokens of a shape with the token scopes (the token
  contexts of the UI) of its page and ancestors, see `cfo/scope-chain`."
  (:require
   [app.common.files.tokens :as cfo]
   [app.config :as cf]
   [app.main.data.tokenscript :as ts]))

(defn enabled?
  "Whether shapes resolve their tokens with their token scopes. Scopes
  need tokenscript, which resolves the sets in the order they are given."
  []
  (and (contains? cf/flags :token-scoped-themes)
       (contains? cf/flags :tokenscript)))

;; The tokens resolved by ordered set ids, for one tokens library: resolving
;; all the sets is costly (every token of the file), and the chains of the
;; shapes of all the pages lead to a few distinct sets
(defonce ^:private resolved-cache (atom {:tokens-lib nil :by-set-ids {}}))

(defn- resolve-set-ids
  "The tokens resolved for the sets of `set-ids`, in that order."
  [tokens-lib set-ids]
  (let [cache @resolved-cache]
    (if-let [resolved (and (identical? tokens-lib (:tokens-lib cache))
                           (get-in cache [:by-set-ids set-ids]))]
      resolved
      (let [resolved (->> set-ids
                          (cfo/get-token-sets-in-order tokens-lib)
                          (ts/resolve-sets))]
        (swap! resolved-cache
               (fn [cache]
                 (if (identical? tokens-lib (:tokens-lib cache))
                   (assoc-in cache [:by-set-ids set-ids] resolved)
                   {:tokens-lib tokens-lib :by-set-ids {set-ids resolved}})))
        resolved))))

(defn make-resolver
  "A fn from a shape id of `page` (nil for the page itself) to the tokens
  resolved for it with its token scopes. `resolved-tokens` are the file's,
  given to the shapes no scope applies to. Resolves once per distinct
  chain of scopes. Without the flags every shape gets `resolved-tokens`."
  [tokens-status tokens-lib resolved-tokens page]
  (if (and (enabled?) tokens-status tokens-lib)
    (let [objects (:objects page)
          set-ids (cfo/make-scoped-set-ids tokens-status tokens-lib)
          cache   (volatile! {[] resolved-tokens})]
      (fn [shape-id]
        (let [chain (cfo/scope-chain page objects shape-id)
              key   (cfo/scope-chain-key chain)]
          (or (get @cache key)
              (let [resolved (resolve-set-ids tokens-lib (set-ids chain))]
                (vswap! cache assoc key resolved)
                resolved)))))
    (constantly resolved-tokens)))
