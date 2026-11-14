<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->

# `plymeld printer`

[Command reference](README.md)

Talk to the machine's printer (--machine, over hardware.machine.&lt;key&gt;.connection in the config; credentials from the environment variables or secrets files named there): status (its state), watch (its state as it changes), camera (one picture from its camera), files (what is on it), send (upload a file; --print starts it), pause, resume, cancel, clear (tell it its bed is clear, the last print removed), loadout (what it has loaded, against the config), credential (set one in its secrets file; sends nothing). Each says what it sends; what the machine's API cannot do is refused in one line.

Usage: `plymeld printer [<options>] <command> [<args>]...`

Subcommands:

- [`status`](#plymeld-printer-status): Read the printer's state: status, job, progress, layer, times, nozzle and bed temperatures, speed and flow overrides, what is loaded where the same answer carries it; "not reported" where its API gives no such field.
- [`watch`](#plymeld-printer-watch): Print the printer's state as it changes: the full state first, then one timestamped line per line that changed, asking every --every seconds (default 5 s) until the print ends (finished, canceled, error, or idle after it was printing) or Ctrl+C.
- [`camera`](#plymeld-printer-camera): Save one JPEG from the printer's camera to FILE (-o): the machine's connection.camera.url (a snapshot or an MJPEG stream; flashforge-http falls back to the URL its /detail names), refused in one line where none is set.
- [`files`](#plymeld-printer-files): List the files on the printer, one per line with the size and time where its API gives them.
- [`send`](#plymeld-printer-send): Upload FILE to the printer and confirm it by the printer's own file list (never by the reply code); with --print, start it, telling the printer which slot prints each tool the file uses where its API takes that (flashforge-http: materialMappings, from the machine's loadout), and to level the bed first with --level or the machine's connection.start.level (flashforge-http only).
- [`pause`](#plymeld-printer-pause): Pause the print.
- [`resume`](#plymeld-printer-resume): Resume the print.
- [`cancel`](#plymeld-printer-cancel): Cancel the print.
- [`clear`](#plymeld-printer-clear): Be told its bed is clear.
- [`loadout`](#plymeld-printer-loadout): Read what the printer has loaded in each tool or slot and compare it with the machine's loadout in the config (-cf, -cv, --machine): for each, the printer's material and color, the config's filament, and which config filament matches (same kind as the printer's material, same color exactly).
- [`credential`](#plymeld-printer-credential): Set one of the printer's credentials in the secrets file the machine's connection names (hardware.machine.&lt;key&gt;.connection.credential.NAME.file), the file's other lines kept.

Options:

- `-h, --help`: Show this message and exit

## `plymeld printer status`

Read the printer's state: status, job, progress, layer, times, nozzle and bed temperatures, speed and flow overrides, what is loaded where the same answer carries it; "not reported" where its API gives no such field. Sends one state request (flashforge-http: POST /detail; moonraker: GET /printer/objects/query).

Usage: `plymeld printer status [<options>]`

Options:

- `-cf, --config-file=FILE|GLOB`: a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv, --config-value=<path=value>`: one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine=<key>`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `--from-response=<file>`: read a saved answer to the state request (as the machine's API gives it) instead of asking the printer; nothing is sent
- `-h, --help`: Show this message and exit

## `plymeld printer watch`

Print the printer's state as it changes: the full state first, then one timestamped line per line that changed, asking every --every seconds (default 5 s) until the print ends (finished, canceled, error, or idle after it was printing) or Ctrl+C. Sends one state request per poll (flashforge-http: POST /detail; moonraker: GET /printer/objects/query) and nothing else.

Usage: `plymeld printer watch [<options>]`

Options:

- `-cf, --config-file=FILE|GLOB`: a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv, --config-value=<path=value>`: one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine=<key>`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `--every=<seconds>`: how often to ask the printer for its state (default 5 s, plymeld's choice)
- `-h, --help`: Show this message and exit

## `plymeld printer camera`

Save one JPEG from the printer's camera to FILE (-o): the machine's connection.camera.url (a snapshot or an MJPEG stream; flashforge-http falls back to the URL its /detail names), refused in one line where none is set. Prints the file's path and size. Sends one GET of the camera URL (and, flashforge-http without camera.url, one POST /detail).

Usage: `plymeld printer camera [<options>]`

Options:

- `-cf, --config-file=FILE|GLOB`: a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv, --config-value=<path=value>`: one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine=<key>`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `-o, --output=<file>`: the JPEG file to write (required)
- `--frame=<n>`: which frame of a stream to keep, from 1 (default: the adapter's own; flashforge-http keeps the second, its first being stale); a snapshot URL gives its one image
- `-h, --help`: Show this message and exit

## `plymeld printer files`

List the files on the printer, one per line with the size and time where its API gives them. Sends one list request (flashforge-http: POST /gcodeList; moonraker: GET /server/files/list).

Usage: `plymeld printer files [<options>]`

Options:

- `-cf, --config-file=FILE|GLOB`: a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv, --config-value=<path=value>`: one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine=<key>`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `-h, --help`: Show this message and exit

## `plymeld printer send`

Upload FILE to the printer and confirm it by the printer's own file list (never by the reply code); with --print, start it, telling the printer which slot prints each tool the file uses where its API takes that (flashforge-http: materialMappings, from the machine's loadout), and to level the bed first with --level or the machine's connection.start.level (flashforge-http only). What the printer does by itself as a print starts (homing, heating, levelling) is its model's (hardware.model.&lt;key&gt;.notes: a Flashforge Creator 5 homes and levels by itself, levelling when the start asks for it); the rest is the file's start g-code. Refused, before anything is sent, when a command in FILE holds a number a printer may refuse or act wrongly on (one that is not a finite number, a move at a feed rate of 0 or below, a temperature, fan, or wait below 0, an acceleration of 0 or below), naming each line. Refused when the printer already has a file of that name (--replace to overwrite), is printing, paused, or busy, or (--print) when a tool the file uses has no filament loaded in the machine's loadout. With --print, what the printer reports loaded is compared with the machine's loadout where its API reports one: a mismatch warns, one line per position, and the print goes on. Sends the list request, the upload (flashforge-http: POST /uploadGcode; moonraker: POST /server/files/upload), the list again, and with --print one loadout request where the API has one, then the start (POST /printGcode; POST /printer/print/start).

Usage: `plymeld printer send [<options>] <file>`

Arguments:

- `<file>`: the g-code file to upload

Options:

- `-cf, --config-file=FILE|GLOB`: a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv, --config-value=<path=value>`: one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine=<key>`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `--name=<name>`: the name to give it on the printer (default: the file's own name)
- `--print`: also start printing it once the upload is confirmed (the printer runs at once: the bed clear, the right filament loaded, and someone with it)
- `--level`: with --print: ask the printer to level the bed before it prints (flashforge-http: levelingBeforePrint); over the machine's connection.start.level; refused, before anything is sent, where the API has no such request (on a Klipper printer levelling is g-code)
- `--no-level`: with --print: do not ask the printer to level first, whatever the machine's connection.start.level says
- `--replace`: overwrite a file of the same name already on the printer (refused otherwise)
- `-h, --help`: Show this message and exit

## `plymeld printer pause`

Pause the print. Sends the pause (flashforge-http: POST /control jobCtl\_cmd pause; moonraker: POST /printer/print/pause), then reads the state back where the API allows (the Creator 5's /control acknowledges whatever happens: judge by the status read back).

Usage: `plymeld printer pause [<options>]`

Options:

- `-cf, --config-file=FILE|GLOB`: a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv, --config-value=<path=value>`: one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine=<key>`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `-h, --help`: Show this message and exit

## `plymeld printer resume`

Resume the print. Sends the resume (flashforge-http: POST /control jobCtl\_cmd continue; moonraker: POST /printer/print/resume, which returns when the RESUME macro has run), then reads the state back where the API allows (the Creator 5's /control acknowledges whatever happens: judge by the status read back).

Usage: `plymeld printer resume [<options>]`

Options:

- `-cf, --config-file=FILE|GLOB`: a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv, --config-value=<path=value>`: one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine=<key>`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `-h, --help`: Show this message and exit

## `plymeld printer cancel`

Cancel the print. Sends the cancel (flashforge-http: POST /control jobCtl\_cmd cancel; moonraker: POST /printer/print/cancel), then reads the state back where the API allows (the Creator 5's /control acknowledges whatever happens: judge by the status read back).

Usage: `plymeld printer cancel [<options>]`

Options:

- `-cf, --config-file=FILE|GLOB`: a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv, --config-value=<path=value>`: one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine=<key>`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `-h, --help`: Show this message and exit

## `plymeld printer clear`

Be told its bed is clear. Sends the bed-is-clear acknowledgement (flashforge-http: POST /control stateCtrl\_cmd setClearPlatform); only once the last print is off the bed: the printer takes your word for it, then reads the state back where the API allows (the Creator 5's /control acknowledges whatever happens: judge by the status read back).

Usage: `plymeld printer clear [<options>]`

Options:

- `-cf, --config-file=FILE|GLOB`: a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv, --config-value=<path=value>`: one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine=<key>`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `-h, --help`: Show this message and exit

## `plymeld printer loadout`

Read what the printer has loaded in each tool or slot and compare it with the machine's loadout in the config (-cf, -cv, --machine): for each, the printer's material and color, the config's filament, and which config filament matches (same kind as the printer's material, same color exactly). -o writes the matched loadout as a config file. Sends one state request (flashforge-http: POST /detail; moonraker: reads Klipper's save\_variables file over SSH, where connection.ssh.user is set) and nothing that changes the printer; the connection is the machine's in the config, or --api with --host and the credentials.

Usage: `plymeld printer loadout [<options>]`

Options:

- `-cf, --config-file=FILE|GLOB`: a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv, --config-value=<path=value>`: one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine=<key>`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `--api=<(flashforge-http)>`: the printer's API, when not the machine's connection in the config: flashforge-http = Flashforge's LAN HTTP API (POST /detail), as the Creator 5 serves it; the only one these options cover (a Moonraker machine's loadout comes over SSH, from the config's connection)
- `--host=<host>`: with --api: the printer's address
- `--port=<int>`: with --api: the API's port (default: the API's own, 8898 for flashforge-http)
- `--serial-number=<sn>`: with --api: the printer's serial number, which flashforge-http sends with each request
- `--check-code=<code>`: with --api: the printer's LAN check code, sent with each request (on the command line it shows in the process list; the machine's connection in the config reads it from the environment or a secrets file instead)
- `--timeout=<seconds>`: with --api: how long to wait for the printer (default 8 s, plymeld's choice)
- `--from-response=<file>`: read a saved /detail answer instead of asking the printer
- `--save-response=<file>`: also write the printer's raw answer here (it identifies the printer: address, MAC, file names; do not commit it as it is)
- `-o, --output=<file>`: write the matched loadout as a config file (TOML) to pass with -cf after your own; your files are never changed
- `-h, --help`: Show this message and exit

## `plymeld printer credential`

Set one of the printer's credentials in the secrets file the machine's connection names (hardware.machine.&lt;key&gt;.connection.credential.NAME.file), the file's other lines kept. The value is typed at a prompt that does not show it, or read as one line from standard input when that is not a terminal; it is never printed. Refused when the config names no secrets file for it (one named only by an environment variable is set there, not by plymeld). Sends nothing to the printer.

Usage: `plymeld printer credential [<options>] <name>`

Arguments:

- `<name>`: the credential: flashforge-http serial\_number or check\_code; moonraker api\_key, ssh\_password or ssh\_key\_file

Options:

- `-cf, --config-file=FILE|GLOB`: a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv, --config-value=<path=value>`: one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine=<key>`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `-h, --help`: Show this message and exit
