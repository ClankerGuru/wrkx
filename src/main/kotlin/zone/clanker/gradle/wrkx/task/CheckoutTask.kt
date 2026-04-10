package zone.clanker.gradle.wrkx.task

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskAction
import zone.clanker.gradle.wrkx.Wrkx
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.File
import javax.inject.Inject

/**
 * Checks out the configured branch in a single repository.
 *
 * If [workingBranch] is set in the DSL, checks out that branch for enabled repos
 * (creates it from [WorkspaceRepository.baseBranch] if it doesn't exist).
 * If [workingBranch] is not set, checks out [WorkspaceRepository.baseBranch].
 *
 * Fails if the working directory is dirty -- never auto-stashes.
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
@org.gradle.api.tasks.UntrackedTask(because = "Checks out a branch in a single repository")
abstract class CheckoutTask
    @Inject
    constructor(
        private val repo: WorkspaceRepository,
        private val repoDir: File,
        private val workingBranchProvider: Provider<String>,
    ) : DefaultTask() {
        private val workingBranch: String?
            get() = workingBranchProvider.get().ifBlank { null }

        init {
            group = Wrkx.GROUP
            description = "Checkout workingBranch (or baseBranch) for ${repo.repoName}"
        }

        /**
         * Checkout the target branch in the repository working directory.
         *
         * If [workingBranch][Wrkx.SettingsExtension.workingBranch] is set, creates it from
         * baseBranch when it does not exist. Fails if uncommitted changes are present.
         *
         * ```bash
         * ./gradlew wrkx-checkout-gort
         * ```
         */
        @TaskAction
        fun checkout() {
            val dir = File(repoDir, repo.directoryName)
            if (!dir.exists()) {
                logger.warn(
                    "wrkx: Repository '${repo.repoName}' not cloned at ${dir.absolutePath}. " +
                        "Run './gradlew ${Wrkx.TASK_CLONE}-${repo.sanitizedBuildName}' to clone it first.",
                )
                return
            }

            checkDirtyWorkingDirectory(dir)

            val targetBranch = workingBranch ?: repo.baseBranch.get().value
            logger.lifecycle("wrkx: Checking out '$targetBranch' in ${repo.repoName}...")

            val checkoutResult = runGitCommand(dir, "git", "checkout", targetBranch)
            if (checkoutResult.exitCode == 0) {
                logger.lifecycle("wrkx: Checked out '$targetBranch' in ${repo.repoName}")
                return
            }

            if (workingBranch != null) {
                val baseBranch = repo.baseBranch.get().value
                logger.lifecycle(
                    "wrkx: Branch '$targetBranch' does not exist for ${repo.repoName}. " +
                        "Creating from '$baseBranch'...",
                )
                val createResult = runGitCommand(dir, "git", "checkout", "-b", targetBranch, baseBranch)
                check(createResult.exitCode == 0) {
                    """
                    wrkx: Failed to create branch '$targetBranch' from '$baseBranch' for ${repo.repoName}.
                    Git output: ${createResult.output}
                    """.trimIndent()
                }
                logger.lifecycle("wrkx: Created and checked out '$targetBranch' in ${repo.repoName}")
            } else {
                error(
                    """
                    wrkx: Failed to checkout '$targetBranch' in ${repo.repoName}.
                    Git output: ${checkoutResult.output}
                    """.trimIndent(),
                )
            }
        }

        private fun checkDirtyWorkingDirectory(dir: File) {
            val result = runGitCommand(dir, "git", "status", "--porcelain")
            check(result.output.isBlank()) {
                """
                wrkx: Working directory is dirty for '${repo.repoName}' at ${dir.absolutePath}.
                Commit or stash your changes before running checkout.
                Dirty files:
                ${result.output}
                """.trimIndent()
            }
        }

        private data class GitResult(
            val exitCode: Int,
            val output: String,
        )

        private fun runGitCommand(
            dir: File,
            vararg command: String,
        ): GitResult {
            val process =
                ProcessBuilder(*command)
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start()
            val output =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            val exitCode = process.waitFor()
            return GitResult(exitCode, output)
        }
    }
