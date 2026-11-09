<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->

# `plymeld update`

[Command reference](README.md)

Install the newest plymeld release over this one. An install from the Windows installer, the portable zip or the AppImage is updated by its updater as this command exits (the window included); the Linux command line's own download (plymeld-linux-x64.tar.gz) has its files replaced in place, the download checked against the checksum published with it and the new executable's --version. On Windows, other plymeld processes running from the install are named and the update refused, unless --stop-others stops them. A build from source is not updated. Whether plymeld only says a newer release is out: `plymeld updates`.

Usage: `plymeld update [<options>]`

Options:

- `--stop-others`: stop the other plymeld processes running from this install first (a window, a command in another terminal): on Windows their files cannot be replaced while they run
- `-h, --help`: Show this message and exit
