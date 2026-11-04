# Security

## Supported versions

plymeld is pre-release: there is no stable release yet. Fixes go into `main` and the next release.

## Reporting a vulnerability

**Please do not open a public issue.** Report it privately through GitHub:
[Report a vulnerability](https://github.com/plymeld/plymeld/security/advisories/new) (the repository's
Security tab). Include the plymeld version (`plymeld --version`), the command, and the smallest model and
config that show the problem. Do not send models or configs you are not free to share.

## What plymeld does

plymeld runs locally. It reads model files (STL, 3MF) and config files (TOML, JSON) and writes g-code,
plans, and pictures where a command names them, and one file of its own: the search log, a line per
decision (date, model file name, how the search stopped, its time) appended to `search.log` in plymeld's
state folder (`method.search.log.enabled = "false"` switches it off; `.file` moves it). Two things talk to
anything outside the machine: the `plymeld printer` command, and the window's check for updates.

### Network access

**On the command line, only `plymeld printer` reaches the network.** Every other
command (`slice`, `plan`, `config`, `calibrate`, ...) makes no network connection, and the command line
never checks for updates.

**The window** (an installed build only) reads its release feed (one GET of `releases.<channel>.json`)
when it opens, unless the viewer chose `never`; it downloads and installs an update only after a click
(the default) or, when the viewer chose `auto`, at the next start. The feed is read over https only.
The package is checked against the feed's size and SHA-256 (SHA-1 where an older feed gives only that;
a package the feed gives no checksum for is refused) before it is installed. The checksum comes from the
same feed, so it guards against a damaged download, not a compromised feed: signed packages are not
there yet. A docs link opens in the system's browser.

#### `plymeld printer`

- **Where it connects:** to the machine named with `--machine`, at the address and port in that
  machine's `hardware.machine.<key>.connection.*` in the config (`loadout` also still takes `--api`
  with `--host` / `--port`). Nothing else is contacted: no discovery, no cloud.
- **What it sends, per subcommand**, each over plain HTTP to the protocol the machine's `connection.api`
  names (Flashforge's LAN HTTP API on port 8898, or Moonraker's HTTP API on port 7125):
  `status` and `loadout` one state request; `watch` one state request per poll (every 5 s unless
  `--every` says otherwise) until the print ends or Ctrl+C; `camera` one GET of the machine's
  `connection.camera.url` (and, on a Flashforge printer without one, one `/detail` to find the
  stream), the picture written only to the file named with `-o`; `files` one list request; `send` the
  list, the upload, the list again, and with `--print` one loadout request where the API has one and
  the start (which on a Flashforge printer carries the tool-to-slot mapping with each filament's kind
  and color); `pause`, `resume`, `cancel` one job-control request and one state request. Each
  subcommand's help names its requests. Nothing is retried; nothing runs after the command returns
  (`watch` runs until it returns). With `--from-response FILE`, `status` and `loadout` send nothing.
- **Credentials:** a Flashforge printer's serial number and LAN check code ride in every request's body
  (headers for the upload); a Moonraker API key, when set, as a header. plymeld reads each from a source
  the config **names**: the environment variable of `connection.credential.<name>.env`, or the
  `<name> = "value"` line of the local secrets file `connection.credential.<name>.file` (the
  environment wins when both have a value; `loadout --api` also takes them as the options
  `--serial-number` / `--check-code`, which show in the process list). plymeld writes a value only into
  a secrets file: `plymeld init` writes the ones it asks for, and `plymeld printer credential` sets one,
  typed at a prompt that does not show it or read from standard input, never taken from the command
  line. A secrets file plymeld creates is readable by you alone where the file system has such
  permissions; one that already exists keeps the permissions it has (plymeld does not check them). The
  values are left out of every message, including refusals and logs. The config file holds names and
  paths only, so it can be shared. **Never commit a secrets file**; keep it readable by you alone.
- **Not encrypted:** both APIs are plain HTTP, so the credentials cross the local network in the clear,
  as they do from the printer makers' own slicers. Use them on a network you trust.
- **What it writes:** the report to the terminal; `loadout -o`, a config file of `loadout` lines;
  `loadout --save-response`, the printer's raw answer, which identifies the printer (its address, MAC
  address, file names) and should not be shared as it is.
- **What it trusts:** a printer's answer is parsed as JSON and only the documented fields are read; an
  answer that is not what plymeld expects is refused with a message, never acted on. A reply code is
  never taken as proof: an upload is confirmed by the printer's own file list, and a job control by
  the state read back (the Creator 5 acknowledges commands it does not know).
- **What changes the printer:** `send` (a file written to it; with `--print` a print started, the bed levelled first
  when asked), `pause`,
  `resume`, `cancel`. `status`, `files`, and `loadout` change nothing. `send --print` refuses while the
  printer is printing, paused, or busy, where the state can be read.
- **A shell on the printer (SSH), only where the config names one** (`hardware.machine.<key>.connection.ssh.user`):
  `printer loadout` and `send --print`'s loadout check on a Moonraker machine read one file over it
  (`cat` of Klipper's `save_variables` file, `connection.ssh.variables_file`) and run nothing else. The
  library is Apache MINA SSHD (Apache-2.0). The printer's **host key is checked against your
  `~/.ssh/known_hosts`**: an unknown or changed key is refused in one line and nothing is sent.
  `connection.ssh.accept_new = "true"` trusts an unknown key on first use and records it: **a risk**, as
  that first time a machine in between could pose as the printer; prefer connecting once with `ssh`
  yourself so the key is recorded by hand. A changed key is refused always. The password or private
  key comes through the same credential sources as the rest (environment or secrets file); a key file's
  passphrase is not supported. Nothing was tried against a real printer from plymeld's side.

The problems that matter most for a slicer:

- **A model or config file that crashes or hangs plymeld**, or makes it use unbounded memory.
- **G-code that could harm a printer or its surroundings**: moves outside the build volume,
  temperatures beyond a heater's limit, a nozzle driven into the bed or into printed parts, commands
  the machine is not known to accept. The pre-print check is meant to catch these; a case it misses is
  worth reporting.
- **A file read or written outside what the command named.**
- **A printer credential that leaks** (into a file, a message, or a log), or any request to a printer
  that a subcommand's help does not name.
