<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->

# `plymeld journal`

[Command reference](README.md)

List a journal of edits (each as the command that made it, applied or undone), or --undo the last applied one, or --redo the last undone one. An undo or redo writes the whole file as the journal holds it, and is refused when the file changed since.

Usage: `plymeld journal [<options>] <journal>`

Arguments:

- `<journal>`: the journal file (&lt;plan&gt;.journal.json, written by `plymeld edit --journal`)

Options:

- `--undo`: take the last applied edit back: its file written as it was before (refused when the file is not as the edit left it)
- `--redo`: do the last undone edit again: its file written as the edit left it
- `-h, --help`: Show this message and exit
