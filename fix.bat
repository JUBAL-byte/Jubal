@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo.
echo   Jubal - fixing the workflow layout
echo   ==================================
echo.

if not exist "jubal-lite\jubal-lite-workflow.yml" (
  echo   ERROR: jubal-lite\jubal-lite-workflow.yml not found.
  echo   Put this file in the project root next to push.bat and run it again.
  echo.
  pause
  exit /b 1
)

echo   Copying the workflow to .github\workflows\jubal-lite.yml
copy /y "jubal-lite\jubal-lite-workflow.yml" ".github\workflows\jubal-lite.yml" >nul
if errorlevel 1 (
  echo   Copy failed.
) else (
  echo   OK
)

echo   Removing .github\workflows\workflows
if exist ".github\workflows\workflows" (
  rmdir /s /q ".github\workflows\workflows"
  if exist ".github\workflows\workflows" (echo   FAILED) else (echo   OK)
) else (
  echo   already gone
)

echo   Removing .github\workflows\jubal-lite-v1
if exist ".github\workflows\jubal-lite-v1" (
  rmdir /s /q ".github\workflows\jubal-lite-v1"
  if exist ".github\workflows\jubal-lite-v1" (echo   FAILED) else (echo   OK)
) else (
  echo   already gone
)

echo.
echo   Result - contents of .github\workflows:
echo.
dir /b ".github\workflows"

echo.
echo   Done. If every line above says OK, run push.bat next.
echo.
pause
