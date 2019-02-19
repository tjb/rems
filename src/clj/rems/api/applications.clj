(ns rems.api.applications
  (:require [clj-time.core :as time]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [compojure.api.sweet :refer :all]
            [rems.api.applications-v2 :refer [get-user-applications-v2 api-get-application-v2 api-get-application-v1]]
            [rems.api.schema :refer :all]
            [rems.api.util :refer [longify-keys]]
            [rems.config :refer [env]]
            [rems.context :as context]
            [rems.db.applications :as applications]
            [rems.db.core :as db]
            [rems.db.users :as users]
            [rems.form :as form]
            [rems.pdf :as pdf]
            [rems.util :refer [getx-user-id update-present]]
            [rems.workflow.dynamic :as dynamic]
            [ring.swagger.upload :as upload]
            [ring.util.http-response :refer :all]
            [schema.core :as s]))

;; Response models

(s/defschema GetApplicationsResponse
  [Application])

(s/defschema Phases
  [{:phase s/Keyword
    (s/optional-key :active?) s/Bool
    (s/optional-key :approved?) s/Bool
    (s/optional-key :closed?) s/Bool
    (s/optional-key :completed?) s/Bool
    (s/optional-key :rejected?) s/Bool
    :text s/Keyword}])

(s/defschema GetApplicationResponse
  {:id (s/maybe s/Num)
   :catalogue-items [CatalogueItem]
   :applicant-attributes (s/maybe {s/Keyword s/Str})
   :application (s/maybe Application)
   :licenses [ApplicationLicense]
   :phases Phases
   :title s/Str
   :items [Item]})

(s/defschema SaveApplicationCommand
  {:command (s/enum "save" "submit")
   (s/optional-key :application-id) s/Num
   (s/optional-key :catalogue-items) [s/Num]
   ;; NOTE: compojure-api only supports keyword keys properly, see
   ;; https://github.com/metosin/compojure-api/issues/341
   :items {s/Any s/Str}
   (s/optional-key :licenses) {s/Any s/Str}})

(s/defschema ValidationMessage
  {:type s/Keyword
   (s/optional-key :field-id) s/Num
   (s/optional-key :license-id) s/Num})

(s/defschema SaveApplicationResponse
  {:success s/Bool
   :valid s/Bool
   (s/optional-key :id) s/Num
   (s/optional-key :state) (s/cond-pre s/Str s/Keyword) ;; HACK for dynamic applications
   (s/optional-key :validation) [ValidationMessage]})

(s/defschema JudgeApplicationCommand
  {:command (s/enum "approve" "close" "reject" "return" "review" "third-party-review" "withdraw")
   :application-id s/Num
   :round s/Num
   :comment s/Str})

(s/defschema ReviewRequestCommand
  {:application-id s/Num
   :round s/Num
   :comment s/Str
   :recipients [s/Str]})

(s/defschema Applicant
  {:userid s/Str
   :name (s/maybe s/Str)
   :email (s/maybe s/Str)})

(s/defschema Reviewer
  {:userid s/Str
   :name (s/maybe s/Str)
   :email (s/maybe s/Str)})

(s/defschema Reviewers
  [Reviewer])

(s/defschema Commenter
  {:userid s/Str
   :name (s/maybe s/Str)
   :email (s/maybe s/Str)})

(s/defschema Commenters
  [Commenter])

(s/defschema Decider
  {:userid s/Str
   :name (s/maybe s/Str)
   :email (s/maybe s/Str)})

(s/defschema Deciders
  [Decider])

(s/defschema DynamicCommand
  {:type s/Keyword
   :application-id s/Num
   s/Keyword s/Any})

;; Api implementation

(defn- api-judge [{:keys [command application-id round comment actor]}]
  (case command
    "approve" (applications/approve-application actor application-id round comment)
    "close" (applications/close-application actor application-id round comment)
    "reject" (applications/reject-application actor application-id round comment)
    "return" (applications/return-application actor application-id round comment)
    "review" (applications/review-application actor application-id round comment)
    "third-party-review" (applications/perform-third-party-review actor application-id round comment)
    "withdraw" (applications/withdraw-application actor application-id round comment))
  ;; failure communicated via an exception
  {:success true})

(defn- fix-keys [application]
  (-> application
      (update-in [:items] longify-keys)
      (update-in [:licenses] longify-keys)))

(defn- hide-sensitive-events [events]
  (filter (fn [event]
            ((complement contains?) #{"third-party-review" "review-request" "review"} (:event event)))
          events))

(defn- latest-event [events]
  (when (seq events)
    (apply time/max-date (map #(or (:time %) ; Non-dynamic events
                                   (:event/time %)) events)))) ; Dynamic events

(defn- update-application-last-modified [application]
  (let [events (or (:events application)
                   (:dynamic-events application))]
    (assoc application :last-modified (latest-event events))))

(defn- hide-users [events]
  (map (fn [event]
         (assoc event :userid nil))
       events))

(defn hide-sensitive-information [application user]
  (let [is-handler? (or (contains? (set (applications/get-handlers (:application application))) user) ; old form
                        (applications/is-dynamic-handler? user (:application application)))] ; dynamic
    (if is-handler?
      application
      (-> application
          (update :application update-application-last-modified)
          (update-in [:application :events] hide-sensitive-events)
          (update-in [:application :dynamic-events] dynamic/hide-sensitive-dynamic-events)
          (update-in [:application :events] hide-users)
          (update-in [:application :workflow] dissoc :handlers)))))

(defn api-get-application [user-id application-id]
  (when (not (empty? (db/get-applications {:id application-id})))
    (-> (applications/get-form-for user-id application-id)
        (hide-sensitive-information user-id))))

(defn invalid-user? [u]
  (or (str/blank? (:eppn u))
      (str/blank? (:commonName u))
      (str/blank? (:mail u))))

(defn format-user [u]
  {:userid (:eppn u)
   :name (:commonName u)
   :email (:mail u)})

;; TODO Filter applicant, requesting user
(defn get-users []
  (->> (users/get-all-users)
       (remove invalid-user?)
       (map format-user)))

(def get-applicants get-users)

(def get-reviewers get-users)

(def get-commenters get-users)

(def get-deciders get-users)

(defn- check-attachment-content-type
  "Checks that content-type matches the allowed ones listed on the UI side:
   .pdf, .doc, .docx, .ppt, .pptx, .txt, image/*"
  [content-type]
  (when-not (or (#{"application/pdf"
                   "application/msword"
                   "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                   "application/vnd.ms-powerpoint"
                   "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                   "text/plain"}
                 content-type)
                (.startsWith content-type "image/"))
    (throw (rems.InvalidRequestException. (str "Unsupported content-type: " content-type)))))

(defn- fix-command-from-api
  [cmd]
  ;; schema could do these coercions for us...
  (update-present cmd :decision keyword))

(def applications-api
  (context "/applications" []
    :tags ["applications"]

    (GET "/" []
      :summary "Get current user's all applications"
      :roles #{:logged-in}
      :return GetApplicationsResponse
      (ok (applications/get-user-applications (getx-user-id))))

    (GET "/draft" []
      :summary "Get application (draft) for `catalogue-items`"
      :roles #{:logged-in}
      :query-params [catalogue-items :- (describe [s/Num] "catalogue item ids")]
      :return GetApplicationResponse
      (let [app (applications/make-draft-application (getx-user-id) catalogue-items)]
        (ok (applications/get-draft-form-for app))))

    (GET "/reviewers" []
      :summary "Available third party reviewers"
      :roles #{:approver}
      :return Reviewers
      (ok (get-reviewers)))

    (GET "/commenters" []
      :summary "Available third party commenters"
      :roles #{:approver}
      :return Commenters
      (ok (get-commenters)))

    (GET "/members" []
      :summary "Existing REMS users available for application membership"
      :roles #{:approver}
      :return [Applicant]
      (ok (get-applicants)))

    (GET "/deciders" []
      :summary "Available deciders"
      :roles #{:approver}
      :return Deciders
      (ok (get-deciders)))

    (GET "/attachments/" []
      :summary "Get an attachment for a field in an application"
      :roles #{:logged-in}
      :query-params [application-id :- (describe s/Int "application id")
                     field-id :- (describe s/Int "application form field id the attachment is related to")]
      (let [form (applications/get-form-for (getx-user-id) application-id)]
        (if-let [attachment (db/get-attachment {:item field-id
                                                :form (:id form)
                                                :application application-id})]
          (do (check-attachment-content-type (:type attachment))
              (-> (:data attachment)
                  (java.io.ByteArrayInputStream.)
                  (ok)
                  (content-type (:type attachment))))
          (not-found! "not found"))))

    (GET "/:application-id" []
      :summary "Get application by `application-id`"
      :roles #{:logged-in}
      :path-params [application-id :- (describe s/Num "application id")]
      :responses {200 {:schema GetApplicationResponse}
                  404 {:schema s/Str :description "Not found"}}
      (if-let [app (api-get-application (getx-user-id) application-id)]
        (ok app)
        (not-found! "not found")))

    (GET "/v2-wip/" []
      :summary "Get current user's all applications"
      :roles #{:logged-in}
      :return [s/Any] ; TODO: add schema once the API has stabilized
      (when (:dev env) ; TODO: remove feature toggle
        (ok (get-user-applications-v2 (getx-user-id)))))

    (GET "/v2-wip/:application-id" []
      :summary "Get application by `application-id`"
      :roles #{:logged-in}
      :path-params [application-id :- (describe s/Num "application id")]
      :responses {200 {:schema s/Any} ; TODO: add schema once the API has stabilized
                  404 {:schema s/Str :description "Not found"}}
      (when (:dev env) ; TODO: remove feature toggle
        (if-let [app (api-get-application-v2 (getx-user-id) application-id)]
          (ok app)
          (not-found! "not found"))))

    (GET "/v2-to-v1-wip/:application-id" []
      :summary "Get application by `application-id`"
      :roles #{:logged-in}
      :path-params [application-id :- (describe s/Num "application id")]
      :responses {200 {:schema s/Any} ; TODO: use GetApplicationResponse schema
                  404 {:schema s/Str :description "Not found"}}
      (when (:dev env) ; TODO: remove feature toggle
        (if-let [app (api-get-application-v1 (getx-user-id) application-id)]
          (ok app)
          (not-found! "not found"))))

    (GET "/:application-id/pdf" []
      :summary "Get a pdf version of an application"
      :roles #{:logged-in}
      :path-params [application-id :- (describe s/Num "application id")]
      :produces ["application/pdf"]
      (if-let [app (api-get-application (getx-user-id) application-id)]
        (-> app
            (pdf/application-to-pdf-bytes)
            (java.io.ByteArrayInputStream.)
            (ok)
            (content-type "application/pdf"))
        (not-found! "not found")))

    (POST "/save" []
      :summary "Create a new application, change an existing one or submit an application"
      :roles #{:logged-in}
      :body [request SaveApplicationCommand]
      :return SaveApplicationResponse
      (ok (form/api-save (assoc (fix-keys request) :actor (getx-user-id)))))

    (POST "/judge" []
      :summary "Judge an application"
      :roles #{:applicant :approver :reviewer}
      :body [request JudgeApplicationCommand]
      :return SuccessResponse
      (ok (api-judge (assoc request :actor (getx-user-id)))))

    (POST "/review_request" []
      :summary "Request a review"
      :roles #{:approver}
      :body [request ReviewRequestCommand]
      :return SuccessResponse
      (applications/send-review-request (getx-user-id)
                                        (:application-id request)
                                        (:round request)
                                        (:comment request)
                                        (:recipients request))
      (ok {:success true}))

    ;; TODO: think about size limit
    (POST "/add_attachment" []
      :summary "Add an attachment file related to an application field"
      :roles #{:applicant}
      :multipart-params [file :- upload/TempFileUpload]
      :query-params [application-id :- (describe s/Int "application id")
                     field-id :- (describe s/Int "application form field id the attachment is related to")]
      :middleware [upload/wrap-multipart-params]
      :return SuccessResponse
      (check-attachment-content-type (:content-type file))
      (applications/save-attachment! file (getx-user-id) application-id field-id)
      (ok {:success true}))

    (POST "/remove_attachment" []
      :summary "Remove an attachment file related to an application field"
      :roles #{:applicant}
      :query-params [application-id :- (describe s/Int "application id")
                     field-id :- (describe s/Int "application form field id the attachment is related to")]
      :return SuccessResponse
      (applications/remove-attachment! (getx-user-id) application-id field-id)
      (ok {:success true}))

    (POST "/command" []
      :summary "Submit a command for a dynamic application"
      :roles #{:logged-in}
      :body [request DynamicCommand]
      :return SuccessResponse
      (let [cmd (assoc request :actor (getx-user-id))
            fixed (fix-command-from-api cmd)
            fixed (assoc fixed :time (time/now))
            errors (applications/dynamic-command! fixed)]
        (if errors
          (ok {:success false
               :errors (:errors errors)})
          (ok {:success true}))))))

(comment
  (let [user-id "developer"]
    (binding [context/*lang* :en]
      (doseq [app (applications/get-user-applications user-id)]
        (when (applications/is-dynamic-application? app)
          (is (= (assoc-in (api-get-application user-id (:id app))
                           [:application :dynamic-events] nil)
                 (assoc-in (api-get-application-v1 user-id (:id app))
                           [:application :dynamic-events] nil))))))))
