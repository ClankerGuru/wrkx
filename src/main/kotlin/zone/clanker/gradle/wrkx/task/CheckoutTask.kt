package zone.clanker.gradle.wrkx.task

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskAction
import zone.clanker.gradle.wrkx.Wrkx
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.File
import javax.inject.Inject

/**
 * Compatibility alias that creates the configured branch worktree for one repository.
 *
 * If [workingBranch] is set in the DSL, creates or reuses that branch's worktree.
 * If [workingBranch] is not set, creates or reuses the [WorkspaceRepository.baseBranch] worktree.
 *
 * ```bash
 * ./gradlew wrkx-checkout-gort   # checkout gort's configured branch
 * ./gradlew wrkx-checkout         # checkout all (lifecycle task)
 * ```
 *
 * @param repo the repository to checkout
 * @param repoDir base directory where repos are cloned
 * @param workingBranchProvider lazy branch override from DSL (empty string means unset)
 * @see CloneTask
 * @see PullTask
 */
@org.gradle.api.tasks.UntrackedTask(because = "Creates or reuses an external Git worktree")
abstract class CheckoutTask
    @Inject
    constructor(
        private val repo: WorkspaceRepository,
        private val repoDir: File,
        private val workingBranchProvider: Provider<String>,
        private val allowedBranchPrefixes: Set<String>,
    ) : DefaultTask() {
        private val workingBranch: String?
            get() = workingBranchProvider.get().ifBlank { null }

        init {
            group = Wrkx.GROUP
            description =
                "Compatibility alias for wrkx-worktree-${repo.sanitizedBuildName}; " +
                "create or reuse ${repo.repoName}'s " +
                "selected branch worktree without changing another worktree"
        }

        /**
         * Create or reuse the target branch worktree without switching another worktree.
         *
         * ```bash
         * ./gradlew wrkx-checkout-gort
         * ```
         */
        @TaskAction
        fun checkout() {
            val result =
                GitOperations.checkoutRepo(
                    repo,
                    repoDir,
                    workingBranch ?: repo.baseBranch.get(),
                    allowedBranchPrefixes,
                )
            logger.lifecycle(result)
            check(!result.startsWith("FAIL")) { "wrkx: $result" }
        }
    }
