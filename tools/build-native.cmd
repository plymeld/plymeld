@echo off
rem Builds the plymeld CLI as a Windows executable with GraalVM native-image (the Windows twin of
rem build-native.sh). Needs GRAALVM_HOME (a GraalVM for JDK 21 with native-image) and Visual Studio 2022's C++ build
rem tools (vcvars64.bat: the Community edition's default path unless VS_VCVARS names another).
rem Input: the CLI's installDist jars, copied to a Windows folder (LIB, default %TEMP%\plymeld-cli\lib: a UNC path to WSL
rem does not survive the link step; PLYMELD_LIB overrides, LIB itself is MSVC's). Output: %OUT%\plymeld.exe (default %TEMP%\plymeld-cli\native).
rem The reachability metadata is in the CLI's jar (META-INF/native-image), traced on Linux by tools/native-smoke.sh;
rem Windows' own imaging library may reach classes that trace does not record: the release runs every command with the
rem executable this builds (tools/native-smoke.sh check, under Git's bash).
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
rem arguments reach plymeld as typed: native-image links a Windows executable with the C runtime's wildcard expansion
rem (setargv.obj) unless -H:-EnableWildcardExpansion, which would turn an unquoted `-cf config\0*.toml` into several
rem words before plymeld reads it; plymeld expands its own globs, the same way on every platform
call "%GRAALVM_HOME%\bin\native-image.cmd" --no-fallback -O2 -H:+ReportExceptionStackTraces -H:+UnlockExperimentalVMOptions -H:-EnableWildcardExpansion -H:-UnlockExperimentalVMOptions -H:IncludeResources="config/.*\.toml|plymeld-version\.txt|plymeld-update-feed\.txt" --initialize-at-build-time=org.slf4j,org.apache.sshd.common.file.root.RootedFileSystemProvider !ICONOPT! -cp "!CP!" plymeld.cli.MainKt -o "%OUT%\plymeld" %* || exit /b 1
rem the executable's own folder is its java.home (Main.kt): the JDK's fonts read their configuration from its lib\
rem (fontconfig.bfc, which the JDK ships on Windows), so lib\ goes beside the executable with that file
if not exist "%OUT%\lib" mkdir "%OUT%\lib"
if exist "%GRAALVM_HOME%\lib\fontconfig.bfc" copy /y "%GRAALVM_HOME%\lib\fontconfig.bfc" "%OUT%\lib\" >nul
