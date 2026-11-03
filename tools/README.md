# tools/ — shell helpers

Shell and cmd helpers that drive building, testing, regression, and release. Anything that computes is written in
Kotlin, in the modules.

| script | what it does |
|---|---|
| `regress.sh check \| baseline \| record` | byte-for-byte regression of the CLI's g-code (CONTRIBUTING.md); CI runs `check --base` on every pull request |
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

A version tag (`v` and `plymeld.version` from gradle.properties) runs these in CI (`.github/workflows/release.yml`): the
native builds on Linux and Windows runners, Velopack's packages for each, and the Linux command line's tar, uploaded to a
draft GitHub release.

The independent reference data the tests compare against was computed once, outside plymeld, and is not regenerated
here: the 3DBenchy's mesh facts (`test-models/3DBenchy.stl.reference.json`) and the geometry kernel's answers on random
polygons (`plymeld-geom/src/test/resources/geom/*.reference.txt`).
