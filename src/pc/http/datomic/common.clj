(ns pc.http.datomic.common
  (:require [clojure.set :as set]
            [datomic.api :as d]
            [pc.datomic.schema :as schema]
            [pc.datomic.web-peer :as web-peer]))

;; TODO: is the transaction guaranteed to be the first? Can there be multiple?
(defn get-annotations [transaction]
  (let [txid (-> transaction :tx-data first :tx)]
    (d/entity (:db-after transaction) txid)))

(def read-only-outgoing-whitelist
  #{:layer/name
    :layer/uuid
    :layer/type
    :layer/start-x
    :layer/start-y
    :layer/end-x
    :layer/end-y
    :layer/rx
    :layer/ry
    :layer/fill
    :layer/stroke-width
    :layer/stroke-color
    :layer/opacity
    :layer/points-to
    :layer/font-family
    :layer/text
    :layer/font-size
    :layer/path
    :layer/child
    :layer/ui-id
    :layer/ui-target
    :layer/document

    :entity/type
    :session/uuid
    :document/uuid
    :document/name
    :document/creator
    :document/collaborators
    :document/privacy

    :chat/body
    :chat/color
    :chat/cust-name
    :chat/document
    :cust/uuid
    :cust/color-name
    :client/timestamp
    :server/timestamp})

(defn outgoing-whitelist [scope]
  (cond (= scope :read)
        read-only-outgoing-whitelist
        (= scope :admin)
        (set/union read-only-outgoing-whitelist
                   #{:permission/document
                     :permission/cust ;; translated
                     :permission/permits
                     :permission/grant-date
                     :permission/team

                     :access-grant/document
                     :access-grant/email
                     :access-grant/grant-date
                     :access-grant/team

                     :access-request/document
                     :access-request/cust ;; translated
                     :access-request/status
                     :access-request/create-date
                     :access-request/deny-date
                     :access-request/team

                     :team/plan

                     :plan/coupon-code
                     :plan/start
                     :plan/trial-end
                     :plan/credit-card
                     :plan/paid?
                     :plan/billing-email
                     :plan/active-custs

                     :discount/start
                     :discount/coupon
                     :discount/end

                     :credit-card/exp-year
                     :credit-card/exp-month
                     :credit-card/last4
                     :credit-card/brand
                     })))

(defn translate-datom-dispatch-fn [db d] (:a d))

(defmulti translate-datom translate-datom-dispatch-fn)

(defmethod translate-datom :default [db d]
  d)

(defmethod translate-datom :permission/cust-ref [db d]
  (-> d
    (assoc :a :permission/cust)
    (update-in [:v] #(:cust/email (d/entity db %)))))

(defmethod translate-datom :access-request/cust-ref [db d]
  (-> d
    (assoc :a :access-request/cust)
    (update-in [:v] #(:cust/email (d/entity db %)))))

(defmethod translate-datom :permission/document-ref [db d]
  (-> d
    (assoc :a :permission/document)))

(defmethod translate-datom :access-request/document-ref [db d]
  (-> d
    (assoc :a :access-request/document)))

(defmethod translate-datom :access-grant/document-ref [db d]
  (-> d
    (assoc :a :access-grant/document)))

(defmethod translate-datom :permission/team [db d]
  (-> d
    (assoc :v (:team/uuid (d/entity db (:v d))))))

(defmethod translate-datom :access-request/team [db d]
  (-> d
    (assoc :v (:team/uuid (d/entity db (:v d))))))

(defmethod translate-datom :access-grant/team [db d]
  (-> d
    (assoc :v (:team/uuid (d/entity db (:v d))))))

(defmethod translate-datom :layer/points-to [db d]
  (-> d
    (assoc :v (web-peer/client-id db (:v d)))))

(defmethod translate-datom :team/plan [db d]
  (-> d
    (assoc :v (web-peer/client-id db (:v d)))))

(defmethod translate-datom :plan/active-custs [db d]
  (-> d
    (assoc :v (:cust/email (d/entity db (:v d))))))

(defmethod translate-datom :discount/coupon [db d]
  (-> d
    (assoc :v (d/ident db (:v d)))))

(defn datom-read-api [db datom]
  (let [{:keys [e a v tx added] :as d} datom
        a (schema/get-ident a)
        v (if (and (contains? (schema/enums) a)
                   (contains? (schema/ident-ids) v))
            (schema/get-ident v)
            v)
        e (web-peer/client-id db e)]
    (->> {:e e :a a :v v :tx tx :added added}
      (translate-datom db))))

(defn whitelisted? [scope datom]
  (contains? (outgoing-whitelist scope) (:a datom)))

(defn frontend-document-transaction
  "Returns map of document transactions filtered for admin and filtered for read-only access"
  [transaction]
  (let [annotations (get-annotations transaction)]
    (when (and (:transaction/document annotations)
               (:transaction/broadcast annotations))
      (when-let [public-datoms (->> transaction
                                 :tx-data
                                 (filter #(:frontend/id (d/entity (:db-after transaction) (:e %))))
                                 (map (partial datom-read-api (:db-after transaction)))
                                 (filter (partial whitelisted? :admin))
                                 seq)]
        {:admin-data (merge {:tx-data public-datoms}
                            annotations)
         :read-only-data (merge {:tx-data (filter (partial whitelisted? :read) public-datoms)}
                                annotations)}))))

(defn frontend-team-transaction
  "Returns map of document transactions filtered for admin and filtered for read-only access"
  [transaction]
  (let [annotations (get-annotations transaction)]
    (when (and (:transaction/team annotations)
               (:transaction/broadcast annotations))
      (when-let [public-datoms (->> transaction
                                 :tx-data
                                 (filter #(:frontend/id (d/entity (:db-after transaction) (:e %))))
                                 (map (partial datom-read-api (:db-after transaction)))
                                 (filter (partial whitelisted? :admin))
                                 seq)]
        {:admin-data (merge {:tx-data public-datoms}
                            annotations)
         :read-only-data (merge {:tx-data (filter (partial whitelisted? :read) public-datoms)}
                                annotations)}))))
