<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->

# `plymeld render-plate`

[Command reference](README.md)

Draw a calibration plate (plymeld calibrate) from its g-code, to review before printing: a top view by flow, the front face by temperature, the melt test's strips, and every temperature command (always); an isometric diagram labeled from the plate's instructions page (--iso); a melt step in section, its strips on their floor and the head's travel over them (--section); each base's layers (--layers, --base).

Usage: `plymeld render-plate [OPTIONS] PLATE.gcode`

Arguments:

- `PLATE.gcode` (required): a calibration plate's g-code (plymeld calibrate -o)

Options:

- `-o DIR`, `--output DIR`: directory for the pictures (default: the g-code's)
- `--iso`: also the isometric diagram, every region labeled from the plate's instructions page
- `--page PAGE.md`: the instructions page for --iso (default: the g-code's name with .md)
- `--layers`: also each base's layers (every 'base ...' cell in the g-code), with a zoom round its notch
- `--base CELL` (repeatable): only this base (e.g. 'base melt'); repeatable; implies --layers
- `--layer-z MM`: the layer for the top view (panel A); default: the one nearest 10 mm
- `--section BAND`: melt plate: also a side section through this step (from 1, the front): the beads cut front to back through its FAST surface, and the head's travel from its FAST strips to its MID strips against what lies under it
- `-h`, `--help`: show the command's help and exit
