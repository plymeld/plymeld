# Contributing to plymeld

Thanks for your interest. Bug reports, ideas, and pull requests are all welcome.

- **A bug or a question:** open an issue. For a slicing problem, include the plymeld version
  (`plymeld --version`), the command, and the smallest model and config that show it, if you are free to
  share them.
- **A security problem:** do not open an issue; see [SECURITY.md](SECURITY.md).
- **A change:** for anything beyond a small fix, open an issue first so the approach can be agreed before
  you spend time on it. New dependencies and new technology always need agreement first (below).

Everyone taking part follows the [code of conduct](CODE_OF_CONDUCT.md); report a concern to
[conduct@plymeld.org](mailto:conduct@plymeld.org).

**The contributor license agreement (CLA).** plymeld is licensed under the AGPL-3.0-only ([LICENSE](LICENSE),
[NOTICE](NOTICE)) and is also offered under a commercial license. To make that possible, every
contribution comes under a CLA: you keep the copyright and grant Russell Tech Strategy LLC a license to
your contribution, including to relicense it. Read the [individual CLA](legal/individual-cla.md). On your
first pull request, the CLA check comments with the line to reply with; replying with it accepts the CLA
once, for every contribution, and records your account name, the date, and the pull request. Every author
of the pull request's commits accepts, so commit with an e-mail address that is on your GitHub account. A
company contributing on its employees' behalf signs the [corporate CLA](legal/corporate-cla.md) and emails
it to legal@plymeld.org; its employees' accounts are then added to the check. Nothing is needed to use,
fork, or build on plymeld, only to have a contribution merged here.

## Build

Requires **JDK 21**. The Gradle wrapper fetches Gradle, Kotlin, and every dependency.

```
./gradlew build                          # compile and run every test (once, at the end of a change)
./gradlew :plymeld-cli:installDist        # the CLI -> plymeld-cli/build/install/plymeld/bin/plymeld
./gradlew :plymeld-cli:run --args="--version"
./gradlew :plymeld-ui:run                 # the desktop window
tools/build-native.sh                    # a GraalVM native binary (after installDist; needs GraalVM 21, GRAALVM_HOME)
```

Gradle's build cache and configuration cache are on (`gradle.properties`), so a build with no change
takes a few seconds, and a new worktree takes its compiled classes from the shared cache. **Run one Gradle
at a time per checkout**: two concurrent runs wipe each other's test results.

## Test

```
./gradlew :plymeld-check:test --tests 'plymeld.check.CalibrationPlateTest'   # while iterating: one module, one class or pattern
./gradlew build                                                          # everything, once, at the end
```

- **While iterating, run only the tests your change touches** (`:<module>:test --tests '<class or pattern>'`).
- **Tests read only what is in the repository**: models built in code, the models in `test-models/`
  ([test-models/MANIFEST.md](test-models/MANIFEST.md)), and test resources. Never someone's own model or settings.
- **Review pictures** from tests go to `<module>/build/renders/`. `-Pplymeld.refreshRenders` writes them into
  `docs/renders/` instead, for the documentation.
- **Warnings are errors** (`allWarningsAsErrors`). Keep it that way.

### The commit check (a git hook)

```
tools/install-git-hooks.sh               # once per clone: git's core.hooksPath -> tools/git-hooks
```

Every commit then runs `tools/git-hooks/pre-commit`: the tree being committed (the index, not the working
tree) is checked out into a check worktree of its own (`~/.cache/plymeld-commit-check/`) and must compile
there, every module's main and test code (`gradlew testClasses`). A commit that does not compile is
refused, with the errors and the log's path. The tests run in CI (`.github/workflows/ci.yml`) on every push
and pull request: `gradlew fastTest` (every suite but the slow ones listed in `tools/slow-suites.txt`),
then `gradlew slowTest` (those alone). To run them locally as well: `PLYMELD_COMMIT_CHECK=tests` makes the
pre-commit run `gradlew fastTest`, and `PLYMELD_SLOW_CHECK=1` makes `tools/git-hooks/post-commit` queue the
commit for the slow suites, one commit at a time (`tools/slow-check.sh status`). `gradlew test` runs
everything. And `tools/git-hooks/commit-msg` refuses a commit that changes `src/main` code with no doc,
`*.md`, or schema change, unless its message says why with `no-docs: <reason>` (see "Text matches reality"
below).

### Regression: byte-for-byte output

`tools/regress.sh` slices a fixed set of cases with your tree and compares the g-code byte for byte with a
baseline:

```
tools/regress.sh check                    # this tree vs the baseline of its merge-base with origin/main
tools/regress.sh check --base <commit>    # this tree vs <commit>'s baseline
tools/regress.sh baseline [<commit>]      # record a commit's baseline once
```

Each case slices a model in `test-models/` with the test config in `test-models/config/` and a plan file in `tools/regress/`, so
nothing is decided again. After a deliberate change to the decisions, `tools/regress.sh plans` writes the plans again.
Baselines are built in a temporary worktree and recorded once per commit in `~/.cache/plymeld-regress/`;
`PLYMELD_REGRESS_DIR` overrides the folder. Every difference the script reports is either explained as intended or
fixed. CI runs `tools/regress.sh check --base` against every pull request's base ("Same g-code as the base"); a
deliberate change to the output fails it, and the pull request explains each case that differs (the g-code of both
is kept with the failed run).

### A calibration plate in one command

```
PLYMELD_CONFIG='/path/to/config/*.toml' tools/plate.sh melt --filament pla-generic --plate pei -o melt.gcode --render
```

`tools/plate.sh` runs `installDist`, then `plymeld calibrate <kind>` with the config files in
`PLYMELD_CONFIG` (or `--config`, `:`-separated), and with `--render` draws the plate. Nothing is sent to a
printer. Keep your own config and plates outside the repository.

## Rules every change follows

### Provenance: from scratch, never copied

- **No code, comments, or data tables are copied** from OrcaSlicer, PrusaSlicer, CuraEngine, Slic3r, Bambu
  Studio, vendor forks, or any other slicer. Read them to learn behavior, algorithms, formats, and settings
  semantics; then close them and write plymeld's own. One copied line would make the commercial license
  impossible to grant for the code it is in.
- **Say in your pull request what you consulted**: the project and its license, and what you learned from it.
- **Code written with an AI assistant is welcome on the same terms as any other.** You are its author and answer for
  it: you have read and understood every line, and the CLA's promises hold for it (your own work, nothing copied from
  other software). Say so with an `Assisted-by: <tool>` line in the commit message. Use only tools whose terms are
  compatible with the AGPL-3.0 and claim no rights in what you submit.
- **No settings are taken from another slicer.** Another slicer's settings and output may be used to
  develop and check slicing functionality, through `plymeld investigate`, never as plymeld's values.

### Dependencies and technology

- **Every new dependency, language, build tool, or library needs the maintainers' agreement** before it is
  used: propose it in an issue. **It must allow both licenses**: compatible with the AGPL-3.0, and
  redistributable under the commercial license's closed terms, which in practice means permissive (MIT,
  Apache-2.0, BSD). The current stack: Gradle (Kotlin DSL), JDK 21, Kotlin, Clikt, kotlinx-serialization,
  tomlj, kotest on the JUnit Platform; for the window, Compose Multiplatform and LWJGL; for SSH to a
  printer, Apache MINA SSHD.
- **The 2D geometry kernel is plymeld's own**, in pure Kotlin on an integer grid. No third-party polygon
  library.
- **Kotlin only.** Tools are written in Kotlin too; shell (and `.cmd` on Windows) only drives builds, hooks, and runs
  (`tools/`).

### A general product

- plymeld is built for any printer, model, and material. The machines and models used in development are
  **test cases, never the target**. No rule, default, or constant may be justified by "it suits this
  printer, model, or profile".
- **Everything machine-specific lives in a machine description**, never in the planner.
- **Physical figures are settings, never code constants.** Every value plymeld supplies that changes the
  print (bundled data, defaults, planner numbers) is an overridable setting with its source. A placeholder
  is fine while a feature is developed, and its source says "placeholder".
- **Never invent a value.** A placeholder you had to pick is labeled as a choice in the code, the docs, and
  the commit.
- **Pressure advance belongs to the firmware by default.** plymeld emits none and never overrides the
  firmware's, except where it is wanted and set (a calibration plate that must carry a known value, a
  print at a stated value): then it is a setting with its source like any other.
- **Nothing of anyone's own in the repository**: no one's own models, slices of them, calibration plates,
  readings, renders, or printer configs. Test models in the repository are ones plymeld may carry (for
  example the CC0 3DBenchy) or are generated.
- **Tests and the scripts in `tools/` never contact a real printer.** Printer code is tested against recorded
  answers; only the `plymeld printer` commands a user runs talk to a printer.

### Consistency

Nothing built is set in stone. With every change, weigh its effect on the product as a whole: names,
kinds, the shapes of settings, descriptions, the same idea named the same way everywhere. Fix what the
change makes inconsistent in the same change. A term that produces unintuitive behavior is a problem with
the term, not a constraint to work around.

### Text matches reality

The change that alters behavior also fixes every piece of text it makes wrong: file headers, doc
comments, CLI help and messages, the [README](README.md), [FEATURES.md](FEATURES.md),
and the documentation in `docs/`. Stale text found anywhere is fixed on sight.

**Comments and docs say what and why, not who or when.** A comment records the decision and its reason;
who made it and when is in the commit history, so it is not repeated in the text.

**Every print technique's documentation shows a sample toolpath**: a picture of the g-code plymeld writes
for a small synthetic model, beside the default technique on the same model where there is one, rendered
by a test into `docs/renders/` and shown where the technique is documented. A new technique, or a change to what
one lays down, is not done without it.

### Checks that can fail

- **The test suite is a deliverable.** Every module ships tests; every feature gets a comparison with an
  independent reference.
- **Every check has a control**: a deliberately wrong input the check is required to reject, run, and seen
  to fail. Absence is probed explicitly: assert that a feature exists.
- **A reference must be independent** of the code under test. Never compare a result with the constants
  that produced it.
- **Never catch an exception inside a check.**
- **Determinism**: the same model, settings, and plan file give byte-identical g-code, apart from
  what records when or where it was made (timestamps and the like). Deciding a plan is bounded by `method.search.time.max`, wall-clock
  time, so a search that reaches that limit can stop at a different point on a busier machine; a plan written with
  `plan -o` and sliced from repeats exactly.

### Engineering

- **Flat data**: vertices, segments, and paths in primitive arrays, not lists of objects. Readers stream.
  Integer coordinates inside the kernel (`plymeld.geom.Units.PER_MM`); millimeters only at module
  boundaries.
- **Stage graph**: every pipeline stage declares its input key (the mesh hash plus the settings it reads).
  Changing a setting a stage does not read must not re-run it.
- **Keep [FEATURES.md](FEATURES.md) current**: what plymeld has, per feature, with the date.

### Test prints are experiments

A test print happens only after the software suite passes, and only with its outcomes written down in
advance: the question, why software cannot answer it, what each possible result would teach (failure
modes included), what to measure, and how the result is written back.

## Pull requests

- One logical change per commit, with a message that says what changed and why.
- Work on a branch of your own; keep a pull request to one topic.
- Update [CHANGELOG.md](CHANGELOG.md) under "Unreleased" for anything a user would notice.
- `./gradlew build` and `tools/regress.sh check` pass, and every regression difference is explained in the
  pull request.
