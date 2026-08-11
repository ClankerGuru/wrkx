---
name: wrkx-worktree
description: Use when creating or restoring branch-scoped WRKX worktrees for enabled repositories or one explicitly selected repository.
---

# WRKX worktree

## Commands

```bash
./gradlew wrkx-worktree -Pwrkx.branch=feature/new-catalog
./gradlew wrkx-worktree-<repo> -Pwrkx.branch=feature/new-catalog
```

You may set `workingBranch` in `settings.gradle.kts` instead of passing the property.
`<repo>` is the sanitized Git URL directory name shown by `./gradlew tasks --group wrkx`.

## Behavior

- The aggregate task processes enabled repositories only; the generated per-repository task is an explicit override.
- Missing bare repositories are cloned and existing bare repositories are fetched first.
- When the expected target path already exists, WRKX returns `SKIP` without validating its Git registration or checked
  out branch. Inspect an unexpected pre-existing path before continuing.
- A new branch starts from `origin/<workingBranch>` when that remote branch exists, otherwise from the repository's
  configured `origin/<baseBranch>`, then from an existing local `<baseBranch>` as the final fallback.
- Worktrees use `<workspace>-repos/<normalized-prefix>/<branch-name>/<directory-name>`.

## Branch rules

- Built-in prefixes are `feature`, `bugfix`, `custom`, `poc`, and `release`; `main` and `dev` are standalone branches.
- Prefix matching is case-insensitive and the filesystem prefix is lowercase.
- A prefixed branch has exactly one slash; its name contains letters, numbers, and single hyphens.
- Add organization-specific prefixes with `allowBranchPrefixes(...)`.

## Workflow

Create worktrees before Gradle evaluates them as included builds:

```bash
./gradlew wrkx-worktree -Pwrkx.branch=feature/new-catalog
./gradlew build -Pwrkx.branch=feature/new-catalog
```

WRKX never pushes the new local branch. Push it later using your normal Git workflow from the worktree if required.
