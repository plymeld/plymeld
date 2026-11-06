<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->

# `plymeld layers`

[Command reference](README.md)

Slice a mesh into layers and report/draw the closed loops of each.

Usage: `plymeld layers [<options>] <model>`

Arguments:

- `<model>`: STL file

Options:

- `--layer=<mm>`: layer height, mm (default: 0.2)
- `--first=<mm>`: first layer height, mm (default: same as --layer)
- `--svg=<dir>`: write one SVG per layer into this directory
- `--png=<file>`: write a contact-sheet PNG of up to --sheet layers
- `--sheet=<n>`: layers on the contact sheet (default: 16)
- `--quiet`: no per-layer summary
- `-h, --help`: Show this message and exit
