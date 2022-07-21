(ns clojure-lsp.classpath-test
  (:require
   [babashka.fs :as fs]
   [clojure-lsp.classpath :as classpath]
   [clojure-lsp.shared :as shared]
   [clojure-lsp.test-helper :as h]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]]))

(deftest default-project-specs-test
  (with-redefs [shared/windows-os? false]
    (testing "empty source-aliases"
      (is (h/assert-contains-submaps
            [{:project-path "project.clj"
              :classpath-cmd ["lein" "classpath"]}
             {:project-path "deps.edn"
              :classpath-cmd ["clojure" "-Spath"]}
             {:project-path "build.boot"
              :classpath-cmd ["boot" "show" "--fake-classpath"]}
             {:project-path "shadow-cljs.edn"
              :classpath-cmd ["npx" "shadow-cljs" "classpath"]}
             {:project-path "bb.edn"
              :classpath-cmd ["bb" "print-deps" "--format" "classpath"]}]
            (classpath/default-project-specs #{}))))
    (testing "single source-alias"
      (is (h/assert-contains-submaps
            [{:project-path "project.clj"
              :classpath-cmd ["lein" "with-profile" "+something" "classpath"]}
             {:project-path "deps.edn"
              :classpath-cmd ["clojure" "-A:something" "-Spath"]}
             {:project-path "build.boot"
              :classpath-cmd ["boot" "show" "--fake-classpath"]}
             {:project-path "shadow-cljs.edn"
              :classpath-cmd ["npx" "shadow-cljs" "classpath"]}
             {:project-path "bb.edn"
              :classpath-cmd ["bb" "print-deps" "--format" "classpath"]}]
            (classpath/default-project-specs #{:something}))))
    (testing "multiple source-aliases"
      (is (h/assert-contains-submaps
            [{:project-path "project.clj"
              :classpath-cmd ["lein" "with-profile" "+otherthing,+something" "classpath"]}
             {:project-path "deps.edn"
              :classpath-cmd ["clojure" "-A:otherthing:something" "-Spath"]}
             {:project-path "build.boot"
              :classpath-cmd ["boot" "show" "--fake-classpath"]}
             {:project-path "shadow-cljs.edn"
              :classpath-cmd ["npx" "shadow-cljs" "classpath"]}
             {:project-path "bb.edn"
              :classpath-cmd ["bb" "print-deps" "--format" "classpath"]}]
            (classpath/default-project-specs #{:something :otherthing}))))))

(defmacro never-skip-exec-tests
  "Return whether executable tests should never been skipped."
  []
  (System/getenv "CLOJURE_LSP_EXEC_TESTS_NEVER_SKIP"))

(defmacro deftest-if-exec
  "If EXEC is in path, run BODY as `(deftest \"EXEC\" BODY)`, otherwise
  create an empty `deftest` with a skip message"
  [exec body]

  (let [exec-str (str exec)]
    (if (or (never-skip-exec-tests) (fs/which exec-str))
      `(deftest ~exec
         ~body)

      `(deftest ~exec
         (testing (str "skipped - exec not in path: " ~exec-str)
           (is true))))))

(defmacro deftest-if-exec-use-PowerShell-on-windows
  "If EXEC is in the path, run BODY as `(deftest \"EXEC\" BODY)`,
  otherwise create an empty `deftest` with a skip message.

  When running on MS-Windows, try to invoke witha all PowerShell
  shells."
  [exec body]

  (if-not shared/windows-os?
    `(deftest-if-exec
       ~exec
       ~body)

    (let [exec-str (str exec)
          never-skip-exec-tests? (never-skip-exec-tests)]
      (apply list 'do
             ;; use any of the known PowerShell shells.
             (for [ps ["powershell" "pwsh"]]
               (let [test-sym (symbol (str "windows-" ps "-" exec))]
                 (if (or never-skip-exec-tests? (fs/which ps))
                   (if (or never-skip-exec-tests?
                           (= 0 (:exit (shell/sh ps "-NoProfile" "-Command" "Get-Command" exec-str))))
                     `(deftest ~test-sym
                        ;; only attempt to execute with the current PS
                        ;; shell.
                        (with-redefs [classpath/powershell-exec-path #(fs/which ~ps)])
                        ~body)

                     `(deftest ~test-sym
                        (testing ~(str "skipped - " exec " not found with " ps)
                          (is true))))

                   `(deftest ~test-sym
                      (testing ~(str "skipped - " ps " not in path")
                        (is true))))))))))


(defn make-components
  [dir project-filename content]
  "Create a PROJECT-FILENAME file at DIR with the given CONTENT and
return a clojure-lsp components reference to it."
  (let [project (.toString (fs/path dir project-filename))
        db {:project-root-uri (-> dir .toUri .toString)
            :settings {:project-specs
                       (classpath/default-project-specs #{})}}]
    (spit project content)
    {:db* (atom db)}))

(deftest-if-exec-use-PowerShell-on-windows
  lein

  (testing "scan lein project"
    (fs/with-temp-dir
      [temp-dir]
      (let [components (make-components temp-dir "project.clj"
                                        (pr-str '(defproject classpath-lein-test "any")))]
        (is (seq (classpath/scan-classpath! components)))))))

(deftest-if-exec-use-PowerShell-on-windows
  clojure

  (testing "scan clojure project"
    (fs/with-temp-dir
      [temp-dir]
      (let [components (make-components temp-dir "deps.edn" (pr-str {}))]
        (is (seq (classpath/scan-classpath! components)))))))

(deftest-if-exec
  boot

  (testing "scan boot project"
    (fs/with-temp-dir
      [temp-dir]
      (let [components (make-components temp-dir "build.boot" (pr-str {}))]
        (is (seq (classpath/scan-classpath! components)))))))

(deftest-if-exec
  npx

  (testing "scan shadow-cljs project"
    (fs/with-temp-dir
      [temp-dir]
      (let [components (make-components temp-dir "shadow-cljs.edn" (pr-str {}))]
        (is (seq (classpath/scan-classpath! components)))))))

(deftest-if-exec
  bb

  (testing "scan babashka project"
    (fs/with-temp-dir
      [temp-dir]
      (let [components (make-components temp-dir "bb.edn" (pr-str {}))]
        (is (seq (classpath/scan-classpath! components)))))))
