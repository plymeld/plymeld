<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->

# `plymeld plan`

[Command reference](README.md)

Show how plymeld would print the models (-m) with config files (-cf) and values (-cv), and why; -o writes the decisions as a config file.

Usage: `plymeld plan [OPTIONS]`

Options:

- `-cf FILE|GLOB`, `--config-file FILE|GLOB` (repeatable): a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv PATH=VALUE`, `--config-value PATH=VALUE` (repeatable): one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine KEY`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `-m FILE|GLOB`, `--model FILE|GLOB` (required; repeatable): a model file (STL, 3MF) or a glob of them; repeatable; each adds its objects to the plate
- `--explain`: also print how each object's techniques were chosen: the proposals and their fate, the candidates and their measures, the handoffs, the scoring, and the decision
- `--explain-json FILE`: write the same as --explain as JSON to FILE
- `--plate N`: which plate of a 3MF project to use (1-based, as the project numbers them); needed when several have objects
- `-o FILE`, `--output FILE`: write the decisions as a config file (TOML), for `plymeld slice -cf`
- `-h`, `--help`: show the command's help and exit
