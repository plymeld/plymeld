<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->

# `plymeld schema`

[Command reference](README.md)

Print (or write, -o) the config schema as JSON Schema, for editors checking config files; or rewrite the settings catalog's tables (--markdown).

Usage: `plymeld schema [<options>]`

Options:

- `-o, --output=<file>`: file to write (default: print it)
- `--markdown=<page>`: rewrite the settings tables in this page below its marker line, instead of the JSON Schema
- `-h, --help`: Show this message and exit
