(ns rems.api.users
  (:require [compojure.api.sweet :refer :all]
            [rems.api.schema :refer :all]
            [rems.api.util :refer [check-roles check-user]]
            [rems.db.users :as users]
            [ring.util.http-response :refer :all]
            [schema.core :as s]))

(def CreateUserCommand
  {:eppn s/Str
   :mail s/Str
   :commonName s/Str})

(defn create-user [user-data]
  (users/add-user! (:eppn user-data) user-data))

(def users-api
  (context "/users" []
    :tags ["users"]

    (POST "/create" []
      :summary "Create user"
      :body [command CreateUserCommand]
      :return SuccessResponse
      (check-user)
      (check-roles :owner)
      (create-user command)
      (ok {:success true}))))