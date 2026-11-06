<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->

# `plymeld edit`

[Command reference](README.md)

Write values into a config file, each on its own line, the rest of the file kept as it was: comments, order, the other values; --unset and --unset-under remove lines instead. A path the schema does not declare is refused; a file that would not read back is refused and left untouched. Prints each line changed, with the value it held before.

Usage: `plymeld edit [<options>] <file> [<path=value>]...`

Arguments:

- `<file>`: the config file to change (.toml; a plan file from `plymeld plan -o`, or your own)
- `<path=value>`: a config path and the value to write there, e.g. plan.object.boat.technique.flat.layer.height=0.12mm; repeatable

Options:

- `--keep`: write each value kept ("!value"): a plan file's line that survives `plymeld plan` re-planning the rest
- `--unset=<path>`: remove the line holding PATH (a comment directly above it goes with it); repeatable. In a plan file: that decision is decided again
- `--unset-under=<path>`: remove every line whose key is under PATH (plan.object.boat.band takes every band line); repeatable
- `--journal=<journal>`: record the edit in this journal file (&lt;plan&gt;.journal.json: the file's text before and after, and this command line), for `plymeld journal --undo`
- `-h, --help`: Show this message and exit
