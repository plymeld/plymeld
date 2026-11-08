# tools/ — shell helpers

Shell and cmd helpers that drive building, testing, regression, and release. Anything that computes is written in
Kotlin, in the modules.

| script | what it does |
|---|---|
| `regress.sh check \| baseline \| record` | byte-for-byte regression of the CLI's g-code, the version that wrote it and the source block's line numbers left out (CONTRIBUTING.md); a case that differs only in its source block (a bundled default added or changed) is reported as `config` with the changed lines and does not fail; CI runs `check --base` on every pull request |
| `plate.sh <kind> ... -o PLATE.gcode [--render]` | a calibration plate in one command |
| `install-git-hooks.sh` | once per clone: points git's `core.hooksPath` at `git-hooks/`, for every worktree |
| `git-hooks/pre-commit` | the commit check: the commit's tree, in a check worktree of its own, must compile (`gradlew testClasses`; `PLYMELD_COMMIT_CHECK=tests`: `gradlew fastTest`); the tests run in CI |
| `git-hooks/post-commit` | with `PLYMELD_SLOW_CHECK=1` only: queues the commit for the slow suites (`slow-check.sh enqueue`) |
| `slow-check.sh` | the slow suites (`slow-suites.txt`, `gradlew slowTest`) locally after a commit, when asked for: one at a time, queued, each at its commit, its result under its hash; `status` shows the queue and the last results |
| `slow-suites.txt` | which test classes are slow: `gradlew slowTest` runs them, `gradlew fastTest` leaves them out (CI runs both) |
| `git-hooks/commit-msg` | "text matches reality": a commit changing `src/main` code with no doc, `*.md` or schema change needs `no-docs: <reason>` in its message |
| `bench_cli.sh <model> <settings>` | whole-process wall time, JVM launcher against the native binary, and that both write the same g-code |
| `build-native.sh` / `.cmd`, `build-native-ui.sh` / `.cmd` | GraalVM native executables of the CLI and the window |
| `package-velopack.cmd` | installers, portable zip, AppImage and update feed with Velopack |
| `windows-ui-tests.ps1` | the window's tests (`WindowDriveTest`, `WindowRunsTest`) on a Windows JDK, Windows' own natives, from a WSL build (`gradlew :plymeld-ui:windowsTestLib`) with JUnit's console launcher |

A version tag on `main` (`v0.YYYYMMDD.N`, the release's date in UTC) runs these in CI (`.github/workflows/release.yml`),
built with that version (`-Pplymeld.version`; any other build is `0.0.0-dev`): the native builds on Linux and Windows
runners, Velopack's packages for each, and the Linux command line's tar, uploaded to a draft GitHub release. A release
needs no commit of its own. Its Gradle distribution and dependencies come from caches kept on `main` for each runner
(`.github/workflows/release-cache.yml`, run when the Gradle build files change and twice a week), so a release does not
depend on downloading them.

The independent reference data the tests compare against was computed once, outside plymeld, and is not regenerated
here: the 3DBenchy's mesh facts (`test-models/3DBenchy.stl.reference.json`) and the geometry kernel's answers on random
polygons (`plymeld-geom/src/test/resources/geom/*.reference.txt`).
