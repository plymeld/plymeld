<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->

# `plymeld migrate`

[Command reference](README.md)

Rewrite config and plan files to the settings' current names: a setting renamed in a release under its new name, its value converted (plymeld reads the old name anyway, with a warning); a removed one taken out. Comments and the rest of each file stay as they were; each file written is recorded for `plymeld journal --undo`.

Usage: `plymeld migrate [<options>] <file>...`

Arguments:

- `<file>`: a config or plan file (TOML) to rewrite

Options:

- `--dry-run`: show the changes; write nothing
- `--journal=<journal>`: record each change in this journal file; default: the config journal in plymeld's state folder (config.journal.json)
- `-h, --help`: Show this message and exit
