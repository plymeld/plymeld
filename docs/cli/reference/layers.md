<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->

# `plymeld layers`

[Command reference](README.md)

Slice a mesh into layers and report/draw the closed loops of each.

Usage: `plymeld layers [OPTIONS] MODEL`

Arguments:

- `MODEL` (required): STL file

Options:

- `--layer MM` (default: `0.2`): layer height, mm
- `--first MM`: first layer height, mm (default: same as --layer)
- `--svg DIR`: write one SVG per layer into this directory
- `--png FILE`: write a contact-sheet PNG of up to --sheet layers
- `--sheet N` (default: `16`): layers on the contact sheet
- `--quiet`: no per-layer summary
- `-h`, `--help`: show the command's help and exit
