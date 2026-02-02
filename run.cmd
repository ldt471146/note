@echo off
setlocal enabledelayedexpansion

REM Dependencies (download to lib\ on first run)
if not exist lib mkdir lib

set "MVN=https://repo1.maven.org/maven2"
set "FLATLAF_VER=3.4.1"
set "GSON_VER=2.11.0"
REM commonmark 0.22+ is built for newer Java. Keep Java 8-compatible version here.
set "COMMONMARK_VER=0.17.2"

call :ensureJar "flatlaf-%FLATLAF_VER%.jar" "%MVN%/com/formdev/flatlaf/%FLATLAF_VER%/flatlaf-%FLATLAF_VER%.jar"
call :ensureJar "gson-%GSON_VER%.jar" "%MVN%/com/google/code/gson/gson/%GSON_VER%/gson-%GSON_VER%.jar"
call :ensureJar "commonmark-%COMMONMARK_VER%.jar" "%MVN%/org/commonmark/commonmark/%COMMONMARK_VER%/commonmark-%COMMONMARK_VER%.jar"
call :ensureJar "commonmark-ext-gfm-strikethrough-%COMMONMARK_VER%.jar" "%MVN%/org/commonmark/commonmark-ext-gfm-strikethrough/%COMMONMARK_VER%/commonmark-ext-gfm-strikethrough-%COMMONMARK_VER%.jar"
call :ensureJar "commonmark-ext-gfm-tables-%COMMONMARK_VER%.jar" "%MVN%/org/commonmark/commonmark-ext-gfm-tables/%COMMONMARK_VER%/commonmark-ext-gfm-tables-%COMMONMARK_VER%.jar"

set "CP=lib\flatlaf-%FLATLAF_VER%.jar;lib\gson-%GSON_VER%.jar;lib\commonmark-%COMMONMARK_VER%.jar;lib\commonmark-ext-gfm-strikethrough-%COMMONMARK_VER%.jar;lib\commonmark-ext-gfm-tables-%COMMONMARK_VER%.jar"

REM Compile
if not exist out mkdir out
echo [compile] StickyNoteApp...
REM Build Java 8-compatible classfiles (classfile 52.0).
REM Prefer --release (JDK 9+). If you're using JDK 8, fallback to -source/-target 8.
dir /s /b src\*.java > out\sources.txt
javac -encoding UTF-8 --release 8 -cp "%CP%" -d out @out\sources.txt
if errorlevel 1 (
  echo [compile] javac --release not available or failed, retry with -source/-target 8...
  javac -encoding UTF-8 -source 8 -target 8 -cp "%CP%" -d out @out\sources.txt
  if errorlevel 1 (
    echo Compile failed.
    exit /b 1
  )
)

REM Run
echo [run] StickyNoteApp...
java -cp "out;%CP%" StickyNoteApp

exit /b 0

:ensureJar
set "JAR=lib\%~1"
set "URL=%~2"
if exist "%JAR%" exit /b 0
echo [deps] Download %~1
curl -f -L -o "%JAR%" "%URL%"
if errorlevel 1 (
  echo [deps] Download failed: %URL%
  exit /b 1
)
exit /b 0
