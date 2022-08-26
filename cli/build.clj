(ns build
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.build.api :as b]
   [clojure.xml :as xml]))

(def lib 'com.github.clojure-lsp/clojure-lsp)
(def clojars-lib 'com.github.clojure-lsp/clojure-lsp-standalone)
(def current-version (string/trim (slurp (io/resource "CLOJURE_LSP_VERSION"))))
(def class-dir "target/classes")
(def basis {:project "deps.edn"})
(def uber-file (format "target/%s-standalone.jar" (name lib)))

(defn clean [_]
  (b/delete {:path "target"}))

(defn pom [opts]
  (b/write-pom {:target ""
                :lib clojars-lib
                :version current-version
                :basis (b/create-basis (update basis :aliases concat (:extra-aliases opts)))
                :src-dirs ["src" "../lib/src"]
                :resource-dirs ["resources"]
                :scm {:tag current-version}}))

(defn ^:private uber [opts]
  (clean opts)
  (pom opts)
  (b/copy-dir {:src-dirs ["src" "../lib/src" "resources" "../lib/resources"]
               :target-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :main 'clojure-lsp.main
           :basis (b/create-basis (update basis :aliases concat (:extra-aliases opts)))}))

(defn ^:private uber-aot [opts]
  (clean opts)
  (println "Building uberjar...")
  (let [basis (b/create-basis (update basis :aliases concat (:extra-aliases opts)))
        src-dirs (into ["src" "resources"] (:extra-dirs opts))]
    (b/copy-dir {:src-dirs src-dirs
                 :target-dir class-dir})
    (b/compile-clj {:basis basis
                    :src-dirs src-dirs
                    :java-opts ["-Xmx2g" "-server"]
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :main 'clojure-lsp.main
             :basis basis})))

(defn ^:private l4j-xml [jar outfile jre-path]
  (-> (with-out-str (xml/emit-element
                      {:tag :launch4jConfig, :attrs nil,
                       :content [{:tag :dontWrapJar, :attrs nil, :content ["false"]}
                                 {:tag :headerType, :attrs nil,  :content ["gui"]}
                                 {:tag :jar, :attrs nil, :content [jar;; "D:\\src\\clojure-lsp\\clojure-lsp-standalone.jar"
                                                                   ]}
                                 {:tag :outfile, :attrs nil, :content [outfile ;; "D:\\src\\clojure-lsp\\clojure-lsp.exe"
                                                                       ]}
                                 {:tag :errTitle, :attrs nil, :content nil}
                                 {:tag :cmdLine, :attrs nil, :content nil}
                                 {:tag :chdir, :attrs nil, :content ["."]}
                                 {:tag :priority, :attrs nil, :content ["normal"]}
                                 {:tag :downloadUrl, :attrs nil, :content ["http://java.com/download"]}
                                 {:tag :supportUrl, :attrs nil, :content nil}
                                 {:tag :stayAlive, :attrs nil, :content ["false"]}
                                 {:tag :restartOnCrash, :attrs nil, :content ["false"]}
                                 {:tag :manifest, :attrs nil, :content nil}
                                 {:tag :icon, :attrs nil, :content nil}
                                 {:tag :jre, :attrs nil, :content
                                  [{:tag :path, :attrs nil, :content [jre-path
                                                                      ;; "C:\\Program Files\\Java\\jdk-11.0.7"
                                                                      ]}
                                   {:tag :bundledJre64Bit, :attrs nil, :content ["true"]}
                                   {:tag :bundledJreAsFallback, :attrs nil, :content ["false"]}
                                   {:tag :minVersion, :attrs nil, :content nil}
                                   {:tag :maxVersion, :attrs nil, :content nil}
                                   {:tag :jdkPreference, :attrs nil, :content ["preferJre"]}
                                   {:tag :runtimeBits, :attrs nil, :content ["64/32"]}]}]}))
      (string/replace #"\r\n" "")))

(defn ^:private bin [opts]
  (println "Generating bin...")

  (if (fs/windows?)
    (if-let [l4j (or (some-> (System/getenv "LAUNCH4J_HOME") (fs/path "launch4jc.exe")
                             (#(when (fs/executable? %) %)))
                     (fs/which "launch4jc.exe"))]
      (let [jar (-> (fs/real-path uber-file) .toString)
            outfile (-> "../clojure-lsp.exe"  fs/absolutize fs/path .toString)
            java-home (System/getenv "JAVA_HOME")]
        (println :jre jar :out outfile :jh java-home)
        (fs/with-temp-dir
          [temp-dir]
          (println :l l4j)
          (let [l4jxml ;;"d:/src/clojure-lsp/cli/l4j.xml"
              ;;"c:/temp/io.xml"
                (-> (fs/path temp-dir "l4j.xml") .toString)]
            (println :path l4jxml)
            (spit l4jxml (l4j-xml jar outfile java-home))
            (println :out (slurp l4jxml))
            (let [{:keys [exit] :as _proc} @(p/process [(.toString l4j)
                                                        l4jxml]
                                                       {:dir "."
                                                        :out :inherit
                                                        :err :inherit})]
              (System/exit exit)))))
      ;; (println (pr-str (xml/parse "l4j.xml")))

      (throw (Exception. (str "Cannot find launch4j.exe in path. " (fs/exec-paths)))))

    ((requiring-resolve 'deps-bin.impl.bin/build-bin)
     {:jar uber-file
      :name "clojure-lsp"
      :jvm-opts (concat (:jvm-opts opts []) ["-Xmx2g" "-server"])
      :skip-realign true})))

(defn debug-jar [opts]
  (uber-aot (merge opts {:extra-aliases [:debug :test]
                         :extra-dirs ["dev"]})))

(def prod-jar uber-aot)

(defn prod-jar-for-native [opts]
  (uber-aot (merge opts {:extra-aliases [:native]})))

(defn debug-cli [opts]
  (uber-aot (merge opts {:extra-aliases [:debug :test]
                         :extra-dirs ["dev"]}))
  (bin {:jvm-opts ["-Djdk.attach.allowAttachSelf=true"
                   "-Dclojure.core.async.go-checking=true"]}))

(defn prod-cli [opts]
  (prod-jar opts)
  (bin {}))

(defn native-cli [opts]
  (println "Building native image...")
  (if-let [graal-home (System/getenv "GRAALVM_HOME")]
    (let [jar (or (System/getenv "CLOJURE_LSP_JAR")
                  (do (prod-jar-for-native opts)
                      uber-file))
          command (->> [(str (io/file graal-home "bin" "native-image"))
                        "-jar" jar
                        "clojure-lsp"
                        "-H:+ReportExceptionStackTraces"
                        "--verbose"
                        "--no-fallback"
                        "--native-image-info"
                        (or (System/getenv "CLOJURE_LSP_XMX")
                            "-J-Xmx8g")
                        (when (= "true" (System/getenv "CLOJURE_LSP_STATIC"))
                          ["--static"
                           (if (= "true" (System/getenv "CLOJURE_LSP_MUSL"))
                             ["--libc=musl" "-H:CCompilerOption=-Wl,-z,stack-size=2097152"]
                             ["-H:+StaticExecutableWithDynamicLibC"])])]
                       (flatten)
                       (remove nil?))
          {:keys [exit]} (b/process {:command-args command})]
      (when-not (= 0 exit)
        (System/exit exit)))
    (println "Set GRAALVM_HOME env")))

(defn deploy-clojars [opts]
  (uber opts)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   (merge {:installer :remote
           :artifact uber-file
           :pom-file "pom.xml"}
          opts))
  opts)
