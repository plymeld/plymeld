@echo off
rem Packages plymeld with Velopack: from the native executables, `vpk pack` makes the Windows installer
rem (plymeld-win-setup.exe), the portable zip (plymeld-win-portable.zip), the full and delta packages and the feed
rem (releases.win.json); `vpk [linux] pack` makes the self-updating AppImage from the Linux build, on this Windows
rem machine too (Velopack cross-builds Windows and Linux packages from any OS; macOS only on a Mac), and the Linux
rem command line's own download (plymeld-linux-x64.tar.gz) is copied beside it.
rem Needs the .NET SDK and `dotnet tool install -g vpk`. Inputs (copied to Windows folders first; a UNC path to WSL
rem does not do):
rem   PLYMELD_WIN    the Windows build: plymeld-ui.exe with its DLLs and bin\jawt.dll at the root, the CLI plymeld.exe
rem                 with ITS OWN DLLs under cli\ (native-image's jvm.dll and java.dll are stubs made per image: the
rem                 window's and the CLI's differ, and one folder for both crashed the window). Velopack's launcher at
rem                 the install root is also plymeld.exe (the package id's name) and opens the window; the window's
rem                 install hook puts current\cli on the user's PATH (CliPath.kt), so `plymeld` in a terminal is the CLI
rem                 (default %TEMP%\plymeld-pack\win)
rem   PLYMELD_LINUX  the Linux build: plymeld-ui, the CLI plymeld, their .so files, lib\libjawt.so
rem                 (default %TEMP%\plymeld-pack\linux)
rem   PLYMELD_LINUX_CLI  the Linux command line's own download, plymeld-linux-x64.tar.gz as tools/build-native.sh makes
rem                 it on Linux (an AppImage opens the window only); copied beside the AppImage
rem                 (default %TEMP%\plymeld-pack\plymeld-linux-x64.tar.gz)
rem   PLYMELD_ICONS  the icon set `./gradlew :plymeld-ui:icons` writes (default %TEMP%\plymeld-pack\icons)
rem   PLYMELD_FEED   the URL the installed window checks for updates (written into the package as plymeld-update-feed.txt).
rem                 Required: an install keeps the URL its package carries. PLYMELD_FEED=none
rem                 packages a build that never checks, for testing only.
rem Usage: package-velopack.cmd VERSION [win|linux|all]   (VERSION in semver 2, e.g. 0.20261026.0, the date as one number; default all)
setlocal enabledelayedexpansion
if "%~1"=="" ( echo usage: %~nx0 VERSION [win^|linux^|all] & exit /b 2 )
set "VER=%~1"
set "WHAT=%~2"
if "%WHAT%"=="" set "WHAT=all"
if "%PLYMELD_WIN%"=="" set "PLYMELD_WIN=%TEMP%\plymeld-pack\win"
if "%PLYMELD_LINUX%"=="" set "PLYMELD_LINUX=%TEMP%\plymeld-pack\linux"
if "%PLYMELD_ICONS%"=="" set "PLYMELD_ICONS=%TEMP%\plymeld-pack\icons"
if "%OUT%"=="" set "OUT=%TEMP%\plymeld-pack\releases"
rem vpk on PATH, or the per-user .NET install's tools folder (dotnet-install.ps1 into %USERPROFILE%\.dotnet, no elevation)
set "VPK=vpk"
where vpk >nul 2>nul || (
  if exist "%USERPROFILE%\.dotnet\tools\vpk.exe" ( set "VPK=%USERPROFILE%\.dotnet\tools\vpk.exe" & set "DOTNET_ROOT=%USERPROFILE%\.dotnet" ) else ( echo vpk not found: dotnet tool install -g vpk ^(needs the .NET SDK^) & exit /b 1 )
)
if "%PLYMELD_FEED%"=="" ( echo PLYMELD_FEED is not set: the update feed's URL ^(or none, for a test package that never checks^) & exit /b 1 )
if /i "%PLYMELD_FEED%"=="none" (
  set "PLYMELD_FEED="
  rem a feed file left in a build folder by an earlier run would still be packaged
  if exist "%PLYMELD_WIN%\plymeld-update-feed.txt" del "%PLYMELD_WIN%\plymeld-update-feed.txt"
  if exist "%PLYMELD_LINUX%\plymeld-update-feed.txt" del "%PLYMELD_LINUX%\plymeld-update-feed.txt"
)
if not "%PLYMELD_FEED%"=="" (
  if exist "%PLYMELD_WIN%" echo %PLYMELD_FEED%> "%PLYMELD_WIN%\plymeld-update-feed.txt"
  if exist "%PLYMELD_LINUX%" echo %PLYMELD_FEED%> "%PLYMELD_LINUX%\plymeld-update-feed.txt"
)
if not exist "%OUT%" mkdir "%OUT%"
if "%WHAT%"=="linux" goto linux
if not exist "%PLYMELD_WIN%\plymeld-ui.exe" ( echo no %PLYMELD_WIN%\plymeld-ui.exe: copy the Windows native build there first & exit /b 1 )
if not exist "%PLYMELD_WIN%\cli\plymeld.exe" ( echo no %PLYMELD_WIN%\cli\plymeld.exe: the CLI goes under cli\, named plymeld.exe ^(not plymeld-cli.exe^) & exit /b 1 )
"%VPK%" [win] pack --runtime win-x64 --framework vcredist143-x64 --packId plymeld --packTitle plymeld --packVersion %VER% --packDir "%PLYMELD_WIN%" --mainExe plymeld-ui.exe --icon "%PLYMELD_ICONS%\plymeld.ico" --channel win --outputDir "%OUT%" || exit /b 1
rem Velopack names them -Setup.exe and -Portable.zip; plymeld's file names are lower case (the updater reads only the
rem packages and the feed, never these two)
ren "%OUT%\plymeld-win-Setup.exe" plymeld-win-setup.exe || exit /b 1
ren "%OUT%\plymeld-win-Portable.zip" plymeld-win-portable.zip || exit /b 1
if "%WHAT%"=="win" goto done
:linux
if not exist "%PLYMELD_LINUX%\plymeld-ui" ( echo no %PLYMELD_LINUX%\plymeld-ui: copy the Linux native build there first & exit /b 1 )
if "%PLYMELD_LINUX_CLI%"=="" set "PLYMELD_LINUX_CLI=%TEMP%\plymeld-pack\plymeld-linux-x64.tar.gz"
if not exist "%PLYMELD_LINUX_CLI%" ( echo no %PLYMELD_LINUX_CLI%: the Linux command line's download, made by tools/build-native.sh on Linux & exit /b 1 )
copy /y "%PLYMELD_LINUX_CLI%" "%OUT%\plymeld-linux-x64.tar.gz" >nul || exit /b 1
"%VPK%" [linux] pack --packId plymeld --packTitle plymeld --packVersion %VER% --packDir "%PLYMELD_LINUX%" --mainExe plymeld-ui --icon "%PLYMELD_ICONS%\plymeld-256.png" --channel linux --outputDir "%OUT%" || exit /b 1
:done
echo packaged %VER% into %OUT%
