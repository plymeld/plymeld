@echo off
rem Builds the plymeld CLI as a Windows executable with GraalVM native-image (the Windows twin of
rem build-native.sh). Needs GRAALVM_HOME (a GraalVM for JDK 21 with native-image) and Visual Studio 2022's C++ build
rem tools (vcvars64.bat: the Community edition's default path unless VS_VCVARS names another).
rem Input: the CLI's installDist jars, copied to a Windows folder (LIB, default %TEMP%\plymeld-cli\lib: a UNC path to WSL
rem does not survive the link step; PLYMELD_LIB overrides, LIB itself is MSVC's). Output: %OUT%\plymeld.exe (default %TEMP%\plymeld-cli\native).
setlocal enabledelayedexpansion
if "%GRAALVM_HOME%"=="" ( echo set GRAALVM_HOME to a GraalVM for JDK 21 with native-image & exit /b 1 )
if "%VS_VCVARS%"=="" set "VS_VCVARS=C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
if "%PLYMELD_LIB%"=="" set "LIBDIR=%TEMP%\plymeld-cli\lib"
if not "%PLYMELD_LIB%"=="" set "LIBDIR=%PLYMELD_LIB%"
if "%OUT%"=="" set "OUT=%TEMP%\plymeld-cli\native"
if not exist "%LIBDIR%\plymeld-cli-*.jar" ( echo no %LIBDIR%\plymeld-cli-*.jar: copy plymeld-cli\build\install\plymeld\lib there first & exit /b 1 )
call "%VS_VCVARS%" >nul || ( echo vcvars64.bat failed: is Visual Studio 2022 with the C++ build tools installed? & exit /b 1 )
set "CP="
for %%f in ("%LIBDIR%\*.jar") do set "CP=!CP!%%f;"
set "CP=!CP:~0,-1!"
if not exist "%OUT%" mkdir "%OUT%"
rem the executable's icon (PLYMELD_ICO, default the set `./gradlew :plymeld-ui:icons` writes, copied beside the jars): a
rem resource script compiled by the Windows SDK's rc.exe (on PATH after vcvars64) and handed to the linker
if "%PLYMELD_ICO%"=="" set "PLYMELD_ICO=%LIBDIR%\..\icons\plymeld.ico"
set "ICONOPT="
if exist "%PLYMELD_ICO%" (
  copy /y "%PLYMELD_ICO%" "%OUT%\plymeld.ico" >nul
  echo 1 ICON "plymeld.ico"> "%OUT%\plymeld.rc"
  rc.exe /nologo /fo "%OUT%\plymeld.res" "%OUT%\plymeld.rc" || ( echo rc.exe failed: the executable gets no icon )
  if exist "%OUT%\plymeld.res" set "ICONOPT=-H:NativeLinkerOption=%OUT%\plymeld.res"
)
call "%GRAALVM_HOME%\bin\native-image.cmd" --no-fallback -O2 -H:+ReportExceptionStackTraces -H:IncludeResources="config/.*\.toml|plymeld-version\.txt" --initialize-at-build-time=org.slf4j,org.apache.sshd.common.file.root.RootedFileSystemProvider !ICONOPT! -cp "!CP!" plymeld.cli.MainKt -o "%OUT%\plymeld" %*
