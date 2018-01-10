(ns bacure.services
  (:require [bacure.coerce :as c]
            [bacure.local-device :as ld]
            [bacure.state :as state]
            [bacure.events :as events]
            [bacure.coerce.service.confirmed]
            [bacure.coerce.service.acknowledgement])
  (:import (com.serotonin.bacnet4j ResponseConsumer)
           (com.serotonin.bacnet4j.service.unconfirmed WhoIsRequest
                                                       WhoHasRequest
                                                       WhoHasRequest$Limits)
           (com.serotonin.bacnet4j.service.confirmed SubscribeCOVRequest)))

;;; bacnet4j introduced some kind of callbacks with the
;;; request-sending mechanism. For simplicity sake, we use promises to
;;; reconvert all this into normal synchronous operations; The
;;; operations will block until the remote devices answer the
;;; requests. The user can use parallel functions like `pmap' if he
;;; wants to send multiple requests at the same time.
(defn- make-response-consumer [return-promise]
  (reify ResponseConsumer
    (success [this acknowledgement-service]
      (deliver return-promise {:success (do (state/set-request-response! acknowledgement-service)
                                            (or (c/bacnet->clojure acknowledgement-service) true))}))
    (fail [this ack-APDU-error]
      (deliver return-promise (do
                                (state/set-request-response! ack-APDU-error)
                                (or (condp = (class ack-APDU-error)
                                      
                                      com.serotonin.bacnet4j.apdu.Abort
                                      {:abort (let [reason (.getAbortReason ack-APDU-error)]
                                                {:abort-reason (->> reason
                                                                    (c/clojure->bacnet :abort-reason)
                                                                    (c/bacnet->clojure))})}
                                      
                                      com.serotonin.bacnet4j.apdu.Reject
                                      {:reject (let [reason (.getRejectReason ack-APDU-error)]
                                                 {:reject-reason (c/bacnet->clojure reason)})}
                                      
                                      com.serotonin.bacnet4j.apdu.Error
                                      {:error (some-> (.getError ack-APDU-error)
                                                      (c/bacnet->clojure))})))))
    (ex [this bacnet-exception]
      (deliver return-promise {:timeout {:timeout-error bacnet-exception}}))))

(defn send-request-promise
  "Send the request to the remote device.
  The possible return values are :

  {:success <expected valuezs - if any>
   :error {:error-class ..., :error-code ...}}

  Will block until the remote device answers."
  ([device-id request] (send-request-promise nil device-id request))
  ([local-device-id device-id request]
   (let [local-device (ld/local-device-object local-device-id)
         return-promise (promise)
         timeout (:apdu-timeout (ld/get-configs local-device-id))
         bacnet4j-future (if (.isInitialized local-device)
                           (.send local-device
                                  (some-> (events/cached-remote-devices local-device-id)
                                          (get device-id))
                                  request
                                  (make-response-consumer return-promise))
                           (throw (Exception. "Can't send request while the device isn't initialized.")))]
     ;; bacnet4j seems a little icky when dealing with timeouts...
     ;; better handling it ourself.
     ;; (future (do (Thread/sleep (+ timeout 1000))
     ;;             (deliver return-promise {:timeout "The request timed out. The remote device might not be on the network anymore."})))
     @return-promise)))

(defn send-who-is
  [local-device-id {:keys [min-range max-range]
                    :as   args}]

  (doto (ld/local-device-object local-device-id)
    (.sendGlobalBroadcast (if (or min-range max-range)
                            (WhoIsRequest.
                             (c/clojure->bacnet :unsigned-integer (or min-range 0))
                             (c/clojure->bacnet :unsigned-integer (or max-range 4194304)))
                            (WhoIsRequest.)))))

(defn send-who-has
  [local-device-id object-identifier-or-name
   {:keys [min-range max-range] :or {min-range 0 max-range 4194303}
    :as   args}]

  (let [local-device   (ld/local-device-object local-device-id)
        min-range      (c/clojure->bacnet :unsigned-integer min-range)
        max-range      (c/clojure->bacnet :unsigned-integer max-range)
        object-id-type (if (string? object-identifier-or-name)
                         :character-string
                         :object-identifier)

        object-identifier-or-name (c/clojure->bacnet object-id-type
                                                     object-identifier-or-name)

        limits  (WhoHasRequest$Limits. min-range max-range)
        request (WhoHasRequest. limits object-identifier-or-name)]

    (doto local-device (.sendGlobalBroadcast request))))

(defn send-subscribe-cov
  [local-device-id remote-device-id object-identifier
   {:keys [process-identifier confirmed? lifetime-seconds]
    :or   {process-identifier events/default-cov-process-id
           confirmed?         false
           lifetime-seconds   60}
    :as   args}]

  (let [process-identifier (c/clojure->bacnet :unsigned-integer process-identifier)
        object-identifier  (c/clojure->bacnet :object-identifier object-identifier)
        confirmed?         (c/clojure->bacnet :boolean confirmed?)
        lifetime-seconds   (c/clojure->bacnet :unsigned-integer lifetime-seconds)

        request (SubscribeCOVRequest. process-identifier object-identifier confirmed?
                                      lifetime-seconds)]

    (send-request-promise local-device-id remote-device-id request)))

(defn send-who-is-router-to-network
  [local-device-id]
  (let [ldo (ld/local-device-object local-device-id)
        n (.getNetwork ldo)
        b-add (.getLocalBroadcastAddress n)]
    (.sendNetworkMessage n b-add nil 0 nil true false)))
