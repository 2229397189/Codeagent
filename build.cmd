@echo off
setlocal EnableDelayedExpansion
set "ROOT=%~dp0"
set "SRC=%ROOT%src\main\java"
set "TST=%ROOT%src\test\java"
set "OUT=%ROOT%out"

if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"

set "FILES="
for /r "%SRC%" %%f in (*.java) do set "FILES=!FILES! "%%f""
for /r "%TST%" %%f in (*.java) do set "FILES=!FILES! "%%f%""

echo [1/2] compiling...
javac -encoding UTF-8 -d "%OUT%" %FILES%
if errorlevel 1 (
  echo BUILD FAILED
  exit /b 1
)
echo BUILD OK

if exist "%TST%" (
  echo [2/2] running tests...
  java -cp "%OUT%" com.codeagent.test.AllTests
  if errorlevel 1 (
    echo TESTS FAILED
    exit /b 1
  )
  echo ALL TESTS PASSED
)
endlocal
