---
name: wrkx-fetch
description: Use when refreshing all remote branch references in existing WRKX bare repositories without creating, switching, merging, or deleting worktrees.
---

# WRKX fetch

## Commands

```bash
./gradlew wrkx-fetch
./gradlew wrkx-fetch-<repo>
```

`<repo>` is the sanitized Git URL directory name shown by `./gradlew tasks --group wrkx`, not necessarily the JSON
repository name.

## Behavior

- The aggregate task processes every configured repository, including disabled repositories.
- Fetches all branches from `origin` and prunes remote-tracking refs deleted upstream.
- Records remote branches observed by WRKX so `wrkx-prune` can distinguish deleted branches from never-pushed work.
- Does not create a missing bare repository and never changes a worktree.

## Rules

- Use `wrkx-clone` instead when the workspace has not been bootstrapped.
- Use the per-repository task to retry one failed remote without contacting the rest of the catalog.
- A pruned remote-tracking ref does not delete its local branch or worktree; cleanup remains a separate safe operation.

## Recovery

If the bare repository is missing, run `./gradlew wrkx-clone-<repo>`. If it is invalid, move or repair that target before
retrying. Authentication failures require fixing Git remote access outside WRKX.
