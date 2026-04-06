package zone.clanker.gradle.wrkx.task

import org.gradle.api.DefaultTask
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.tasks.TaskAction
import zone.clanker.gradle.wrkx.Wrkx
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Checks out the configured branch in all workspace repositories in parallel.
 *
 * If [workingBranch] is set, checks out that branch for each repo (creating it
 * from [WorkspaceRepository.baseBranch] if it does not exist).
 * If [workingBranch] is not set, checks out [WorkspaceRepository.baseBranch].
 *
 * Fails for any repo with a dirty working directory -- never auto-stashes.
 * Reports per-repo success/failure and a summary at the end.
 *
 * ```bash
 * ./gradlew wrkx-checkout   # checkout all repos in parallel
 * ```
 *
 * @param repos the container of all registered [WorkspaceRepository] entries
 * @param repoDir base directory where repos are cloned
 * @param workingBranchRaw branch override from DSL (empty string means unset)
 * @see CheckoutTask
 * @see ParallelCloneTask
 * @see ParallelPullTask
 */
@org.gradle.api.tasks.UntrackedTask(because = "Checks out branches in all repositories in parallel")
abstract class ParallelCheckoutTask
    @Inject
    constructor(
        private val repos: NamedDomainObjectContainer<WorkspaceRepository>,
        private val repoDir: File,
        private val workingBranchRaw: String,
    ) : DefaultTask() {
        private val workingBranch: String? = workingBranchRaw.ifBlank { null }

        init {
            group = Wrkx.GROUP
            description = "Checkout workingBranch (or baseBranch) across all repos (parallel)"
        }

        /**
         * Checkout the target branch in all workspace repositories in parallel.
         *
         * Uses a thread pool of 4 threads. Each repo checkout runs as a separate
         * [Callable] that returns a [RepoResult] indicating success or failure.
         *
         * ```bash
         * ./gradlew wrkx-checkout
         * ```
         */
        @TaskAction
        fun checkoutAll() {
            val repoList = repos.toList()
            if (repoList.isEmpty()) {
                logger.lifecycle("wrkx: No repos defined in ${Wrkx.CONFIG_FILE}")
                return
            }

            val executor = Executors.newFixedThreadPool(POOL_SIZE)
            val futures =
                repoList.map { repo ->
                    executor.submit(
                        Callable {
                            runCatching { checkoutRepo(repo) }
                                .fold(
                                    onSuccess = { RepoResult(repo.repoName, true, it) },
                                    onFailure = { RepoResult(repo.repoName, false, it.message ?: "Unknown error") },
                                )
                        },
                    )
                }

            val results = futures.map { it.get() }
            executor.shutdown()

            logSummary(results)

            val failures = results.filter { !it.success }
            if (failures.isNotEmpty()) {
                error(
                    "wrkx: ${failures.size} repo(s) failed to checkout: " +
                        failures.joinToString(", ") { it.name },
                )
            }
        }

        private fun checkoutRepo(repo: WorkspaceRepository): String {
            val dir = File(repoDir, repo.directoryName)
            if (!dir.exists()) {
                return "SKIP -- not cloned at ${dir.absolutePath}"
            }

            checkDirtyWorkingDirectory(repo, dir)

            val targetBranch = workingBranch ?: repo.baseBranch.get().value

            val checkoutResult = runGitCommand(dir, "git", "checkout", targetBranch)
            if (checkoutResult.exitCode == 0) {
                return "Checked out '$targetBranch'"
            }

            if (workingBranch != null) {
                val baseBranch = repo.baseBranch.get().value
                val createResult = runGitCommand(dir, "git", "checkout", "-b", targetBranch, baseBranch)
                check(createResult.exitCode == 0) {
                    "Failed to create branch '$targetBranch' from '$baseBranch': ${createResult.output}"
                }
                return "Created and checked out '$targetBranch' from '$baseBranch'"
            }

            error("Failed to checkout '$targetBranch': ${checkoutResult.output}")
        }

        private fun checkDirtyWorkingDirectory(repo: WorkspaceRepository, dir: File) {
            val result = runGitCommand(dir, "git", "status", "--porcelain")
            check(result.output.isBlank()) {
                "Working directory is dirty for '${repo.repoName}' at ${dir.absolutePath}. " +
                    "Commit or stash your changes before running checkout."
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

        private fun logSummary(results: List<RepoResult>) {
            val succeeded = results.count { it.success }
            val failed = results.count { !it.success }

            results.forEach { result ->
                val icon = if (result.success) "OK" else "FAIL"
                logger.lifecycle("wrkx: [$icon] ${result.name} -- ${result.message}")
            }
            logger.lifecycle(
                "wrkx: Checkout complete. $succeeded succeeded, $failed failed, ${results.size} total.",
            )
        }

        private data class RepoResult(
            val name: String,
            val success: Boolean,
            val message: String,
        )

        private companion object {
            private const val POOL_SIZE = 4
        }
    }
