@echo off
rem SET MYOUT=".\123.txt"
rem IF EXIST %MYOUT% DEL /F %MYOUT%
powershell -NoProfile -File d:/src/clojure-lsp/scripts/process-usage.ps1 d:/src/clojure-lsp/clojure-lsp.exe %*
