---
name: wrkx-checkout
description: Use when maintaining scripts that call the WRKX checkout compatibility task; prefer wrkx-worktree for new automation.
---

# WRKX checkout compatibility

## Commands

```bash
./gradlew wrkx-checkout -Pwrkx.branch=feature/new-catalog
./gradlew wrkx-checkout-<repo> -Pwrkx.branch=feature/new-catalog
```

`<repo>` is the sanitized Git URL directory name shown by `./gradlew tasks --group wrkx`.

## Behavior

- `wrkx-checkout` is a compatibility route to branch-scoped worktree creation.
- It does not run `git checkout` inside another branch's existing directory.
- The aggregate task processes enabled repositories only; the per-repository task is an explicit override.
- Without a selected working branch, checkout compatibility falls back to each repository's `baseBranch`, but that
  branch must also satisfy WRKX worktree naming rules.

## Rules

- Prefer `wrkx-worktree` in new documentation and automation because its name describes the actual operation.
- Pass `-Pwrkx.branch` or configure `workingBranch` when all repositories must use one feature branch.
- Select an explicit valid working branch when a base branch is `develop`, nested, or otherwise outside worktree rules.
- Do not use this task to switch an existing worktree. Each branch has its own WRKX-managed path.
- Use the `wrkx-worktree` skill for branch validation, layout, and start-point rules.
