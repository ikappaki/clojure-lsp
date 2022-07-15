(ns clojure-lsp.classpath
  (:require
   [babashka.fs :as fs]
   [clojure-lsp.settings :as settings]
   [clojure-lsp.shared :as shared]
   [clojure-lsp.source-paths :as source-paths]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.set :as set]
   [clojure.string :as string]
   [lsp4clj.protocols.logger :as logger]
   [lsp4clj.protocols.producer :as producer])
  (:import
   (java.io ByteArrayOutputStream)
   (java.security MessageDigest)))

(set! *warn-on-reflection* true)

(defn ^:private md5 [^java.io.File file]
  (let [bytes'
        (with-open [xin (io/input-stream file)
                    xout (ByteArrayOutputStream.)]
          (io/copy xin xout)
          (.toByteArray xout))
        algorithm (MessageDigest/getInstance "MD5")
        raw (.digest algorithm bytes')]
    (format "%032x" (BigInteger. 1 raw))))

(defn ^:private valid-project-spec? [root-path {:keys [project-path]}]
  (let [project-file (shared/to-file root-path project-path)]
    (shared/file-exists? project-file)))

(defn ^:private project-root->project-dep-files [project-root dep-file-path settings]
  (let [project-dep-file (io/file project-root dep-file-path)]
    (if (string/ends-with? (str project-dep-file) "deps.edn")
      (if-let [local-roots (seq (source-paths/deps-file->local-roots project-dep-file settings))]
        (concat [project-dep-file]
                (->> local-roots
                     (map #(shared/relativize-filepath % project-root))
                     (map #(io/file project-root % "deps.edn"))
                     (filter shared/file-exists?)))
        [project-dep-file])
      [project-dep-file])))

(defn project-specs->hash [root-path settings]
  (->> (:project-specs settings)
       (filter (partial valid-project-spec? root-path))
       (map (fn [{:keys [project-path]}]
              (map md5 (project-root->project-dep-files (str root-path) project-path settings))))
       flatten
       (reduce str)))

(defn ^:private lookup-classpath! [root-path {:keys [classpath-cmd env]}]
  (let [command (string/join " " classpath-cmd)]
    (logger/info (format "Finding classpath via `%s`" command))
    (try
      (let [sep (re-pattern (System/getProperty "path.separator"))
            {:keys [exit out err]} (apply shell/sh (into classpath-cmd
                                                         (cond-> [:dir (str root-path)]
                                                           env (conj :env (merge {} (System/getenv) env)))))]
        (if (= 0 exit)
          (let [paths (-> out
                          string/split-lines
                          last
                          string/trim-newline
                          (string/split sep))]
            (logger/debug "Classpath found, paths: " paths)
            {:command command
             :paths (set paths)})
          {:command command
           :error err}))
      (catch Exception e
        {:command command
         :error (.getMessage e)}))))

(defn scan-classpath! [{:keys [db* producer]}]
  (let [db @db*
        root-path (shared/uri->path (:project-root-uri db))]
    (->> (settings/get db [:project-specs])
         (filter (partial valid-project-spec? root-path))
         (map #(lookup-classpath! root-path %))
         (map (fn [{:keys [command error paths]}]
                (when error
                  (logger/error (format "Error while looking up classpath info in %s. Error: %s" (str root-path) error))
                  (producer/show-message producer (format "Classpath lookup failed when running `%s`. Some features may not work properly. Error: %s" command error) :error error))
                paths))
         (reduce set/union))))

(defn ^:private classpath-cmd->windows-safe-classpath-cmd
  [classpath]
  (if shared/windows-os?
    (into ["pwsh" "-NoProfile" "-Command"] classpath)
    classpath))

(defn ^:private powershell-exec-path
  "Return the local path to a PowerShell exec file.

  Look up order is:

  pwsh - the name of the executable in PowerShell v6 or later.
  powershell - the name of the executable prior to v6."
  []
  (or (fs/which "pwsh")
      (fs/which "powershell")))

(defn ^:private classpath-cmd->windows-compatible
  "Return CLASSPATH-CMD. If running on MS-Windows, update it to be invokable from windows.

  It uses `powershell-exec-path` to locate the PowerShell exec file
  for invoking the Clojure cli script on MS-Windows."
  [classpath-cmd]
  (if shared/windows-os?
    (let [cmd (first classpath-cmd)]
      (case cmd
        "clojure"
        ;; The Clojure cli tools command is a PowerShell script that
        ;; must be invoked from PowerShell.
        (if-let [psh (powershell-exec-path)]
          (into [(fs/file-name psh) "-NoProfile" "-Command"] classpath-cmd)
          classpath-cmd)

        ;; else, update executable with extension so that can be
        ;; invoked by the shell command.
        (update classpath-cmd 0 #(or (some-> (fs/which %1) fs/file-name) %1))))
    classpath-cmd))

(defn ^:private lein-source-aliases [source-aliases]
  (some->> source-aliases
           (map #(str "+" (name %)))
           seq
           (string/join ",")
           (conj ["with-profile"])))

(defn ^:private deps-source-aliases [source-aliases]
  (some->> source-aliases
           (map name)
           seq
           (string/join ":")
           (str "-A:")
           vector))

(defn default-project-specs [source-aliases]
  (->> [{:project-path "project.clj"
         :classpath-cmd (->> ["lein" (lein-source-aliases source-aliases) "classpath"]
                             flatten
                             (remove nil?)
                             vec)}
        {:project-path "deps.edn"
         :classpath-cmd (->> ["clojure" (deps-source-aliases source-aliases) "-Spath"]
                             flatten
                             (remove nil?)
                             vec)}
        {:project-path "build.boot"
         :classpath-cmd ["boot" "show" "--fake-classpath"]}
        {:project-path "shadow-cljs.edn"
         :classpath-cmd ["npx" "shadow-cljs" "classpath"]}
        {:project-path "bb.edn"
         :classpath-cmd ["bb" "print-deps" "--format" "classpath"]}]
       (map #(update % :classpath-cmd classpath-cmd->windows-compatible))))
