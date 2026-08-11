---
name: wrkx-worktree-delete
description: Use when safely deleting only the selected branch's WRKX-managed worktrees and optionally its merged local branches without mutating remotes.
---

# WRKX worktree delete

## Commands

```bash
./gradlew wrkx-worktree-delete -Pwrkx.branch=feature/restart-work
./gradlew wrkx-worktree-delete-<repo> -Pwrkx.branch=feature/restart-work
```

The aggregate task examines every configured repository, including disabled ones. The generated task targets one
repository. `<repo>` is the sanitized Git URL directory name shown by `./gradlew tasks --group wrkx`.

## Safety guarantees

- Targets only the selected branch's canonical WRKX path.
- Refuses dirty, locked, inaccessible, unregistered, or externally located worktrees.
- Uses ordinary `git worktree remove` with no force option.
- Attempts local branch cleanup only with safe `git branch -d`; unmerged branches remain.
- Preserves bare repositories, remote branches, other local branches, and every other worktree.
- Never pushes, force-pushes, or deletes a remote branch.

## Restart from a corrected base

For an unpushed branch created from the wrong base:

```bash
./gradlew wrkx-worktree-delete -Pwrkx.branch=feature/restart-work
./gradlew wrkx-worktree -Pwrkx.branch=feature/restart-work
```

Set the corrected per-repository `baseBranch` before recreation. If `origin/<workingBranch>` already exists, WRKX
restores that remote branch instead of rewriting it from the new base.

## Recovery

On refusal, inspect `./gradlew wrkx-status` and Git's worktree list at the reported bare repository. Resolve the dirty,
locked, or path-registration condition manually, then retry. There is intentionally no force mode.
