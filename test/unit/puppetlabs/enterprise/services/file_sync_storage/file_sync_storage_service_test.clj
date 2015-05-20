(ns puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service-test
  (:import (org.eclipse.jgit.api.errors TransportException GitAPIException)
           (org.eclipse.jgit.api Git))
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core :as core]
            [puppetlabs.enterprise.jgit-utils :as jgit-utils]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.kitchensink.core :as ks]
            [me.raynes.fs :as fs]
            [cheshire.core :as json]
            [schema.test :as schema-test]))

(use-fixtures :once schema-test/validate-schemas)

(defn parse-response-body
  [response]
  (json/parse-string (slurp (:body response))))

(deftest push-disabled-test
  (testing "The JGit servlet should not accept pushes"
    (let [repo-id "push-disabled-test"
          config (helpers/storage-service-config-with-repos
                   (helpers/temp-dir-as-string)
                   {(keyword repo-id) {:working-dir repo-id}}
                   false)]
      (helpers/with-bootstrapped-file-sync-storage-service-for-http
        app config
        (let [clone-dir (helpers/temp-dir-as-string)
              server-repo-url (str (helpers/repo-base-url) "/" repo-id)]
          (jgit-utils/clone server-repo-url clone-dir)
          (testing "An attempt to push to the repo should fail"
            (is (thrown-with-msg?
                  TransportException
                  #"authentication not supported"
                  (helpers/push-test-commit! clone-dir)))))))))

(deftest file-sync-storage-service-simple-workflow-test
  (let [data-dir (helpers/temp-dir-as-string)
        repo-id "file-sync-storage-service-simple-workflow"]
    (testing "bootstrap the file sync storage service and validate that a simple
            clone/push/clone to the server works over http"
      (helpers/with-bootstrapped-file-sync-storage-service-for-http
        app
        (helpers/storage-service-config-with-repos
          data-dir
          {(keyword repo-id) {:working-dir repo-id}}
          false)
        (let [server-repo-url (str
                                (helpers/repo-base-url)
                                "/"
                                repo-id)
              repo-test-file "test-file"]
          (helpers/clone-and-push-test-commit! repo-id data-dir repo-test-file)
          (let [client-second-repo-dir (helpers/temp-dir-as-string)]
            (jgit-utils/clone  server-repo-url client-second-repo-dir)
            (is (= helpers/file-text
                   (slurp (str client-second-repo-dir "/" repo-test-file)))
                "Unexpected file text found in second repository clone")))))))

(deftest ssl-configuration-test
  (testing "file sync storage service cannot perform git operations over
            plaintext when the server is configured using SSL"
    (let [repo-name "ssl-configuration-test"
          config (helpers/storage-service-config-with-repos
                   (helpers/temp-dir-as-string)
                   {(keyword repo-name) {:working-dir repo-name}}
                   true)] ; 'true' results in config with Jetty listening on over HTTPS only
      ;; Ensure that JGit's global config is initially using plaintext.
      (helpers/configure-JGit-SSL! false)
      ;; Starting the storage service with SSL in the config should
      ;; reconfigure JGit's global state to allow access over SSL.
      (helpers/with-bootstrapped-file-sync-storage-service-for-http
        app config
        (is (thrown? TransportException
                     (jgit-utils/clone
                       (str (helpers/repo-base-url) "/" repo-name)
                       (helpers/temp-dir-as-string))))))))

(deftest latest-commits-test
  (let [data-dir (helpers/temp-dir-as-string)
        repo1-id "latest-commits-test-1"
        repo2-id "latest-commits-test-2"
        repo3-id "latest-commits-test-3"]
    (helpers/with-bootstrapped-file-sync-storage-service-for-http
      app
      (helpers/storage-service-config-with-repos
        data-dir
        {(keyword repo1-id) {:working-dir repo1-id}
         (keyword repo2-id) {:working-dir repo2-id}
         (keyword repo3-id) {:working-dir repo3-id}}
        false)

      (let [client-orig-repo-dir-1 (helpers/clone-and-push-test-commit! repo1-id data-dir)
            client-orig-repo-dir-2 (helpers/clone-and-push-test-commit! repo2-id data-dir)]

        (testing "Validate /latest-commits endpoint"
          (let [response (http-client/get (str
                                            helpers/server-base-url
                                            helpers/default-api-path-prefix
                                            "/v1"
                                            common/latest-commits-sub-path))
                content-type (get-in response [:headers "content-type"])]

            (testing "the endpoint returns JSON"
              (is (.startsWith content-type "application/json")
                  (str
                    "The response's Content-type should be JSON. Reponse: "
                    response)))

            (testing "the SHA-1 IDs it returns are correct"
              (let [body (parse-response-body response)]
                (is (map? body))

                (testing "A repository with no commits in it returns a nil ID"
                  (is (contains? body repo3-id))
                  (let [rev (get body repo3-id)]
                    (is (= rev nil))))

                (testing "the first repo"
                  (let [actual-rev (get body repo1-id)
                        expected-rev (jgit-utils/head-rev-id-from-working-tree
                                       client-orig-repo-dir-1)]
                    (is (= actual-rev expected-rev))))

                (testing "The second repo"
                  (let [actual-rev (get body repo2-id)
                        expected-rev (jgit-utils/head-rev-id-from-working-tree
                                       client-orig-repo-dir-2)]
                    (is (= actual-rev expected-rev))))))))))))

(defn get-commit
  [repo]
  (-> repo
      Git/open
      .log
      .call
      first))

(def publish-url (str helpers/server-base-url
                      helpers/default-api-path-prefix
                      "/v1"
                      common/publish-content-sub-path))

(defn make-publish-request
  [body]
  (http-client/post publish-url
             {:body    (json/encode body)
              :headers {"Content-Type" "application/json"}}))

(deftest publish-content-endpoint-success-test
  (testing "publish content endpoint makes correct commit"
    (let [repo "test-commit"
          working-dir (helpers/temp-dir-as-string)
          data-dir (helpers/temp-dir-as-string)
          server-repo (fs/file data-dir (str repo ".git"))]

      (helpers/with-bootstrapped-file-sync-storage-service-for-http
        app
        (helpers/storage-service-config-with-repos
          data-dir
          {(keyword repo) {:working-dir working-dir}}
          false)
        (testing "with no body supplied"
          (let [response (http-client/post publish-url)
                body (slurp (:body response))]
            (testing "get expected response"
              (is (= (:status response) 200))
              (is (= repo (first (keys (json/parse-string body))))
                  (str "Unexpected response body: " body)))
            (let [commit (get-commit server-repo)]
              (testing "commit message is correct"
                (is (= core/default-commit-message
                       (.getFullMessage commit))))
              (testing "commit author is correct"
                (is (= core/default-commit-author-name (.getName (.getAuthorIdent commit))))
                (is (= core/default-commit-author-email
                       (.getEmailAddress (.getAuthorIdent commit))))))))

        (testing "with just author supplied"
          (let [author {:name  "Tester"
                        :email "test@example.com"}
                response (make-publish-request {:author author})
                body (slurp (:body response))]
            (testing "get expected response"
              (is (= (:status response) 200))
              (is (= repo (first (keys (json/parse-string body))))
                  (str "Unexpected response body: " body)))
            (let [commit (get-commit server-repo)]
              (testing "commit message is correct"
                (is (= core/default-commit-message
                       (.getFullMessage commit))))
              (testing "commit author is correct"
                (is (= (:name author) (.getName (.getAuthorIdent commit))))
                (is (= (:email author) (.getEmailAddress (.getAuthorIdent commit))))))))

        (testing "with author and message supplied"
          (let [author {:name  "Tester"
                        :email "test@example.com"}
                message "This is a test commit"
                response (make-publish-request {:author author :message message})
                body (slurp (:body response))]
            (testing "get expected response"
              (is (= (:status response) 200))
              (is (= repo (first (keys (json/parse-string body))))
                  (str "Unexpected response body: " body)))
            (let [commit (get-commit server-repo)]
              (testing "commit message is correct"
                (is (= message (.getFullMessage commit))))
              (testing "commit author is correct"
                (is (= (:name author) (.getName (.getAuthorIdent commit))))
                (is (= (:email author) (.getEmailAddress (.getAuthorIdent commit))))))))))))

(deftest publish-content-endpoint-error-test
  (testing "publish content endpoint returns well-formed errors"
    (helpers/with-bootstrapped-file-sync-storage-service-for-http
      app
      (helpers/storage-service-config-with-repos
        (helpers/temp-dir-as-string)
        {:repo-name {:working-dir (helpers/temp-dir-as-string)}}
        false)

      (testing "when request body does not match schema"
        (let [response (make-publish-request {:author "bad"})
              body (slurp (:body response))]
          (is (= (:status response) 400))
          (is (= "user-data-invalid" (get-in (json/parse-string body) ["error" "type"]))
              (str "Unexpected response body: " body))))

      (testing "when request body is malformed json"
        (let [response (http-client/post publish-url
                                  {:body "malformed"
                                   :headers {"Content-Type" "application/json"}})
              body (slurp (:body response))]
          (is (= (:status response) 400))
          (is (= "json-parse-error" (get-in (json/parse-string body) ["error" "type"]))
              (str "Unexpected response body: " body))))

      (testing "when request body is not json"
        (let [response (http-client/post publish-url {:body "not json"
                                                      :headers {"Content-Type" "text/plain"}})
              body (slurp (:body response))]
          (is (= (:status response) 400))
          (is (= "content-type-error" (get-in (json/parse-string body) ["error" "type"]))
              (str "Unexpected response body: " body)))))))

(deftest publish-content-endpoint-response-test
  (testing "publish content endpoint returns correct response"
    (let [failed-repo "publish-failed"
          success-repo "publish-success"
          working-dir-failed (helpers/temp-dir-as-string)
          working-dir-success (helpers/temp-dir-as-string)
          data-dir (helpers/temp-dir-as-string)]
      (helpers/with-bootstrapped-file-sync-storage-service-for-http
        app
        (helpers/storage-service-config-with-repos
          data-dir
          {(keyword failed-repo) {:working-dir working-dir-failed}
           (keyword success-repo) {:working-dir working-dir-success}}
          false)

        ; Delete the failed repo entirely - this'll cause the publish to fail
        (fs/delete-dir (fs/file data-dir (str failed-repo ".git")))

        (with-test-logging
          (let [response (http-client/post publish-url)
                body (slurp (:body response))]
            (testing "get a 200 response"
              (is (= 200 (:status response))))

            (let [data (json/parse-string body)]
              (testing "for repo that was successfully published"
                (is (not= nil (get data success-repo)))
                (is (= (-> (fs/file data-dir (str success-repo ".git"))
                           (jgit-utils/get-repository-from-git-dir)
                           (jgit-utils/head-rev-id))
                       (get-in data [success-repo "commit"]))
                    (str "Could not find correct body for " success-repo " in " body)))
              (testing "for repo that failed to publish"
                (is (re-matches #".*publish-error"
                                (get-in data [failed-repo "error" "type"]))
                    (str "Could not find correct body for " failed-repo " in " body))))))))))

;; The publish endpoint returns the commit taken from the submodule's repo.
;; This gets the commit from the parent repo to compare against that to ensure
;; that the two are the same.
(defn get-submodules-status
  [git-dir working-dir]
  (let [submodule-status (-> (jgit-utils/get-repository git-dir working-dir)
                           Git/wrap
                           .submoduleStatus
                           .call)]
    (ks/mapvals (fn [v] (.getName (.getIndexId v))) submodule-status)))

(deftest publish-endpoint-response-with-submodules-test
  (let [failed-parent "parent-failed"
        successful-parent "parent-success"
        submodule-1 "submodule-1"
        submodule-2 "submodule-2"
        submodule-3 "submodule-3"
        submodule-4 "submodule-4"
        working-dir-failed (helpers/temp-dir-as-string)
        working-dir-success (helpers/temp-dir-as-string)
        data-dir (helpers/temp-dir-as-string)
        submodules-dir-name-1 "submodules1"
        submodules-dir-name-2 "submodules2"
        submodules-working-dir-1 (helpers/temp-dir-as-string)
        submodules-working-dir-2 (helpers/temp-dir-as-string)]
  (ks/mkdirs! (fs/file submodules-working-dir-1 submodule-1))
  (helpers/write-test-file! (fs/file submodules-working-dir-1 submodule-1 "test.txt"))
  (ks/mkdirs! (fs/file submodules-working-dir-1 submodule-2))
  (helpers/write-test-file! (fs/file submodules-working-dir-1 submodule-2 "test.txt"))

  (ks/mkdirs! (fs/file submodules-working-dir-2 submodule-3))
  (helpers/write-test-file! (fs/file submodules-working-dir-2 submodule-3 "test.txt"))
  (ks/mkdirs! (fs/file submodules-working-dir-2 submodule-4))
  (helpers/write-test-file! (fs/file submodules-working-dir-2 submodule-4 "test.txt"))

  (helpers/with-bootstrapped-file-sync-storage-service-for-http
    app
    (helpers/storage-service-config-with-repos
      data-dir
      {(keyword successful-parent) {:working-dir working-dir-success
                                    :submodules-dir submodules-dir-name-1
                                    :submodules-working-dir submodules-working-dir-1}
       (keyword failed-parent) {:working-dir working-dir-failed
                                :submodules-dir submodules-dir-name-2
                                :submodules-working-dir submodules-working-dir-2}}
    false)

    (testing "successful publish returns shas for parent repos and submodules"
      (let [response (http-client/post publish-url)
            parsed-body (parse-response-body response)]
        (is (= 200 (:status response)))

        (is (= (-> (fs/file data-dir (str successful-parent ".git"))
                 (jgit-utils/head-rev-id-from-git-dir))
              (get-in parsed-body [successful-parent "commit"])))
        (is (= (get-submodules-status (fs/file data-dir (str successful-parent ".git")) working-dir-success)
              (get-in parsed-body [successful-parent "submodules"])))

        (is (= (-> (fs/file data-dir (str failed-parent ".git"))
                 (jgit-utils/head-rev-id-from-git-dir))
              (get-in parsed-body [failed-parent "commit"])))
        (is (= (get-submodules-status (fs/file data-dir (str failed-parent ".git")) working-dir-failed)
              (get-in parsed-body [failed-parent "submodules"])))))

    (testing "publish endpoint returns correct errors"
      (with-test-logging
        ; Delete a submodule repo entirely - this'll cause the publish to fail
        (fs/delete-dir (fs/file data-dir successful-parent (str submodule-1 ".git")))
        ; Delete a parent repo entirely - this'll cause the publish to fail
        (fs/delete-dir (fs/file data-dir (str failed-parent ".git")))

        (let [response (http-client/post publish-url)
              body (slurp (:body response))
              parsed-body (json/parse-string body)]
          (is (= 200 (:status response)))

          (testing "when one submodule failed to publish, but parent repo succeeded"
            (let [successful-parent-repo (jgit-utils/get-repository (fs/file data-dir (str successful-parent ".git")) working-dir-success)
                  submodules-status (.. (Git/wrap successful-parent-repo)
                                      submoduleStatus
                                      call)]
              (testing "returns sha for non-failed submodules"
                (is (= (.getName (.getIndexId (get submodules-status (str submodules-dir-name-1 "/" submodule-2))))
                    (get-in parsed-body [successful-parent "submodules" (str submodules-dir-name-1 "/" submodule-2)]))))
              (testing "returns sha for parent repo"
                (is (= (-> (fs/file data-dir (str successful-parent ".git"))
                        (jgit-utils/head-rev-id-from-git-dir))
                      (get-in parsed-body [successful-parent "commit"]))))
              (testing "returns error for failed submodule"
                (is (re-matches #".*publish-error"
                      (get-in parsed-body
                        [successful-parent "submodules" (str submodules-dir-name-1 "/" submodule-1) "error" "type"]))
                  (str "Could not find correct body for submodule "  (str submodules-dir-name-1 "/" submodule-1)
                    " of parent repo " successful-parent " in " body)))))

          (testing "when parent repo failed to publish"
            (testing "returns error for parent repo"
              (is (re-matches #".*publish-error" (get-in parsed-body [failed-parent "error" "type"]))
                (str "Could not find correct body for repo " failed-parent " in " body)))
            (testing "returns nothing for submodules"
              (is (= ["error"] (keys (parsed-body failed-parent))))))))))))

(deftest submodules-test
  (testing "storage service works with submodules"
    (let [repo "parent-repo"
          submodules-working-dir (helpers/temp-dir-as-string)
          working-dir (helpers/temp-dir-as-string)
          submodules-dir-name "submodules"
          submodule-1 "existing-submodule"
          submodule-2 "nonexistent-submodule"
          data-dir (helpers/temp-dir-as-string)
          git-dir (fs/file data-dir (str repo ".git"))]

      (ks/mkdirs! (fs/file submodules-working-dir submodule-1))
      (helpers/write-test-file! (fs/file submodules-working-dir submodule-1 "test.txt"))

      (helpers/with-bootstrapped-file-sync-storage-service-for-http
        app
        (helpers/storage-service-config-with-repos
          data-dir
          {(keyword repo) {:working-dir working-dir
                           :submodules-dir submodules-dir-name
                           :submodules-working-dir submodules-working-dir}}
          false)

       (testing "parent repo initialized correctly but does not initialize any submodules"
         (is (fs/exists? (fs/file data-dir (str repo ".git"))))
         (is (not (fs/exists? (fs/file data-dir repo (str submodule-1 ".git")))))
         (is (not (fs/exists? (fs/file data-dir repo (str submodule-2 ".git")))))
         (let [submodules (get-submodules-status git-dir working-dir)]
           (is (empty? submodules))))

       (testing "publish works and initializes submodule"
         (let [response (http-client/post publish-url)
               body (slurp (:body response))]
           (is (= 200 (:status response)))
           (is (fs/exists? (fs/file data-dir repo (str submodule-1 ".git"))))
           (is (not (fs/exists? (fs/file data-dir repo (str submodule-2 ".git")))))
           (is (= (get-submodules-status git-dir working-dir)
                 (get-in (json/parse-string body) [repo "submodules"])))))

       (testing "adding a new submodule and triggering another publish"
         (ks/mkdirs! (fs/file submodules-working-dir submodule-2))
         (helpers/write-test-file! (fs/file submodules-working-dir submodule-2 "test.txt"))
         (let [response (http-client/post publish-url)
               body (slurp (:body response))]
           (is (= 200 (:status response)))
           (is (fs/exists? (fs/file data-dir repo (str submodule-1 ".git"))))
           (is (fs/exists? (fs/file data-dir repo (str submodule-2 ".git"))))
           (is (= (get-submodules-status git-dir working-dir)
                 (get-in (json/parse-string body) [repo "submodules"])))))

       (testing "updating a submodule and triggering a publish"
         (helpers/write-test-file! (fs/file submodules-working-dir submodule-1 "update.txt"))
         (let [response (http-client/post publish-url)
               body (slurp (:body response))]
           (is (= 200 (:status response)))
           (is (= (get-submodules-status git-dir working-dir)
                 (get-in (json/parse-string body) [repo "submodules"])))
           (is (fs/exists? (fs/file working-dir submodules-dir-name submodule-1 "update.txt")))
           (is (= (slurp (fs/file submodules-working-dir submodule-1 "update.txt"))
                 (slurp (fs/file working-dir submodules-dir-name submodule-1 "update.txt"))))))))))
