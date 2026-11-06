<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->

# `plymeld slice`

[Command reference](README.md)

Slice models (-m) to g-code with config files (-cf) and values (-cv).

Usage: `plymeld slice [<options>]`

Options:

- `-cf, --config-file=FILE|GLOB`: a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv, --config-value=<path=value>`: one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine=<key>`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `-m, --model=FILE|GLOB`: a model file (STL, 3MF) or a glob of them; repeatable; each adds its objects to the plate (required)
- `--explain`: also print how each object's techniques were chosen: the proposals and their fate, the candidates and their measures, the handoffs, the scoring, and the decision
- `--explain-json=<file>`: write the same as --explain as JSON to FILE
- `--plate=<n>`: which plate of a 3MF project to use (1-based, as the project numbers them); needed when several have objects
- `-o, --output=<file>`: g-code file to write (required)
- `--from=<gcode>`: start from what a g-code `plymeld slice -o` wrote was made from: the config and plan in its source block (-cf and -cv add to them; -m names the model files)
- `-h, --help`: Show this message and exit
