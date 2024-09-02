;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.middleware
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.common.types.shape :as cts]
   [app.common.geom.rect :as ctr]
   [app.common.geom.matrix :as ctm]
   [app.common.geom.point :as ctp]
   [app.common.uuid :as ctu]
   [app.config :as cf]
   [clojure.data.json :as json]
   [cuerdas.core :as str]
   [ring.request :as rreq]
   [ring.response :as rres]
   [yetti.adapter :as yt]
   [yetti.middleware :as ymw])
  (:import
   io.undertow.server.RequestTooBigException
   java.io.InputStream
   java.io.OutputStream))

(set! *warn-on-reflection* true)

(def server-timing
  {:name ::server-timing
   :compile (constantly ymw/wrap-server-timing)})

(def params
  {:name ::params
   :compile (constantly ymw/wrap-params)})

(defn- get-reader
  ^java.io.BufferedReader
  [request]
  (let [^InputStream body (rreq/body request)]
    (java.io.BufferedReader.
     (java.io.InputStreamReader. body))))

(defn- read-json-key
  [k]
  (-> k str/kebab keyword))

(defn- read-json-value
  [k, v]
  (cond
    (and (= k :type) (not (#{ "paragraph" "paragraph-set" "root" } v))) (-> v keyword)
    (#{:attr :constraints-h :constraints-v :blend-mode :stroke-style :stroke-alignment :style :layout :layout-padding-type :layout-justify-content :layout-justify-items :layout-align-content :layout-align-items :layout-flex-dir :layout-wrap-type :layout-item-h-sizing :layout-item-v-sizing :layout-item-align-self :grow-type :command :bool-type} k) (-> v keyword)
    (#{:id :old-id :shape-ref :fill-color-ref-id :fill-color-ref-file :stroke-color-ref-id :stroke-color-ref-file :typography-ref-id :typography-ref-file :component-id :component-file :main-instance-page :main-instance-id} k) (-> v ctu/uuid)
    (= k :points) (if (coll? v)
                    (vec (map ctp/map->Point v))
                    (vec [(ctp/map->Point v)]))
    (= k :touched) (if (coll? v)
                    (set (map keyword v))
                    nil)
    (= k :selrect) (-> v ctr/map->Rect)
    (= k :transform) (-> v ctm/map->Matrix)
    (= k :transform-inverse) (-> v ctm/map->Matrix)
    (= k :obj) (-> v cts/map->Shape)
    (= k :operations) (vec (map (fn [item]
                                   (cond
                                     (= (:attr item) :points) (update item :val (fn [points]
                                                                                  (if (coll? points)
                                                                                    (vec (map ctp/map->Point points))
                                                                                    (vec [(ctp/map->Point points)]))))
                                     (= (:attr item) :selrect) (update item :val ctr/map->Rect)
                                     (= (:attr item) :transform) (update item :val ctm/map->Matrix)
                                     (= (:attr item) :transform-inverse) (update item :val ctm/map->Matrix)
                                     (= (:attr item) :shapes) (update item :val (fn [shapes]
                                                                                  (if (coll? shapes)
                                                                                    (vec (map ctu/uuid shapes))
                                                                                    (vec [(ctu/uuid shapes)]))))
                                     (= (:attr item) :touched) (update item :val (fn [touched]
                                                                                  (if (coll? touched)
                                                                                    (set (map keyword touched))
                                                                                    nil)))
                                     (#{:parent-id :frame-id :id :shape-ref :fill-color-ref-id :fill-color-ref-file :stroke-color-ref-id :stroke-color-ref-file :typography-ref-id :typography-ref-file :component-id :component-file :main-instance-page :main-instance-id} (:attr item)) (update item :val ctu/uuid)
                                     (#{:type :constraints-h :constraints-v :blend-mode :stroke-style :stroke-alignment :style :layout :layout-padding-type :layout-justify-content :layout-justify-items :layout-align-content :layout-align-items :layout-flex-dir :layout-wrap-type :layout-item-h-sizing :layout-item-v-sizing :layout-item-align-self :grow-type :command :bool-type} (:attr item)) (update item :val keyword)
                                     :else item))
                                 v))
    (= k :shapes) (vec (map ctu/uuid v))
    :else v))

(defn- write-json-key
  [k]
  (if (or (keyword? k) (symbol? k))
    (str/camel k)
    (str k)))

(defn wrap-parse-request
  [handler]
  (letfn [(process-request [request]
            (let [header (rreq/get-header request "content-type")]
              (cond
                (str/starts-with? header "application/transit+json")
                (with-open [^InputStream is (rreq/body request)]
                  (let [params (t/read! (t/reader is))]
                    (-> request
                        (assoc :body-params params)
                        (update :params merge params))))

                (str/starts-with? header "application/json")
                (with-open [reader (get-reader request)]
                  (let [params (json/read reader :key-fn read-json-key :value-fn read-json-value)]
                    (-> request
                        (assoc :body-params params)
                        (update :params merge params))))

                :else
                request)))

          (handle-error [cause]
            (cond
              (instance? RuntimeException cause)
              (if-let [cause (ex-cause cause)]
                (handle-error cause)
                (throw cause))

              (instance? RequestTooBigException cause)
              (ex/raise :type :validation
                        :code :request-body-too-large
                        :hint (ex-message cause))

              (instance? java.io.EOFException cause)
              (ex/raise :type :validation
                        :code :malformed-json
                        :hint (ex-message cause)
                        :cause cause)

              :else
              (throw cause)))]

    (fn [request]
      (if (= (rreq/method request) :post)
        (let [request (ex/try! (process-request request))]
          (if (ex/exception? request)
            (handle-error request)
            (handler request)))
        (handler request)))))

(def parse-request
  {:name ::parse-request
   :compile (constantly wrap-parse-request)})

(defn buffered-output-stream
  "Returns a buffered output stream that ignores flush calls. This is
  needed because transit-java calls flush very aggresivelly on each
  object write."
  [^java.io.OutputStream os ^long chunk-size]
  (yetti.util.BufferedOutputStream. os (int chunk-size)))

(def ^:const buffer-size (:xnio/buffer-size yt/defaults))

(defn wrap-format-response
  [handler]
  (letfn [(transit-streamable-body [data opts]
            (reify rres/StreamableResponseBody
              (-write-body-to-stream [_ _ output-stream]
                (try
                  (with-open [^OutputStream bos (buffered-output-stream output-stream buffer-size)]
                    (let [tw (t/writer bos opts)]
                      (t/write! tw data)))
                  (catch java.io.IOException _)
                  (catch Throwable cause
                    (binding [l/*context* {:value data}]
                      (l/error :hint "unexpected error on encoding response"
                               :cause cause)))
                  (finally
                    (.close ^OutputStream output-stream))))))

          (json-streamable-body [data]
            (reify rres/StreamableResponseBody
              (-write-body-to-stream [_ _ output-stream]
                (try
                  (with-open [^OutputStream bos (buffered-output-stream output-stream buffer-size)]
                    (with-open [^java.io.OutputStreamWriter writer (java.io.OutputStreamWriter. bos)]
                      (json/write data writer :key-fn write-json-key)))

                  (catch java.io.IOException _)
                  (catch Throwable cause
                    (binding [l/*context* {:value data}]
                      (l/error :hint "unexpected error on encoding response"
                               :cause cause)))
                  (finally
                    (.close ^OutputStream output-stream))))))

          (format-response-with-json [response _]
            (let [body (::rres/body response)]
              (if (or (boolean? body) (coll? body))
                (-> response
                    (update ::rres/headers assoc "content-type" "application/json")
                    (assoc ::rres/body (json-streamable-body body)))
                response)))

          (format-response-with-transit [response request]
            (let [body (::rres/body response)]
              (if (or (boolean? body) (coll? body))
                (let [qs   (rreq/query request)
                      opts (if (or (contains? cf/flags :transit-readable-response)
                                   (str/includes? qs "transit_verbose"))
                             {:type :json-verbose}
                             {:type :json})]
                  (-> response
                      (update ::rres/headers assoc "content-type" "application/transit+json")
                      (assoc ::rres/body (transit-streamable-body body opts))))
                response)))

          (format-from-params [{:keys [query-params] :as request}]
            (and (= "json" (get query-params :_fmt))
                 "application/json"))

          (format-response [response request]
            (let [accept (or (format-from-params request)
                             (rreq/get-header request "accept"))]
              (cond
                (or (= accept "application/transit+json")
                    (str/includes? accept "application/transit+json"))
                (format-response-with-transit response request)

                (or (= accept "application/json")
                    (str/includes? accept "application/json"))
                (format-response-with-json response request)

                :else
                (format-response-with-transit response request))))

          (process-response [response request]
            (cond-> response
              (map? response) (format-response request)))]

    (fn [request]
      (let [response (handler request)]
        (process-response response request)))))

(def format-response
  {:name ::format-response
   :compile (constantly wrap-format-response)})

(defn wrap-errors
  [handler on-error]
  (fn [request]
    (try
      (handler request)
      (catch Throwable cause
        (on-error cause request)))))

(def errors
  {:name ::errors
   :compile (constantly wrap-errors)})

(defn- with-cors-headers
  [headers origin]
  (-> headers
      (assoc "access-control-allow-origin" origin)
      (assoc "access-control-allow-methods" "GET,POST,DELETE,OPTIONS,PUT,HEAD,PATCH")
      (assoc "access-control-allow-credentials" "true")
      (assoc "access-control-expose-headers" "x-requested-with, content-type, cookie")
      (assoc "access-control-allow-headers" "x-frontend-version, content-type, accept, x-requested-width")))

(defn wrap-cors
  [handler]
  (fn [request]
    (let [response (if (= (rreq/method request) :options)
                     {::rres/status 200}
                     (handler request))
          origin   (rreq/get-header request "origin")]
      (update response ::rres/headers with-cors-headers origin))))

(def cors
  {:name ::cors
   :compile (fn [& _]
              (when (contains? cf/flags :cors)
                wrap-cors))})

(def restrict-methods
  {:name ::restrict-methods
   :compile
   (fn [data _]
     (when-let [allowed (:allowed-methods data)]
       (fn [handler]
         (fn [request]
           (let [method (rreq/method request)]
             (if (contains? allowed method)
               (handler request)
               {::rres/status 405}))))))})
