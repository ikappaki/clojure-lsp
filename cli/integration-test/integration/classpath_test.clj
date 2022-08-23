(ns integration.classpath-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [integration.fixture :as fixture]
   [integration.lsp :as lsp]))

(lsp/clean-after-test)

(defn getRootUri
  "Return the URI to the given SAMPLE-TEST-DIR."
  [sample-test-dir]
  (-> (fs/canonicalize ".")
      (fs/path "integration-test" sample-test-dir)
      .toUri .toString))

(deftest claspath-babashka
  (lsp/start-process!)
  (lsp/request! [:initialize
                 {:rootUri (getRootUri "sample-test-bb")
                  :initializationOptions fixture/default-init-options}])
  (let [{:keys [classpath] :as _res} (lsp/request! ["clojure/serverInfo/raw" {}])]
    (is (some #(str/includes? % "datomic-free") classpath))))

(deftest claspath-boot
  (lsp/start-process!)
  (lsp/request! [:initialize {:rootUri (getRootUri "sample-test-boot")
                              :initializationOptions fixture/default-init-options}])
  (let [{:keys [classpath] :as _res} (lsp/request! ["clojure/serverInfo/raw" {}])]
    (is (some #(str/includes? % "datomic-free") classpath))))

(deftest claspath-cli
  (lsp/start-process!)
  (lsp/request! (fixture/initialize-request
                 {:initializationOptions fixture/default-init-options}))
  (let [{:keys [classpath] :as _res} (lsp/request! ["clojure/serverInfo/raw" {}])]
    (is (some #(str/includes? % "datomic-free") classpath))))


(deftest claspath-lein
  (lsp/start-process!)
  (lsp/request! [:initialize {:rootUri (getRootUri "sample-test-lein")
                              :initializationOptions fixture/default-init-options}])
  (let [{:keys [classpath] :as _res} (lsp/request! ["clojure/serverInfo/raw" {}])]
    (is (some #(str/includes? % "datomic-free") classpath))))


(deftest claspath-shadow
  (lsp/start-process!)
  (lsp/request! [:initialize {:rootUri  (getRootUri "sample-test-shadow")
                              :initializationOptions fixture/default-init-options}])
  (let [{:keys [classpath] :as _res} (lsp/request! ["clojure/serverInfo/raw" {}])]
    (is (some #(str/includes? % "datomic-free") classpath))))
