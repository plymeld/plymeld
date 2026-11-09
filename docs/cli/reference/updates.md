<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->

# `plymeld updates`

[Command reference](README.md)

Whether plymeld checks for a newer release: the setting (off until you turn it on with --set), the feed it asks, and what the last check found; --check asks now. Turned on, the command line asks the feed as it runs any command, at most once a day and in the background, and says a newer release in one line on stderr after the command (only in a terminal); the window asks as it opens.

Usage: `plymeld updates [<options>]`

Options:

- `--set=off|notify|install`: turn checks on or off, for the command line and the window: off (the default: nothing is checked), notify (a newer release is said after a command, at most once a day, and in the window's bar) or install (as notify, and the window installs it at its next start; the command line never updates itself)
- `--check`: ask the release feed now, whatever the setting, and say whether a newer release is out
- `-h, --help`: Show this message and exit
