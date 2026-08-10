package zone.clanker.gradle.wrkx.task

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskAction
import zone.clanker.gradle.wrkx.Wrkx
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.File
import javax.inject.Inject

/**
 * Updates one bare repository and merges its base branch into the selected worktree.
 *
 * Registered per-repo as `wrkx-pull-<name>` by [Wrkx.SettingsPlugin].
 * Creates or updates the shared bare repository first. With no selected branch,
 * it performs only the fetch and leaves all worktrees unchanged.
 *
 * ```bash
 * ./gradlew wrkx-pull-gort      # pull just gort
 * ./gradlew wrkx-pull            # pull all (lifecycle task)
 * ```
 *
 * @param repo the repository to pull
 * @param repoDir base directory where repos are cloned
 * @param workingBranchProvider lazy selected branch; an empty value fetches without merging
 * @param allowedBranchPrefixes branch prefixes accepted by the workspace
 * @see CloneTask
 * @see CheckoutTask
 */
@org.gradle.api.tasks.UntrackedTask(because = "Pulls latest changes for a single repository")
abstract class PullTask
    @Inject
    constructor(
        private val repo: WorkspaceRepository,
        private val repoDir: File,
        private val workingBranchProvider: Provider<String>,
        private val allowedBranchPrefixes: Set<String>,
    ) : DefaultTask() {
        init {
            group = Wrkx.GROUP
            description =
                "Fetch ${repo.repoName}'s shared bare repository, then merge origin/baseBranch into its selected " +
                "branch worktree; refuses dirty worktrees and aborts conflicting merges"
        }

        /**
         * Fetch and merge baseBranch into the selected clean worktree.
         * Conflicting merges are aborted before the task reports recovery steps.
         *
         * ```bash
         * ./gradlew wrkx-pull-gort
         * ```
         */
        @TaskAction
        fun pull() {
            val result =
                GitOperations.pullRepo(
                    repo,
                    repoDir,
                    workingBranchProvider.get().ifBlank { null },
                    allowedBranchPrefixes,
                )
            logger.lifecycle(result)
            check(!result.startsWith("FAIL")) { "wrkx: $result" }
        }
    }
