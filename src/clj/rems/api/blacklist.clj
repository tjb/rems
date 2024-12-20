(ns rems.api.blacklist
  (:require [clojure.tools.logging :as log]
            [compojure.api.sweet :refer :all]
            [medley.core :refer [assoc-some]]
            [rems.api.schema :as schema]
            [rems.service.command :as command]
            [rems.service.blacklist]
            [rems.api.util :refer [extended-logging unprocessable-entity-json-response]] ; required for route :roles
            [rems.application.rejecter-bot :as rejecter-bot]
            [rems.common.roles :refer [+admin-read-roles+]]
            [rems.common.util :refer [getx-in]]
            [rems.db.user-mappings]
            [rems.schema-base :as schema-base]
            [rems.service.resource]
            [rems.service.users]
            [rems.util :refer [getx-user-id]]
            [ring.util.http-response :refer [ok]]
            [schema.core :as s])
  (:import [org.joda.time DateTime]))

(s/defschema BlacklistCommand
  {:blacklist/resource {:resource/ext-id s/Str}
   :blacklist/user schema-base/User
   :comment s/Str})

(s/defschema BlacklistEntryWithDetails
  (assoc schema/BlacklistEntry
         :blacklist/comment s/Str
         :blacklist/added-by schema-base/UserWithAttributes
         :blacklist/added-at DateTime))

(defn- user-not-found-error [command]
  (when-not (rems.service.users/user-exists? (get-in command [:blacklist/user :userid]))
    (unprocessable-entity-json-response "user not found")))

(defn- resource-not-found-error [command]
  (when-not (rems.service.resource/ext-id-exists? (get-in command [:blacklist/resource :resource/ext-id]))
    (unprocessable-entity-json-response "resource not found")))

(defn- get-blockable-users [] (rems.service.users/get-users))

(def blacklist-api
  (context "/blacklist" []
    :tags ["blacklist"]

    (GET "/" []
      :summary "Get blacklist entries"
      :roles +admin-read-roles+
      :query-params [{user :- schema-base/UserId nil}
                     {resource :- s/Str nil}]
      :return [BlacklistEntryWithDetails]
      (ok (rems.service.blacklist/get-blacklist (assoc-some nil
                                                            :userid (rems.db.user-mappings/find-userid user)
                                                            :resource/ext-id resource))))

    (GET "/users" []
      :summary "Existing REMS users available for adding to the blacklist"
      :roles  #{:owner :handler}
      :return [schema-base/UserWithAttributes]
      (ok (get-blockable-users)))

    ;; TODO write access to blacklist for organization-owner

    (POST "/add" request
      :summary "Add a blacklist entry"
      :roles #{:owner :handler}
      :body [command BlacklistCommand]
      :return schema/SuccessResponse
      (extended-logging request)
      (let [userid (rems.db.user-mappings/find-userid (getx-in command [:blacklist/user :userid]))
            command (assoc-in command [:blacklist/user :userid] userid)]
        (or (user-not-found-error command)
            (resource-not-found-error command)
            (do (rems.service.blacklist/add-user-to-blacklist! (getx-user-id) command)
                (doseq [cmd (rejecter-bot/reject-all-applications-by userid)]
                  (let [result (command/command! cmd)]
                    (when (:errors result)
                      (log/error "Failure when running rejecter bot commands:"
                                 {:cmd cmd :result result}))))
                (ok {:success true})))))

    (POST "/remove" request
      :summary "Remove a blacklist entry"
      :roles #{:owner :handler}
      :body [command BlacklistCommand]
      :return schema/SuccessResponse
      (extended-logging request)
      (let [userid (rems.db.user-mappings/find-userid (getx-in command [:blacklist/user :userid]))
            command (assoc-in command [:blacklist/user :userid] userid)]
        (or (user-not-found-error command)
            (resource-not-found-error command)
            (do (rems.service.blacklist/remove-user-from-blacklist! (getx-user-id) command)
                (ok {:success true})))))))
