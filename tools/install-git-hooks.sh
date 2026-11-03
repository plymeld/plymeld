#!/usr/bin/env bash
# Install plymeld's git hooks (tools/git-hooks/: the commit check, pre-commit, a compile; the tests run in CI) for this repository: points git's
# core.hooksPath at tools/git-hooks. The setting lives in the repository's shared config, so every worktree of it uses
# the hooks of its own checkout (a relative hooksPath is read from the root of the worktree committing), and a change
# to a hook takes effect with the commit that makes it. Run once per clone. Undo: git config --unset core.hooksPath
#
# usage: tools/install-git-hooks.sh
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
git -C "$here" config core.hooksPath tools/git-hooks
echo "core.hooksPath = $(git -C "$here" config core.hooksPath): every commit in this repository's checkouts runs tools/git-hooks/pre-commit, commit-msg and post-commit"
