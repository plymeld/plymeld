<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->

# `plymeld init`

[Command reference](README.md)

Set up plymeld: your printers (each a model plymeld ships), their tools and build plates, your filaments, and what is loaded where, written to plymeld's config folder; it ends by slicing a test cube with them. Arrow keys choose, Enter confirms, Ctrl+C stops without writing anything. A command run in a terminal with no -cf and an empty config folder starts it too.

Usage: `plymeld init [<options>]`

Options:

- `--folder=<dir>`: write the setup here instead of plymeld's config folder (a folder holding no .toml file)
- `-h, --help`: Show this message and exit
