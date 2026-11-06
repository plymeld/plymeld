# The window's tests (plymeld-ui's WindowDriveTest, WindowRunsTest) run on a Windows JDK, against Windows' own natives (Skia,
# OpenGL), from a build made under WSL:
#
#   ./gradlew :plymeld-ui:windowsTestLib        (under WSL: the test classes and jars with Windows natives)
#   powershell.exe -ExecutionPolicy Bypass -File tools\windows-ui-tests.ps1 -Lib <test-lib> -WorkDir <plymeld-ui> -Launcher <jar>
#
# -Lib: plymeld-ui/build/windows/test-lib as Windows sees it (\\wsl.localhost\...); copied to %TEMP% first, as jars load slowly
# over the share. -WorkDir: plymeld-ui as Windows sees it (the tests read ../test-models and write build/renders there).
# -Launcher: JUnit's junit-platform-console-standalone jar, the version the tests' JUnit Platform has. -Jdk: a JDK 21.
# -Select: the test classes. The results go to <WorkDir>/build/windows-test-results.
param(
    [Parameter(Mandatory = $true)][string]$Lib,
    [Parameter(Mandatory = $true)][string]$WorkDir,
    [Parameter(Mandatory = $true)][string]$Launcher,
    [string]$Jdk = "$env:USERPROFILE\.jdks\ms-21.0.7",
    [string[]]$Select = @("plymeld.ui.WindowDriveTest")
)
$ErrorActionPreference = "Stop"
$local = Join-Path $env:TEMP "plymeld-ui-tests"
if (Test-Path $local) { Remove-Item -Recurse -Force $local }
Copy-Item -Recurse $Lib $local
Copy-Item $Launcher (Join-Path $local "launcher.jar")
Set-Location $WorkDir
# as the Gradle test tasks give them: plymeld's folders of their own (never the user's), and the coroutine test runner's
# limit raised past a slice
$env:PLYMELD_HOME = Join-Path $local "home"
$cp = "$local\launcher.jar;$local\*;$local\classes"
$sel = @($Select | ForEach-Object { "--select-class=$_" })
& "$Jdk\bin\java.exe" "-Dkotlinx.coroutines.test.default_timeout=20m" -cp $cp org.junit.platform.console.ConsoleLauncher execute @sel --reports-dir "$WorkDir\build\windows-test-results" --details=tree
exit $LASTEXITCODE
