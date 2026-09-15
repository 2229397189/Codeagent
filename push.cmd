@echo off
REM CodeAgent - push to GitHub (origin/main)
REM Run this on a machine with normal internet access.
REM No token is embedded here on purpose: it uses your local git credentials
REM (Windows Credential Manager, or you will be prompted for a PAT once).

echo Pushing CodeAgent to GitHub (origin/main)...
git -C "%~dp0" push origin main
if errorlevel 1 (
  echo.
  echo Push failed.
  echo If no credential is configured, set it once with:
  echo   git remote set-url origin https://YOUR_PAT@github.com/2229397189/Codeagent.git
  echo (replace YOUR_PAT with a GitHub Personal Access Token that has repo scope)
  echo Then run this script again.
  exit /b 1
)
echo PUSH OK
