---
name: wrkx-pull
description: Use when safely fetching repositories and merging each configured base branch into the selected clean WRKX worktree.
---

# WRKX pull

## Commands

```bash
./gradlew wrkx-pull -Pwrkx.branch=feature/new-catalog
./gradlew wrkx-pull-<repo> -Pwrkx.branch=feature/new-catalog
```

`<repo>` is the sanitized Git URL directory name shown by `./gradlew tasks --group wrkx`.

## Behavior

- The aggregate task processes enabled repositories only; the generated task explicitly targets one repository.
- Every selected bare repository is cloned or fetched before worktree checks.
- With a selected branch, WRKX merges `origin/<baseBranch>` into that exact branch worktree.
- Without a selected branch, it fetches the bare repository and performs no worktree merge.
- A conflicting automatic merge is aborted before the task reports failure.

## Preconditions

- Create the selected worktree first with `wrkx-worktree`.
- The worktree must be clean and must have the expected branch checked out.
- The configured base branch must exist as `origin/<baseBranch>` after fetching.

## Recovery

- Dirty worktree: report the dirty paths and stop. Commit or stash user-owned changes only with explicit approval.
- Missing worktree: run `wrkx-worktree-<repo>` for the same selected branch.
- Merge conflict: report the aborted merge and worktree path. Perform a manual merge and commit only with approval.
- Missing base: correct `baseBranch` in `wrkx.json` or the settings DSL, or create it on the remote.
