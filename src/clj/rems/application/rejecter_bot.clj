(ns rems.application.rejecter-bot
  (:require [clj-time.core :as time]
            [clojure.tools.logging :as log]
            [rems.common.application-util :as application-util]
            [rems.db.applications :as applications]
            [rems.permissions :as permissions]))

(def bot-userid "rejecter-bot")

(defn- should-reject? [application]
  (not (empty? (:application/blacklist application))))

(defn- can-reject? [application]
  (contains? (permissions/user-permissions application bot-userid) :application.command/reject))

(defn- consider-rejecting [application]
  (when (and (application-util/is-handler? application bot-userid)
             (should-reject? application)
             (can-reject? application))
    (log/info "Rejecter bot rejecting application" (:application/id application) "based on blacklist" (:application/blacklist application))
    [{:type :application.command/reject
      :application-id (:application/id application)
      :time (time/now)
      :comment ""
      :actor bot-userid}]))

(defn- generate-commands [event application]
  (when (= :application.event/submitted (:event/type event)) ;; rejecter bot only reacts to fresh applications
    (consider-rejecting application)))

(defn run-rejecter-bot [new-events]
  (doall (mapcat #(generate-commands % (applications/get-application (:application/id %)))
                 new-events)))

(defn reject-all-applications-by
  "Go through all applications by the given user-id and reject any if necessary. Returns sequence of commands."
  [user-id]
  (let [apps (mapv #(applications/get-application-internal (:application/id %))
                   (applications/get-my-applications user-id))]
    (mapcat consider-rejecting apps)))
