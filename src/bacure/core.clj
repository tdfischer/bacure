(ns bacure.core
  (:require [bacure.network :as network]
            [bacure.coerce :as coerce]
            [bacure.local-save :as save]))

(import '(com.serotonin.bacnet4j 
          LocalDevice 
          RemoteDevice
          obj.BACnetObject
          service.confirmed.CreateObjectRequest
          service.confirmed.DeleteObjectRequest
          service.unconfirmed.WhoIsRequest
          type.enumerated.PropertyIdentifier
          type.primitive.UnsignedInteger))

(defmacro mapify
  "Given some symbols, construct a map with the symbols as keys, and
  the value of the symbols as the map values. For example:
 (Let [aa 12]
     (mapify aa))
 => {:aa 12}"
  [& symbols]
  `(into {}
         (filter second
                 ~(into []
                        (for [item symbols]
                          [(keyword item) item])))))


(defn where
  "Use with `filter'"
  [criteria]
    (fn [m]
      (every? (fn [[k v]] (= (k m) v)) criteria)))

;; ================


(def local-device (atom nil))
(def local-device-configs (atom {}))

(defn new-local-device
  "Return a new configured BACnet local device . (A device is required
to communicate over the BACnet network.). To terminate it, use the
java method `terminate'. If needed, the initial configurations are
available in the atom 'local-device-configs."
  ([] (new-local-device nil))
  ([configs-map]
     (let [{:keys [device-id broadcast-address port destination-port local-address timeout]
            :or {device-id 1338 ;;some default configs
                 broadcast-address (network/get-broadcast-address (network/get-ip))
                 port 47808
                 destination-port 47808
                 timeout 10000}}
           configs-map
           ld (LocalDevice. device-id broadcast-address local-address)]
       (.setPort ld port)
       (.setTimeout ld timeout) ;; increase the default to 20s
       (reset! local-device ld)
       (reset! local-device-configs (mapify device-id broadcast-address port
                                            destination-port local-address timeout))
       ld)))



(defn initialize
  "Initialize the local device. This will bind it to it's port (most
  likely 47808). The port will remain unavailable until the device is
  terminated. Once terminated, you should discard the device and
  create a new one if needed."[]
  (.initialize @local-device))

(defn terminate
  "Terminate the local device, freeing any bound port in the process."[]
  (try (.terminate @local-device)
       (catch Exception e))) ;if the device isn't initialized, it will throw an error

(defn find-remote-devices
  "We find remote devices by sending a 'WhoIs' broadcast. Every device
  that responds is added to the remote-devices field in the
  local-device. WARNING: This won't ask the device if it supports
  read-property-multiple. Thus, any property read based solely on this
  remote device discovery might fail. The use of
  `find-remote-devices-and-services-supported' is highly recommended, even if it
  might take a little longer to execute."
  [&[{:keys [min-range max-range dest-port] :or {dest-port (:destination-port @local-device-configs)}}]]
  (.sendBroadcast @local-device
                  dest-port (if (or min-range max-range)
                              (WhoIsRequest.
                               (UnsignedInteger. (or min-range 0))
                               (UnsignedInteger. (or max-range 4194304)))
                              (WhoIsRequest.))))


(defn local-objects
  "Return a list of local objects"[]
  (mapv coerce/bacnet->clojure
        (.getLocalObjects @local-device)))

(defn add-object
  "Add a local object and return it. You should probably just use `add-or-update-object'."
  [object-map]
  (let [object-id (coerce/c-object-identifier (:object-identifier object-map))]
    (try (.addObject @local-device
                     (BACnetObject. @local-device object-id))
         (catch Exception e (print (str "caught exception: " (.getMessage e)))))
    (.getObject @local-device object-id)))
  
(defn add-or-update-object
  "Update a local object properties. Create the object it if not
  already present. Will not try to modify any :object-type
  or :object-identifier." [object-map]
  (let [object-id (coerce/c-object-identifier (:object-identifier object-map))
        b-obj (or (.getObject @local-device object-id) (add-object object-map))]
    (doseq [prop (coerce/encode-properties object-map :object-identifier :object-type)]
      (.setProperty b-obj prop))
    (coerce/bacnet->clojure b-obj)))


(defn remove-object
  "Remove a local object"
  [object-map]
  (.removeObject @local-device (coerce/c-object-identifier (:object-identifier object-map))))

(defn remove-all-objects
  "Remove all local object"[]
  (doseq [o (local-objects)]
    (remove-object o)))

(defn local-device-backup
  "Spit all important information about the local device into a map." []
  (merge @local-device-configs
         (when @local-device
           {:objects (local-objects)
            :port (.getPort @local-device)
            :retries (.getRetries @local-device)
            :seg-timeout (.getSegTimeout @local-device)
            :seg-window (.getSegWindow @local-device)
            :timeout (.getTimeout @local-device)})))
;; eventually we should be able to add programs in the local device

(defn reset-local-device
  "Terminate the local device and discard it. Replace it with a new
  local device, and apply the same configurations as the previous one.
  If a map with new configurations is provided, it will be merged with
  the old config.

  (reset-local-device {:device-id 1112})
  ---> reset the device and change the device id."
  ([] (reset-local-device nil))
  ([new-config]
     (terminate)
     (new-local-device (merge (local-device-backup) new-config))
     (let [configs (local-device-backup)]
       (doto @local-device
         (.setRetries (:retries configs))
         (.setSegTimeout (:seg-timeout configs))
         (.setSegWindow (:seg-window configs))
         (.setTimeout (:timeout configs)))
       (initialize)
       (doseq [o (or (:objects configs) [])]
         (add-or-update-object o)))))

(defn clear-all!
  "Mostly a development function; Destroy all traces of a local-device."[]
  (terminate)
  (def local-device (atom nil))
  (def local-device-configs (atom {})))


(defn save-local-device-backup
  "Save the device backup on a local file and return the config map."[]
  (save/save-configs (local-device-backup)))
;; eventually it would be nice to implement the BACnet backup procedure.


(defn load-local-device-backup
  "Load the local-device backup file and reset it with this new configuration."
  []
  (reset-local-device (save/get-configs)))


(defn find-remote-devices-and-extended-information
  "Given a local device, sends a WhoIs. For every device discovered,
  get its extended information. Return the remote devices as a list."
  [&[{:keys [min-range max-range dest-port] :as args}]]
  (find-remote-devices args)
  (Thread/sleep 500) ;wait a little to insure we get the responses
  (let [rds (-> @local-device (.getRemoteDevices))]
    (doseq [remote-device rds]
      (-> @local-device (.getExtendedDeviceInformation remote-device)))
    (for [rd rds]
      (.getInstanceNumber rd))))


(defn boot-up
  "Create a local-device, load its config file, initialize it, and find the remote devices." []
  (load-local-device-backup)
  (future (dorun
           (->> (repeatedly 5 find-remote-devices-and-extended-information);;try up to 5 time
                (take-while empty?))))
  true) ; in another thread


(defn remote-devices
  "Return the list of the current remote devices. These devices must
  be in the local table. To scan a network, use
  `find-remote-devices-and-extended-information'." []
  (for [rd (seq (.getRemoteDevices @local-device))]
    (.getInstanceNumber rd)))

(defn rd
  "Get the remote device by its device-id"
  [device-id]
  (.getRemoteDevice @local-device device-id))

(defn remote-devices-and-names
  "Return a list of vector pair with the device-id and its name.
   -->  ([1234 \"SimpleServer\"])" []
  (for [d (remote-devices)]
    [d (.getName (rd d))]))

;;;;;;;;;;;;;;;

(defn create-remote-object
  "Send a 'create object request' to the remote device."
  [device-id object-map]
  (.send @local-device (rd device-id)
         (CreateObjectRequest. (coerce/c-object-identifier (:object-identifier object-map))
                               (coerce/encode-properties object-map :object-type :object-identifier
                                                         :object-list))))

(defn delete-remote-object
  "Send a 'delete object' request to a remote device."
  [device-id object-identifier]
  (.send @local-device (rd device-id)
         (DeleteObjectRequest. (coerce/c-object-identifier object-identifier))))
  

;; (defn get-device-id
;;   "Find the device id when given a list of object-map"[objects-map]
;;   (-> (filter (where {:object-identifier :device}) objects-map)
;;       first
;;       :instance))

(defn object-properties-values
  "Query a remote device and return the properties values
   Example: (object-properties-values 1234 [:analog-input 0] :all)
   -> {:notification-class 4194303, :event-enable .....}"
  [device-id object-identifier & properties]
  (let [oid (coerce/c-object-identifier object-identifier)
        refs (com.serotonin.bacnet4j.util.PropertyReferences.)
        prop-identifiers (map #(coerce/make-property-identifier %) properties)]
    (doseq [prop-id prop-identifiers]
      (.add refs oid prop-id))
    (coerce/bacnet->clojure (.readProperties @local-device (rd device-id) refs))))

(defn remote-objects
  "Return a map of every objects in the remote device.
   -> [[:device 1234] [:analog-input 0]...]"
  [device-id]
  (-> (object-properties-values device-id [:device device-id] :object-list)
      :object-list))

(defn remote-objects-full-properties
  "Return a list of maps of every objects and their properties."
  [device-id]
  (map #(object-properties-values device-id % :all) (remote-objects device-id)))

(defn set-remote-properties
  "Set all the given objects properties in the remote device."
  [device-id properties-map]
  (let [remote-device (rd device-id)
        encoded-properties (coerce/encode properties-map)]
    (doseq [prop (dissoc encoded-properties :object-type :object-identifier :object-list)]
      (.setProperty @local-device remote-device
                    (:object-identifier encoded-properties)
                    (coerce/make-property-identifier (key prop))
                    (val prop)))))