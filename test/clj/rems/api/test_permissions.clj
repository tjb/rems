(ns ^:integration rems.api.test-permissions
  (:require [clojure.test :refer :all]
            [rems.api.testing :refer :all]
            [rems.handler :refer [handler]]
            [ring.mock.request :refer :all]))

(use-fixtures
  :once
  api-fixture
  (fn [f]
    (with-redefs [rems.config/env (assoc rems.config/env :enable-permissions-api true)]
      (f))))

(defn- validate-alice-result [data]
  (is (= 1 (count data)))
  (is (contains? data :ga4gh_visa_v1))
  (is (vector? (:ga4gh_visa_v1 data)))
  (is (string? (first (:ga4gh_visa_v1 data)))))

(deftest permissions-test-content
  (let [api-key "42"]
    (testing "all for alice as handler"
      (let [data (-> (request :get "/api/permissions/alice")
                     (authenticate api-key "handler")
                     handler
                     read-ok-body)]
        (validate-alice-result data)))

    (testing "all for alice as owner"
      (let [data (-> (request :get "/api/permissions/alice")
                     (authenticate api-key "owner")
                     handler
                     read-ok-body)]
        (validate-alice-result data)))

    (testing "without user not found is returned"
      (let [response (-> (request :get "/api/permissions")
                         (authenticate api-key "handler")
                         handler)
            body (read-body response)]
        (is (= "not found" body))))))

(deftest permissions-test-security
  (let [api-key "42"]
    (testing "listing without authentication"
      (let [response (-> (request :get (str "/api/permissions/userx"))
                         handler)
            body (read-body response)]
        (is (= "unauthorized" body))))

    (testing "listing without appropriate role"
      (let [response (-> (request :get (str "/api/permissions/alice"))
                         (authenticate api-key "approver1")
                         handler)
            body (read-body response)]
        (is (= "forbidden" body))))

    (testing "all for alice as malice"
      (let [response (-> (request :get (str "/api/permissions/alice"))
                         (authenticate api-key "malice")
                         handler)
            body (read-body response)]
        (is (= "forbidden" body))))))

(deftest permissions-test-api-disabled
  (with-redefs [rems.config/env (assoc rems.config/env :enable-permissions-api false)]
    (let [api-key "42"]
      (testing "when permissions api is disabled"
        (let [response (-> (request :get "/api/permissions/alice")
                           (authenticate api-key "handler")
                           handler)
              body (read-body response)]
          (is (= "permissions api not implemented" body)))))))