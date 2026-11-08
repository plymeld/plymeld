<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->

# `plymeld report`

[Command reference](README.md)

Make a problem report about what you are working on: a zip to attach to a GitHub issue, and the issue filled in for you to read and submit (its address printed, or opened with --open). The zip holds plymeld's version and the system, the config as plymeld reads it (plymeld config), with -m the plan (plymeld plan), with --gcode that file's header and source block, and with --printer the printer's status. The models and the whole g-code only with --include-model. Never credentials (in the environment or the secrets files, not the config), network addresses, or your home folder's path. Nothing is sent: you submit the issue.

Usage: `plymeld report [<options>]`

Options:

- `-cf, --config-file=FILE|GLOB`: a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv, --config-value=<path=value>`: one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine=<key>`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `-m, --model=FILE|GLOB`: the job's model files: the report holds the plan for them (not the files themselves; --include-model adds them)
- `--gcode=<file>`: a g-code file plymeld wrote for the job: the report holds its header and its source block (the config and plan it was made from), not its moves
- `--printer`: also the machine's printer's status (plymeld printer status): asks the printer
- `--include-model`: also the model files and the whole g-code file: a model may be private, and its g-code draws it, so they are left out unless asked
- `--title=<text>`: the issue's title (default: "Problem report" and plymeld's version)
- `--description=<text>`: what happened and what you expected, written into the issue (default: headings to fill in on the issue's page)
- `-o, --output=<file>`: the zip to write (default: plymeld-report-&lt;date&gt;-&lt;time&gt;.zip here)
- `--open`: open the filled-in issue in the browser (otherwise its address is printed)
- `-h, --help`: Show this message and exit
