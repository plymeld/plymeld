<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->

# `plymeld commands`

[Command reference](README.md)

List every command and option plymeld has, and every config path with the scopes it may be set for: the model the UI is built from, so the two cannot differ; or write the command reference (--markdown).

Usage: `plymeld commands [<options>]`

Options:

- `--json`: as JSON instead of text
- `-o, --output=<file>`: file to write (default: print it)
- `--markdown=<dir>`: write the command reference instead: an index (README.md) and a Markdown page per command, into this folder (a page an earlier --markdown wrote that is no longer a command is removed; no other file is touched)
- `-h, --help`: Show this message and exit
