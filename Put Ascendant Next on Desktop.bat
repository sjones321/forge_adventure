@echo off
rem Double-click this once from File Explorer. It puts a shortcut on your Desktop.
rem No terminal knowledge needed after that — use the Desktop icon.
setlocal
set "REPO=%~dp0"
set "REPO=%REPO:~0,-1%"
set "TARGET=%REPO%\play-ascendant-next.cmd"
set "DESKTOP=%USERPROFILE%\Desktop"
if not exist "%DESKTOP%" set "DESKTOP=%USERPROFILE%\OneDrive\Desktop"
if not exist "%TARGET%" (
  echo Could not find play-ascendant-next.cmd next to this file.
  echo Make sure you are in your forge_adventure folder.
  pause
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ws = New-Object -ComObject WScript.Shell; ^
   $sc = $ws.CreateShortcut('%DESKTOP%\Ascendant Next.lnk'); ^
   $sc.TargetPath = '%TARGET%'; ^
   $sc.WorkingDirectory = '%REPO%'; ^
   $sc.WindowStyle = 7; ^
   $sc.Description = 'Shandalar Ascendant Next - walk the plane'; ^
   $ico = Join-Path '%REPO%' 'forge-gui\res\adventure\Shandalar Ascendant\forge-adventure.ico'; ^
   if (Test-Path $ico) { $sc.IconLocation = $ico }; ^
   $sc.Save(); ^
   Write-Host 'Created:' (Join-Path '%DESKTOP%' 'Ascendant Next.lnk')"

if errorlevel 1 (
  echo Shortcut creation failed.
  pause
  exit /b 1
)

echo.
echo Done. Look on your Desktop for "Ascendant Next".
echo First launch may take a minute while it builds.
echo.
pause
