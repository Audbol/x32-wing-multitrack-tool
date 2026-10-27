@echo off
rem Starts the X32 / M32 / WING Multitrack Tool from a source build (run build.ps1 first).
rem With arguments it runs the command line version.
set "JAR="
for %%f in ("%~dp0dist\X32WingTool-*.jar") do set "JAR=%%f"
if not defined JAR (
  echo No jar in dist\ - run build.ps1 first.
  exit /b 1
)
if "%~1"=="" (
  start "" javaw -jar "%JAR%"
) else (
  java -jar "%JAR%" %*
)
