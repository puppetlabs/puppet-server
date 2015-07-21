(ns puppetlabs.enterprise.jgit-utils-test
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.jgit-utils :refer :all]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.kitchensink.core :as ks]
            [schema.test :as schema-test]
            [me.raynes.fs :as fs])
  (:import (org.eclipse.jgit.api Git)))

(use-fixtures :once schema-test/validate-schemas)

(deftest test-head-rev-id
  (testing "Getting the latest commit on the current branch of a repo"
    (let [repo-dir (ks/temp-dir)
          git (helpers/init-repo! repo-dir)
          repo (.getRepository git)]

      (testing "It should return `nil` for a repo with no commits"
        (is (= nil (head-rev-id repo))))

      (testing "It should return the correct commit id for a repo with commits"
        (helpers/write-test-file! (str repo-dir "/test.txt"))
        (let [commit (add-and-commit git "a test commit" helpers/test-person-ident)
              id (commit-id commit)]
          (is (= (head-rev-id repo) id)))))))

(deftest test-head-rev-id-with-working-tree
  (testing "Getting the latest commit on the current branch of a repo"
    (let [repo-dir (ks/temp-dir)
          git (helpers/init-repo! repo-dir)]

      (testing "It should return `nil` for a repo with no commits"
        (is (= nil (head-rev-id-from-working-tree repo-dir))))

      (testing "It should return the correct commit id for a repo with commits"
        (helpers/write-test-file! (str repo-dir "/test.txt"))
        (let [commit (add-and-commit git "a test commit" helpers/test-person-ident)
              id (commit-id commit)]
          (is (= (head-rev-id-from-working-tree repo-dir) id)))))))

(deftest test-head-rev-id-with-git-dir
  (testing "Getting the latest commit on the current branch of a repo"
    (let [repo-dir (ks/temp-dir)
          local-repo-dir (ks/temp-dir)]

      (helpers/init-bare-repo! repo-dir)
      (testing "It should return `nil` for a repo with no commits"
        (is (= nil (head-rev-id-from-git-dir repo-dir))))

      (testing "It should return the correct commit id for a repo with commits"
        (helpers/write-test-file! (str local-repo-dir "/test.txt"))
        (let [local-repo (helpers/init-repo! local-repo-dir)
              commit (add-and-commit local-repo "a test commit" helpers/test-person-ident)
              commit-id (commit-id commit)]
          (push local-repo (str repo-dir))

          (is (= (head-rev-id-from-git-dir repo-dir) commit-id)))))))

(deftest test-remove-submodules-configuration
  (let [repo-dir (helpers/temp-dir-as-string)
        local-repo-dir (helpers/temp-dir-as-string)
        submodule-dir (helpers/temp-dir-as-string)]

    (helpers/init-bare-repo! (fs/file repo-dir))
    (helpers/init-bare-repo! (fs/file submodule-dir))
    (let [repo (get-repository repo-dir local-repo-dir)]
      (submodule-add!
        (Git. repo)
        submodule-dir submodule-dir)
      (let [git-config (.getConfig repo)
            gitmodules (submodules-config repo)]
        (.load gitmodules)
        (testing "git config and .gitmodules contain submodule"
          (is (= 1 (count (.getSubsections git-config "submodule"))))
          (is (= 1 (count (.getSubsections gitmodules "submodule"))))
          (is (= submodule-dir (.getString git-config "submodule" submodule-dir "url")))
          (is (= submodule-dir (.getString gitmodules "submodule" submodule-dir "url"))))

        (testing (str "remove-submodule-configuration! successfully removes "
                      "submodule configuration")
          (remove-submodule-configuration! repo submodule-dir)
          (.load gitmodules)
          (.load git-config)

          (is (empty? (.getSubsections git-config "submodule")))
          (is (empty? (.getSubsections gitmodules "submodule"))))))))

(deftest test-remove-submodule
  (let [repo-dir (helpers/temp-dir-as-string)
        local-repo-dir (helpers/temp-dir-as-string)
        submodule-dir (helpers/temp-dir-as-string)
        submodule-path "test-submodule"]

    (helpers/init-bare-repo! (fs/file repo-dir))
    (helpers/init-bare-repo! (fs/file submodule-dir))
    (let [repo (get-repository repo-dir local-repo-dir)
          git (Git. repo)]
      (submodule-add! git submodule-path submodule-dir)
      (add! git ".")
      (commit
        git
        "test commit"
        (common/identity->person-ident {:name "test" :email "test"}))
      (testing "submodule successfully added to repo"
        (is (= [submodule-path] (get-submodules repo))))
      (remove-submodule! repo submodule-path)
      (testing "submodule successfully removed from repo"
        (is (= 0 (count (get-submodules repo))))))))

(deftest test-clone
  (testing "When clone fails, it does not leave a bogus git repository behind"
    (let [repo-path (str (fs/temp-dir "repo") ".git")
          repo-url (str "file://" repo-path)]
      (helpers/init-bare-repo! repo-path)
      (testing "Normal clone (not bare)"
        (let [clone-path (fs/temp-dir "test-clone")]
          (is (thrown?
                Exception
                (clone "file:///invalid" clone-path)))
          (is (not (fs/exists? (fs/file clone-path ".git"))))
          (is (fs/exists? clone-path))
          (testing "Fixing the URL and re-attempting the clone works"
            (clone repo-url clone-path)
            (is (fs/exists? (fs/file clone-path ".git")))
            (is (not (empty? (fs/list-dir clone-path)))))))
      (testing "Bare repo"
        (testing "Existing repo dir"
          (let [clone-path (fs/temp-dir "test-clone.git")]
            (is (fs/exists? clone-path))
            (is (thrown?
                  Exception
                  (clone "file:///invalid" clone-path true)))
            (testing "Exsting directory should not be deleted"
              (is (fs/exists? clone-path)))
            (testing "But it should be empty"
              (is (empty? (fs/list-dir clone-path))))
            (testing "Fixing the URL and re-attempting the clone works"
              (clone repo-url clone-path true)
              (is (fs/exists? clone-path))
              (is (not (empty? (fs/list-dir clone-path)))))))
        (testing "Repo dir doesn't yet exist"
          (let [clone-path (ks/temp-file-name "test-clone.git")]
            (is (not (fs/exists? clone-path)))
            (is (thrown?
                  Exception
                  (clone "file:///invalid" clone-path true)))
            (testing "Directory which did not exist should not be created"
              (is (not (fs/exists? clone-path))))
            (testing "Fixing the URL and re-attempting the clone works"
              (clone repo-url clone-path true)
              (is (fs/exists? clone-path))
              (is (not (empty? (fs/list-dir clone-path)))))))))))

(deftest test-submodule-add
  (let [submodule-name "my-submodule"
        submodule-source-repo-path (str (fs/temp-dir "submodule-source") ".git")
        submodule-url (str "file://" submodule-source-repo-path)]
    (helpers/init-bare-repo! submodule-source-repo-path)
    (testing "When submodule-add! fails, it does not leave a bogus git repository behind"
      (let [repo-dir (fs/temp-dir "test-submodule-add")
            _ (helpers/init-repo! repo-dir)
            git (Git/open repo-dir)]
        (is (thrown?
              Exception
              (submodule-add! git submodule-name "file:///invalid")))
        (is (not (fs/exists? (fs/file repo-dir submodule-name))))
        (testing "Fixing the URL and re-attempting the submodule-add works"
          (submodule-add! git submodule-name submodule-url)
          (is (fs/exists? (fs/file repo-dir submodule-name)))
          (is (not (empty? (fs/list-dir (fs/file repo-dir submodule-name))))))))
    (testing "submodule-add! does not delete pre-existing files on failure"
      (let [repo-dir (fs/temp-dir "test-submodule-add")
            _ (helpers/init-repo! repo-dir)
            git (Git/open repo-dir)]
        (spit (fs/file repo-dir submodule-name) "foo")
        (is (thrown?
              Exception
              (submodule-add! git submodule-name "file:///invalid")))
        (is (= "foo" (slurp (fs/file repo-dir submodule-name))))))))
