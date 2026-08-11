---
name: wrkx-status
description: Use when generating or interpreting the WRKX repository catalog report before workspace setup, synchronization, cleanup, or troubleshooting.
---

# WRKX status

## Command

```bash
./gradlew wrkx-status
```

## Output

The task writes `.wrkx/repos.md` in the workspace root. It reports every configured repository, including:

- Configured remote path and bare-clone state.
- Enabled or disabled composite-build state.
- Categories and configured base branch.
- Dependency-substitution settings.
- Catalog summaries and category views.
- Machine-readable repository lines for other tools.

## Rules

- The task is read-only with respect to Git; it only creates or replaces the Markdown report.
- Use it before deleting or pruning worktrees to confirm catalog configuration and clone state. Inspect Git directly
  for worktree paths, branch registration, dirtiness, and locks; those details are not in this report.
- A disabled repository may still have a bare clone or old worktrees because enablement controls composite inclusion,
  not catalog membership.
- Missing worktrees are expected on a fresh workspace until `wrkx-worktree` runs.

## Diagnosis sequence

1. Run `wrkx-status` from the workspace root.
2. Confirm the repository is present in `wrkx.json` and has the expected enablement and base branch.
3. Confirm the bare path exists before using `wrkx-fetch`.
4. Inspect the selected Git worktree directly before using `wrkx-pull`.
5. Use the generated per-repository lifecycle task to retry only the failing repository.
