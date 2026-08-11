---
name: wrkx-clone
description: Use when bootstrapping WRKX bare repositories or refreshing an existing bare clone for all configured repositories or one explicit repository.
---

# WRKX clone

## Commands

```bash
./gradlew wrkx-clone
./gradlew wrkx-clone-<repo>
```

`<repo>` is the sanitized Git URL directory name shown by `./gradlew tasks --group wrkx`, not necessarily the JSON
repository name.

## Behavior

- The aggregate task processes every entry in `wrkx.json`, including disabled repositories.
- A missing repository is created with `git clone --bare` under `<workspace>-repos/bare/<directory-name>.git`.
- An existing valid bare repository receives the full branch fetch refspec, then fetches and prunes `origin/*` refs.
- The per-repository task targets one repository regardless of enablement.

## Rules

- Use this task for the first bootstrap; `wrkx-fetch` requires the bare repository to already exist.
- Ensure Git credentials can access every selected remote. Terminal credential prompts are disabled.
- Do not replace the bare target with a normal clone. WRKX refuses an existing non-bare directory.
- This task does not create worktrees. Run `wrkx-worktree` afterward when branch files are needed.

## Recovery

Read the reported repository and path. Repair or move an invalid target, verify remote access, then rerun the generated
`wrkx-clone-<repo>` task for the failed repository.
