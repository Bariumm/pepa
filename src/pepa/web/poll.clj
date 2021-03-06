(ns pepa.web.poll
  (:require [pepa.model :as m]
            [pepa.db :as db]
            [pepa.bus :as bus]
            [pepa.log :as log]

            [clojure.data.json :as json]
            [cognitect.transit :as transit]

            [ring.util.response :as response]
            [immutant.web.async :as async-web]
            [clojure.core.async :as async :refer [go go-loop <!]]
            [clojure.string :as s])
  (:import java.io.ByteArrayOutputStream))

(def +encoding+ "utf-8")

(defn ^:private data->response-fn [content-type]
  (case content-type
    "application/json"
    json/write-str

    "application/transit+json"
    (let [out (ByteArrayOutputStream.)
          writer (transit/writer out :json)]
      (fn [data]
        (.reset out)
        (transit/write writer data)
        (.toString out (s/upper-case +encoding+))))))

(defn ^:private send-fn [content-type]
  (let [data->response (data->response-fn content-type)]
    (fn send! [ch data]
      (async-web/send! ch
                       (-> data (data->response) (str \newline))
                       {:close? true}))))

(defn ^:private send-seqs! [db ch content-type]
  ((send-fn content-type) ch {:seqs (m/sequence-numbers db)}))

(defn ^:private lock-all! [db topic]
  (db/with-transaction [db db]
    (doseq [lock db/advisory-locks]
      (db/advisory-xact-lock! db lock))))

(defn ^:private handle-poll! [web config ch seqs content-type]
  (let [{:keys [db bus]} web
        send! (send-fn content-type)
        timeout (async/timeout (* 1000 (:timeout config)))
        bus-changes (bus/subscribe-all bus (async/sliding-buffer 1))]
    (log/debug "Starting poll with remote" (:remote-addr (async-web/originating-request ch)))
    (go-loop []
      (if-let [changed (m/changed-entities db seqs)]
        (do
          (log/debug "Got changes from DB:" (pr-str changed))
          (send! ch changed))
        ;; NOTE: We have to manually close the channels after a timeout,
        ;; else they stay open for forever & hog memory!
        (let [[val port] (async/alts! [timeout bus-changes])]
          (cond
            ;; Something changed
            (and val (= port bus-changes))
            (let [topic (bus/topic val)]
              (log/debug "lock-all!")
              (lock-all! db topic)
              (log/debug "lock-all! finished")
              ;; Recur to trigger the then-part of the if.
              (recur))
            ;; Hit a timeout or channel is closed
            (or (= port timeout) (not (async-web/open? ch)))
            (when (async-web/open? ch)
              (log/debug "Closing poll channel to remote" (:remote-addr (async-web/originating-request ch)))
              (async-web/close ch))))))))

(defn ^:private poll-handler* [req]
  (let [method (:request-method req)
        allowed-methods #{:get :post}
        content-type (some #(re-find % (:content-type req))
                           [#"^application/transit\+json"
                            #"^application/json"])
        seqs (:body req)

        poll-config (get-in req [:pepa/config :web :poll])
        db (:pepa/db req)
        bus (:pepa/bus req)
        web (:pepa/web req)
        handle-poll! (partial handle-poll! web poll-config)]
    (cond
      (not content-type)
      {:status 406}

      (not (contains? allowed-methods method))
      {:status 405}

      (and (seq seqs) (not (m/valid-seqs? seqs)))
      {:status 400}

      true
      (-> req
          (async-web/as-channel
           {:on-open (fn [ch]
                       (if (empty? seqs)
                         (send-seqs! db ch content-type)
                         (handle-poll! ch seqs content-type)))
            :on-error (fn [ch throwable]
                        (log/error "Caught exception:" throwable)
                        (async-web/close ch))})
          (response/content-type content-type)
          (response/charset +encoding+)))))


(def poll-handler #'poll-handler*)
