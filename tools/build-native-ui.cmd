@echo off
rem Builds the plymeld window as a Windows executable with GraalVM native-image: the Windows twin of build-native-ui.sh.
rem Two steps:
rem   agent [window args...]  runs the window under the tracing agent (a --screenshot of a job, or the live window:
rem                           close it when done), which merges the reachability metadata (JNI, reflection, resources)
rem                           into %META% - copy that back to plymeld-ui/src/main/resources/META-INF/native-image/com.plymeld/plymeld-ui
rem                           (the Linux agent run's metadata and this one's merge: each platform adds its own classes);
rem   build [native-image args...]  native-image over the UI's jars with Windows natives (./gradlew :plymeld-ui:windowsLib,
rem                           copied to %PLYMELD_LIB%) to %OUT%\plymeld-ui.exe.
rem Needs GRAALVM_HOME (a GraalVM for JDK 21 with native-image) and Visual Studio 2022's C++ build tools (VS_VCVARS), as
rem build-native.cmd.
setlocal enabledelayedexpansion
if "%GRAALVM_HOME%"=="" ( echo set GRAALVM_HOME to a GraalVM for JDK 21 with native-image & exit /b 1 )
if "%VS_VCVARS%"=="" set "VS_VCVARS=C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
if "%PLYMELD_LIB%"=="" set "LIBDIR=%TEMP%\plymeld-ui\lib"
if not "%PLYMELD_LIB%"=="" set "LIBDIR=%PLYMELD_LIB%"
if "%META%"=="" set "META=%TEMP%\plymeld-ui\native-image"
if "%OUT%"=="" set "OUT=%TEMP%\plymeld-ui\native"
rem the builder's heap: native-image sizes it from the memory free when it starts (7.8 GB once, out of memory in the
rem initialization), so it is set
if "%NATIVE_XMX%"=="" set "NATIVE_XMX=14g"
if not exist "%LIBDIR%\plymeld-ui-*.jar" ( echo no %LIBDIR%\plymeld-ui-*.jar: copy plymeld-ui\build\windows\lib there first & exit /b 1 )
set "MODE=%~1"
if "%MODE%"=="" set "MODE=build"
set "REST="
:args
shift
if "%~1"=="" goto run
set "REST=!REST! %1"
goto args
:run
if "%MODE%"=="agent" (
  if not exist "%META%" mkdir "%META%"
  "%GRAALVM_HOME%\bin\java.exe" "-agentlib:native-image-agent=config-merge-dir=%META%,config-write-period-secs=10" -cp "%LIBDIR%\*" plymeld.ui.MainKt !REST!
  exit /b !errorlevel!
)
if not "%MODE%"=="build" ( echo usage: %~nx0 agent [window args...] ^| build [native-image args...] & exit /b 2 )
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
rem the natives Skiko and LWJGL unpack from their jars, the bundled config, the machines, the version; slf4j and MINA's
rem file-system provider at build time (the latter sits in the image heap through the installed providers list)
rem a Windows GUI program (no console window beside it): the linker's /SUBSYSTEM:WINDOWS, with the C entry point kept
rem (/ENTRY:mainCRTStartup); Main.kt then sends System.out and System.err to a log file when there is no console
call "%GRAALVM_HOME%\bin\native-image.cmd" -J-Xmx%NATIVE_XMX% --no-fallback -O2 -H:+ReportExceptionStackTraces -H:+UnlockExperimentalVMOptions !ICONOPT! ^
  -H:NativeLinkerOption=/SUBSYSTEM:WINDOWS -H:NativeLinkerOption=/ENTRY:mainCRTStartup ^
  -H:IncludeResources=".*\.dll|.*\.sha256|.*\.sha1|config/.*\.toml|plymeld-version\.txt" ^
  -H:ConfigurationFileDirectories="%META%" --initialize-at-build-time=org.slf4j,org.apache.sshd.common.file.root.RootedFileSystemProvider ^
  -cp "!CP!" plymeld.ui.MainKt -o "%OUT%\plymeld-ui" !REST!
if not "!errorlevel!"=="0" exit /b 1
rem Skiko's AWT bridge loads %java.home%\bin\jawt.dll, and the executable sets java.home to its own directory (Main.kt):
rem native-image put jawt.dll beside the executable, so it goes under bin\ as well
if not exist "%OUT%\bin" mkdir "%OUT%\bin"
copy /y "%OUT%\jawt.dll" "%OUT%\bin\jawt.dll" >nul
