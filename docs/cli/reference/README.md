<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->

# Command reference

Every command plymeld has, with every option, as its `--help` gives it. The [guides](../README.md) say how the commands fit together; `plymeld <command> --help` prints the same for one command.

## `plymeld`

plymeld — a 3D print slicer. Commands: slice (models to g-code), plan (how plymeld would print the models, and why), and config (what the config adds up to), each with config files (-cf), config values (-cv), and models (-m); schema (the config's JSON Schema, or the settings reference); calibrate (a calibration plate for one filament); render-plate (pictures of a plate from its g-code); layers (inspect a model's slices); printer (talk to the machine's printer over its connection in the config: status, watch, camera, files, send, pause, resume, cancel, loadout, which compares what it has loaded with the config's, and credential, which sets one in its secrets file; each says what it sends); investigate (slice, plan, check, config with the settings in a project or g-code file, to compare plymeld's result with that file — never how a print is set up — and predict, the time and filament of any g-code file); commands (every command and option plymeld has, and every config path, as the UI reads them; or the command reference as Markdown pages); edit (write values into a config file, the rest of it kept as it was); journal (the edits made with --journal: list, undo, redo); init (set up your printers and filaments, asked in the terminal; it starts by itself when a command finds no config).

Usage: `plymeld [<options>] <command> [<args>]...`

Options:

- `--version`: show the version and exit
- `-h, --help`: Show this message and exit

## Commands

- [`plymeld slice`](slice.md): Slice models (-m) to g-code with config files (-cf) and values (-cv).
- [`plymeld plan`](plan.md): Show how plymeld would print the models (-m) with config files (-cf) and values (-cv), and why; -o writes the decisions as a config file.
- [`plymeld config`](config.md): Show the config the files (-cf) and values (-cv) add up to, after plymeld's bundled files: each value, where it was set, the values it overrode, and what each formula works out to.
- [`plymeld schema`](schema.md): Print (or write, -o) the config schema as JSON Schema, for editors checking config files; or rewrite the settings catalog's tables (--markdown).
- [`plymeld layers`](layers.md): Slice a mesh into layers and report/draw the closed loops of each.
- [`plymeld calibrate`](calibrate.md): Make a calibration plate for one filament (--filament, --tool), printed exactly as specified: one subcommand per plate, in the order to run them (each plate's results lean on the ones before it): 'melt' (the melt rate: a column per temperature, a row per flow, each row's flow printed beside it. Run it first: an accurate melt rate drives so much of the rest, and the other plates are not reliable until it is measured); 'flow' (the flow ratio: a column per temperature, a patch per ratio, each patch solid at its own ratio and its top laid at its column's temperature; after the melt plate); 'first-layer' (the first layer: nozzle height x first-layer speed by temperature, one layer of tiles in one piece, a column per temperature and a row per speed and height (a frame over their walls and a border round the base to peel it by); after the flow plate); 'motion' (the machine: the speed and acceleration a visible wall holds without ringing, per direction: one cross-shaped tower, twelve faces at six speeds (one along X and one along Y each), a band per acceleration, the top band's fastest faces at the machine's limits; graded per cell, the readings become the machine's visible speed and acceleration; after the first-layer plate (the tower, shaken at the machine's limits, needs a first layer that holds)); 'filament' (the filament's other tests by temperature band, once its melt rate and flow ratio are measured: overhangs, bridges, stringing, line widths, fit (--tests picks a subset)); 'ironing' (the ironing values: one flat slab whose top is divided into zones, each ironed with its own combination of flow, line spacing, speed, angle, and inset, a control zone per speed left as laid; graded zone by zone, the readings become the ironing values); 'seams' (the seam measure's shares: eight hollow pieces on one slab, each with its outer wall's seam laid one way (a point seam or plymeld's scarf, on a round wall, mid-face, in a corner, on a face leaning 30 and 45 degrees); graded piece by piece for how far away the seam shows, the ratios become method.quality.seam.scarf\_share, .corner\_share and method.quality.visibility.under).
  - [`plymeld calibrate melt`](calibrate.md#plymeld-calibrate-melt), [`values`](calibrate.md#plymeld-calibrate-melt-values)
  - [`plymeld calibrate flow`](calibrate.md#plymeld-calibrate-flow), [`values`](calibrate.md#plymeld-calibrate-flow-values)
  - [`plymeld calibrate first-layer`](calibrate.md#plymeld-calibrate-first-layer), [`values`](calibrate.md#plymeld-calibrate-first-layer-values)
  - [`plymeld calibrate motion`](calibrate.md#plymeld-calibrate-motion), [`readings`](calibrate.md#plymeld-calibrate-motion-readings), [`values`](calibrate.md#plymeld-calibrate-motion-values)
  - [`plymeld calibrate filament`](calibrate.md#plymeld-calibrate-filament)
  - [`plymeld calibrate ironing`](calibrate.md#plymeld-calibrate-ironing), [`readings`](calibrate.md#plymeld-calibrate-ironing-readings), [`values`](calibrate.md#plymeld-calibrate-ironing-values)
  - [`plymeld calibrate seams`](calibrate.md#plymeld-calibrate-seams), [`readings`](calibrate.md#plymeld-calibrate-seams-readings), [`values`](calibrate.md#plymeld-calibrate-seams-values)
- [`plymeld render-plate`](render-plate.md): Draw a calibration plate (plymeld calibrate) from its g-code, to review before printing: a top view by flow, the front face by temperature, the melt test's strips, and every temperature command (always); an isometric diagram labeled from the plate's instructions page (--iso); a melt step in section, its strips on their floor and the head's travel over them (--section); each base's layers (--layers, --base).
- [`plymeld printer`](printer.md): Talk to the machine's printer (--machine, over hardware.machine.&lt;key&gt;.connection in the config; credentials from the environment variables or secrets files named there): status (its state), watch (its state as it changes), camera (one picture from its camera), files (what is on it), send (upload a file; --print starts it), pause, resume, cancel, clear (tell it its bed is clear, the last print removed), loadout (what it has loaded, against the config), credential (set one in its secrets file; sends nothing).
  - [`plymeld printer status`](printer.md#plymeld-printer-status)
  - [`plymeld printer watch`](printer.md#plymeld-printer-watch)
  - [`plymeld printer camera`](printer.md#plymeld-printer-camera)
  - [`plymeld printer files`](printer.md#plymeld-printer-files)
  - [`plymeld printer send`](printer.md#plymeld-printer-send)
  - [`plymeld printer pause`](printer.md#plymeld-printer-pause)
  - [`plymeld printer resume`](printer.md#plymeld-printer-resume)
  - [`plymeld printer cancel`](printer.md#plymeld-printer-cancel)
  - [`plymeld printer clear`](printer.md#plymeld-printer-clear)
  - [`plymeld printer loadout`](printer.md#plymeld-printer-loadout)
  - [`plymeld printer credential`](printer.md#plymeld-printer-credential)
- [`plymeld investigate`](investigate.md): INVESTIGATION ONLY: slice, plan, check, and config with the settings in a project or g-code file (--settings), and predict the time and filament of any g-code file, to compare plymeld's result with that file.
  - [`plymeld investigate slice`](investigate.md#plymeld-investigate-slice)
  - [`plymeld investigate plan`](investigate.md#plymeld-investigate-plan)
  - [`plymeld investigate check`](investigate.md#plymeld-investigate-check)
  - [`plymeld investigate config`](investigate.md#plymeld-investigate-config)
  - [`plymeld investigate predict`](investigate.md#plymeld-investigate-predict)
- [`plymeld commands`](commands.md): List every command and option plymeld has, and every config path with the scopes it may be set for: the model the UI is built from, so the two cannot differ; or write the command reference (--markdown).
- [`plymeld edit`](edit.md): Write values into a config file, each on its own line, the rest of the file kept as it was: comments, order, the other values; --unset and --unset-under remove lines instead.
- [`plymeld journal`](journal.md): List a journal of edits (each as the command that made it, applied or undone), or --undo the last applied one, or --redo the last undone one.
- [`plymeld init`](init.md): Set up plymeld: your printers (each a model plymeld ships), their tools and build plates, your filaments, and what is loaded where, written to plymeld's config folder; it ends by slicing a test cube with them.
