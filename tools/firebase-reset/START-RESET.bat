@echo off
setlocal
title InChat Firebase Test Data Reset
cd /d "%~dp0"

echo.
echo ==========================================
echo       InChat Firebase Clean-Slate Reset
echo ==========================================
echo.
echo This tool first performs a DRY RUN.
echo It will NOT delete anything unless you type RESET.
echo.

if not exist "reset-test-data.mjs" (
  echo ERROR: Reset script is missing. Pull the latest main branch first.
  goto :finish
)

if not exist "package.json" (
  echo ERROR: package.json is missing.
  goto :finish
)

where node >nul 2>nul
if errorlevel 1 (
  echo ERROR: Node.js is not installed or not on PATH.
  echo Install the current Node.js LTS version, then run this file again.
  goto :finish
)

echo A file picker will open. Select the Firebase private-key JSON you downloaded.
echo Keep that file private. It will not be copied into your InChat project.
echo.
for /f "usebackq delims=" %%I in (`powershell -NoProfile -STA -Command "Add-Type -AssemblyName System.Windows.Forms; $d=New-Object System.Windows.Forms.OpenFileDialog; $d.Title='Select Firebase service-account JSON'; $d.Filter='JSON files (*.json)|*.json|All files (*.*)|*.*'; if($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK){[Console]::Write($d.FileName)}"`) do set "GOOGLE_APPLICATION_CREDENTIALS=%%I"

if not defined GOOGLE_APPLICATION_CREDENTIALS (
  echo.
  echo No file was selected. Nothing was changed.
  goto :finish
)
if not exist "%GOOGLE_APPLICATION_CREDENTIALS%" (
  echo.
  echo ERROR: The selected file could not be found.
  goto :finish
)

if not exist "..\..\app\google-services.json" (
  echo.
  echo Your local app\google-services.json was not found.
  echo Enter the exact Realtime Database URL from Firebase Console.
  set /p "FIREBASE_DATABASE_URL=Realtime Database URL: "
  if not defined FIREBASE_DATABASE_URL (
    echo ERROR: A Realtime Database URL is required.
    goto :finish
  )
)

echo.
echo Installing the reset tool dependencies...
call npm install --no-audit --no-fund
if errorlevel 1 (
  echo ERROR: npm install failed.
  goto :finish
)

echo.
echo ==========================================
echo                DRY RUN
echo ==========================================
node ".\reset-test-data.mjs"
if errorlevel 1 (
  echo.
  echo DRY RUN FAILED. Nothing was deleted.
  goto :finish
)

echo.
echo Review the project name, database URL, and account/data counts above.
echo This reset deletes ALL Firebase Auth accounts and InChat database data
echo in project inchat-24b8b. It cannot be undone.
echo.
set "RESET_CONFIRM="
set /p "RESET_CONFIRM=Type RESET to permanently erase this test project, or press Enter to cancel: "
if /I not "%RESET_CONFIRM%"=="RESET" (
  echo.
  echo Cancelled. Nothing was deleted.
  goto :finish
)

echo.
echo Running the destructive reset...
node ".\reset-test-data.mjs" --execute --confirm-project=inchat-24b8b
if errorlevel 1 (
  echo.
  echo Reset stopped with an error. Read the message above.
  goto :finish
)

echo.
echo Reset completed. Clear InChat app data or reinstall it on your test devices,
echo then register your two new test accounts.

:finish
echo.
pause
endlocal
