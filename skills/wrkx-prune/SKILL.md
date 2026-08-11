---
name: wrkx-prune
description: Use when safely pruning obsolete WRKX worktrees after their previously observed remote branches have been deleted and merged.
---

# WRKX prune

## Commands

```bash
./gradlew wrkx-prune
./gradlew wrkx-prune-<repo>
```

The aggregate task evaluates every configured repository. The generated task evaluates one repository regardless of
enablement. `<repo>` is the sanitized Git URL directory name shown by `./gradlew tasks --group wrkx`.

## Eligibility

WRKX removes a worktree and local branch only when all conditions hold:

- WRKX previously observed the branch on `origin`.
- A fresh fetch confirms that the remote branch no longer exists.
- The local branch tip is fully merged into `origin/<baseBranch>`.
- The worktree has no staged, modified, or untracked files.
- Its canonical path exactly matches the configured WRKX layout.

## Preservation rules

- Never-pushed branches are retained.
- Branches still present on the remote are retained.
- Dirty, detached, unmerged, inaccessible, or externally located worktrees are retained.
- `main`, `dev`, and each repository's configured base-branch worktree are retained.
- No remote branch is deleted; configure remote branch deletion in your hosting workflow separately.

## Use the right cleanup task

Use `wrkx-prune` for routine cleanup after merged remote branches disappear. Use `wrkx-worktree-delete` when you
explicitly want to discard or restart the currently selected branch and accept that an unmerged local branch may remain.
