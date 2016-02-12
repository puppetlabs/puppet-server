(ns puppetlabs.puppetserver.shell-utils-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.shell-utils :as sh-utils]
            [puppetlabs.kitchensink.core :as ks])
  (:import (java.io ByteArrayInputStream)))

(def test-resources
  (ks/absolute-path
   "./dev-resources/puppetlabs/puppetserver/shell_utils_test"))

(defn script-path
  [script-name]
  (str test-resources "/" script-name))

(deftest returns-the-exit-code
  (testing "true should return 0"
    (is (zero? (:exit-code (sh-utils/execute-command (script-path "true"))))))
  (testing "false should return 1"
    (is (= 1 (:exit-code (sh-utils/execute-command (script-path "false")))))))

(deftest returns-stdout-correctly
  (testing "echo should add content to stdout"
    (is (= "foo\n" (:stdout (sh-utils/execute-command
                             (script-path "echo")
                             {:args ["foo"]}))))))

(deftest returns-stderr-correctly
  (testing "echo can add content to stderr as well"
    (is (= "bar\n" (:stderr (sh-utils/execute-command
                             (script-path "warn")
                             {:args ["bar"]}))))))

(deftest pass-args-correctly
  (testing "passes the expected number of args to cmd"
    (is (= 5 (:exit-code (sh-utils/execute-command
                          (script-path "num-args")
                          {:args ["a" "b" "c" "d" "e"]}))))))

(deftest sets-env-correctly
  (testing "sets environment variables correctly"
    (is (= "foo\n" (:stdout (sh-utils/execute-command
                             (script-path "echo_foo_env_var")
                             {:env {"FOO" "foo"}}))))))

(deftest pass-stdin-correctly
  (testing "passes stdin stream to command"
    (is (= "foo" (:stdout (sh-utils/execute-command
                             (script-path "cat")
                             {:in (ByteArrayInputStream.
                                   (.getBytes "foo" "UTF-8"))}))))))

(deftest throws-exception-for-non-absolute-path
  (testing "Commands must be given using absolute paths"
    (is (thrown? IllegalArgumentException (sh-utils/execute-command "echo")))))

(deftest throws-exception-for-non-existent-file
  (testing "The given command must exist"
    (is (thrown? IllegalArgumentException (sh-utils/execute-command "/usr/bin/footest")))))

(deftest can-read-more-than-the-pipe-buffer
  (testing "Doesn't deadlock when reading more than the pipe can hold"
    (is (= 128000 (count (:stdout (sh-utils/execute-command
                                   (script-path "gen-output")
                                   {:args ["128000"]})))))))