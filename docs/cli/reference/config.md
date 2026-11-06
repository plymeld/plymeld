<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->

# `plymeld config`

[Command reference](README.md)

Show the config the files (-cf) and values (-cv) add up to, after plymeld's bundled files: each value, where it was set, the values it overrode, and what each formula works out to.

Usage: `plymeld config [OPTIONS] [PREFIX]`

Arguments:

- `PREFIX` (optional): only paths starting with this

Options:

- `-cf FILE|GLOB`, `--config-file FILE|GLOB` (repeatable): a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv PATH=VALUE`, `--config-value PATH=VALUE` (repeatable): one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine KEY`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `-h`, `--help`: show the command's help and exit
