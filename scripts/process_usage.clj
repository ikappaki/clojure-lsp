(ns process-usage
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [selmer.parser :as s]))

;; doc
;; https://superuser.com/questions/618686/private-bytes-vs-working-set-in-process-explorer
;; https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.management/get-process

(comment

  ;;
  )
(def template
  "reset
set term dumb
set border lw 1
unset key
set title '{{title}}'
set tics scale 0.75
#set xtics 5 rotate
#set ytics 25
set xlabel '{{x-label}}'
# dumb fix https://github.com/xia2/screen19/issues/18
set ylabel '{{y-label}}' offset character {{y-label-offset}},0

set grid
plot '{{data-path}}' with linespoints pt 7 lc \"black\"")


(comment
(-> (plot)
    str/split-lines)
  ;;
  )

(defn analysis-1 [exec & args]
  (let [subdir "process-usage-logs"
        anchor (str (fs/path subdir ".anchor"))]
    (spit anchor "")
    (println :exec exec)
    (apply p/shell {:err *err*} "powershell" "-File" "scripts/process-usage.ps1" exec args)
    (let [output (-> (fs/modified-since anchor #{subdir})
                     first
                     str)]
      output
      ;; (println :spit (slurp output))
      )))

(def timestamp-kw (keyword "timestamp(ms)"))

(defn plot [path]
  (let [text (slurp path)
        [text-meta & table] (str/split-lines text)
        {:keys [exec interval-ms args] :as text-meta} (edn/read-string text-meta)
        exec (fs/file-name exec)
        [header & data] (map #(-> (str/trim %)
                                  (str/split #"\s+")
                                  (->> (map (fn [v] (or (parse-long v) (parse-double v) v))))) table)
        header (map keyword ;; #(let [x
                       ;;     (-> %
                       ;;         (str/replace #"\\(|\\)" "_")
                       ;;         keyword)]
                       ;; (println :x x)
                       ;; x)
                    header)
        data (map #(zipmap header %) data)
        tm-min (timestamp-kw (first data))
        data (let [tm-min (timestamp-kw (first data))]
               (map #(update % timestamp-kw - tm-min) data))]
    [header tm-min data]
    (fs/with-temp-dir
      [td {}]
      (let [out-data (str (fs/path td "data"))
            out-cfg (str (fs/path td "cfg"))
            x-axis timestamp-kw
            y-axis (keyword "WS(K)")]
        (spit out-data (str/join "\n"  (map #(str/join " " [(x-axis %) (y-axis %)]) data)))
        (spit out-cfg (s/render template {:data-path out-data ;;:min-ms min-ms :max-ms max-ms
                                          :title (str/join " " [exec args])
                                          :x-label x-axis
                                          :y-label y-axis
                                          :y-label-offset (/ (count (name y-axis)) 2)}))
        (spit (str path ".gp") (into [] data))
        ;; (println :temp (slurp out-cfg))
        ;; (println :mt text-meta :t header :d (first data))
        (-> (p/shell {:err nil :out :string} "gnuplot" out-cfg)
            :out)))))

(defn analysis []
  (pp/pprint (concat
               (for [exec (map #(str "D:/clj/issues/clsp-graalvm-22/" %)
                               ["clojure-lsp.21.3.exe" "clojure-lsp.21.3.upx.exe"
                                "clojure-lsp.22.2.exe" "clojure-lsp.22.2.upx.exe"
                                ])]
                 (do
                   (fs/delete-tree ".lsp/.cache")
                   (-> (analysis-1 exec "diagnostics" "--dry")
                       plot
                       str/split-lines)))
     ;;
               )))

(comment

  (analysis-1 "D:/clj/issues/clsp-graalvm-22/clojure-lsp.21.3.exe" "diagnostics" "--dry")
  (concat
    (for [exec (map #(str "D:/clj/issues/clsp-graalvm-22/" %)
                    ["clojure-lsp.21.3.exe" "clojure-lsp.21.3.upx.exe"
                     "clojure-lsp.22.2.exe" "clojure-lsp.22.2.upx.exe"                     
                     ])]

      (do
        (fs/delete-tree ".lsp/.cache")
        (-> (analysis-1 exec "diagnostics" "--dry")
            plot
            str/split-lines)))
    ;;
    )
  ;;
  )


(comment
  (-> (plot)
      str/split-lines)
  
  (p/process)
  (p/shell "gnuplot" "-c")
  (binding [*out* *err*]
    (println :hi))
  (p/shell "ls" "----")
  (xyz)
  ;;
  )
