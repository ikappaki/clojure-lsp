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

(defmacro deftest-if-exec
  "If EXEC is in the path and ARCH matches the system architecture, run
  BODY as `(deftest \"EXEC\" BODY)`, otherwise create an empty
  `deftest` with a skip message.

  Possible values for ARCH are:

  :windows - for MS-Windows.

  :any-arch - any architecture."
  [exec arch body]

  (let [exec-str (str exec)]
    (if (and (= arch :windows)
             (not shared/windows-os?))
      `(deftest ~exec
         (testing (str "skipped - not on MS-Windows")
           (is true)))

      (if (fs/which exec-str)
        `(deftest ~exec
           ~body)

        `(deftest ~exec
           (testing (str "skipped - exec not in path: " ~exec-str)
             (is true)))))))

(comment
  (shell/sh "powershell" "-NoProfile" "-Command" "Get-Command" "leins")

  )
(defmacro deftest-if-exec-with-powershell
  "If EXEC is in the path and ARCH matches the system architecture, run
  BODY as `(deftest \"EXEC\" BODY)`, otherwise create an empty
  `deftest` with a skip message.

  Possible values for ARCH are:

  :windows - for MS-Windows.

  :any-arch - any architecture."
  [exec body]

  (if-not shared/windows-os?
    `(deftest-if-exec
       ~exec :any-arch
       ~body)

    (let [exec-str (str exec)]
      (apply list 'do
             (for [ps ["powershell" "pwsh"]]
               (let [test-sym (symbol (str "windows-" ps "-" exec))]
                 (if-not (fs/which ps)
                   `(deftest ~test-sym
                      (testing ~(str "skipped - " ps " not in path")
                        (is true)))

                   (if-not (= 0 (:exit (shell/sh ps "-NoProfile" "-Command" "Get-Command" exec-str)))
                     `(deftest ~test-sym
                        (testing ~(str "skipped - " exec " not found with " ps)
                          (is true)))

                     `(deftest ~test-sym
                        (with-redefs [classpath/powershell-exec-path #(fs/which ~ps)])
                        ~body)))))))))


(comment
  (macroexpand-1  '(deftest-if-exec-with-powershell
                     lein
                    1
                    ;; (fs/with-temp-dir
                    ;;    [temp-dir]
                    ;;    ;; (let [project (.toString (fs/path temp-dir "project.clj"))
                    ;;    ;;       content (pr-str '(defproject classpath-lein-test "any"))
                    ;;    ;;       db {:project-root-uri (-> temp-dir .toUri .toString)
                    ;;    ;;           :settings {:project-specs
                    ;;    ;;     (classpath/default-project-specs #{})}}]
                    ;;    ;;   (spit project content)
                    ;;    ;;   (is (seq (classpath/scan-classpath! {:db* (atom db)}))))
                    ;;    )
                     ) )


  )

(deftest-if-exec-with-powershell
  lein
  (testing "scan project"
    (fs/with-temp-dir
      [temp-dir]
      (let [project (.toString (fs/path temp-dir "project.clj"))
            content (pr-str '(defproject classpath-lein-test "any"))
            db {:project-root-uri (-> temp-dir .toUri .toString)
                :settings {:project-specs
                           (classpath/default-project-specs #{})}}]
        (spit project content)
        (is (seq (classpath/scan-classpath! {:db* (atom db)})))))))

(deftest-if-exec-with-powershell
  clojure

  (testing "scan project"
    (fs/with-temp-dir
      [temp-dir]
      (let [project (.toString (fs/path temp-dir "deps.edn"))
            content (pr-str {})
            db {:project-root-uri (-> temp-dir .toUri .toString)
                :settings {:project-specs (classpath/default-project-specs #{})}}]
        (spit project content)
        (is (seq (classpath/scan-classpath! {:db* (atom db)})))))))

;; (deftest-if-exec
;;   powershell :windows

;;   (testing "clojure tools scan project"
;;     (fs/with-temp-dir
;;       [temp-dir]
;;       (let [project (.toString (fs/path temp-dir "deps.edn"))
;;             content (pr-str {})
;;             db {:project-root-uri (-> temp-dir .toUri .toString)
;;                 :settings {:project-specs (classpath/default-project-specs #{})}}]
;;         (spit project content)
;;         (with-redefs [classpath/powershell-exec-path #(fs/which "powershell")]
;;           (is (seq (classpath/scan-classpath! {:db* (atom db)}))))))))

;; (deftest-if-exec
;;   pwsh :windows

;;   (testing "clojure tools scan project"
;;     (fs/with-temp-dir
;;       [temp-dir]
;;       (let [project (.toString (fs/path temp-dir "deps.edn"))
;;             content (pr-str {})
;;             db {:project-root-uri (-> temp-dir .toUri .toString)
;;                 :settings {:project-specs (classpath/default-project-specs #{})}}]
;;         (spit project content)
;;         (with-redefs [classpath/powershell-exec-path #(fs/which "pwsh")]
;;           (is (seq (classpath/scan-classpath! {:db* (atom db)}))))))))

(deftest-if-exec
  boot :any-arch

  (testing "scan project"
    (fs/with-temp-dir
      [temp-dir]
      (let [project (.toString (fs/path temp-dir "build.boot"))
            content (pr-str {})
            db {:project-root-uri (-> temp-dir .toUri .toString)
                :settings {:project-specs (classpath/default-project-specs #{})}}]
        (spit project content)
        (is (seq (classpath/scan-classpath! {:db* (atom db)})))))))

(deftest-if-exec
  npx :any-arch

  (testing "shadow-cljs scan project"
    (fs/with-temp-dir
      [temp-dir]
      (let [project (.toString (fs/path temp-dir "shadow-cljs.edn"))
            content (pr-str {})
            db {:project-root-uri (-> temp-dir .toUri .toString)
                :settings {:project-specs (classpath/default-project-specs #{})}}]
        (spit project content)
        (is (seq (classpath/scan-classpath! {:db* (atom db)})))))))

(deftest-if-exec
  bb :any-arch

  (testing "babashka scan project"
    (fs/with-temp-dir
      [temp-dir]
      (let [project (.toString (fs/path temp-dir "bb.edn"))
            content (pr-str {})
            db {:project-root-uri (-> temp-dir .toUri .toString)
                :settings {:project-specs (classpath/default-project-specs #{})}}]
        (spit project content)
        (is (seq (classpath/scan-classpath! {:db* (atom db)})))))))
