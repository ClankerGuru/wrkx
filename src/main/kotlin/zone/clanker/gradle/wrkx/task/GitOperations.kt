package zone.clanker.gradle.wrkx.task

import org.gradle.api.logging.Logging
import zone.clanker.gradle.wrkx.model.WorkspaceLayout
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Parallel git operations for lifecycle tasks.
 *
 * Runs git commands across multiple repos concurrently using a fixed thread pool.
 */
@Suppress("TooManyFunctions")
internal object GitOperations {
    private val logger = Logging.getLogger(GitOperations::class.java)
    private const val THREAD_POOL_SIZE = 4

    fun runParallel(
        repos: List<WorkspaceRepository>,
        action: String,
        work: (WorkspaceRepository) -> String,
    ) {
        if (repos.isEmpty()) {
            logger.lifecycle("wrkx: No repos to $action.")
            return
        }
        val pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE)
        val futures =
            repos.map { repo ->
                pool.submit(
                    Callable {
                        runCatching { work(repo) }
                            .getOrElse { e -> "FAIL ${repo.repoName}: ${e.message}" }
                    },
                )
            }
        val results = futures.map { it.get() }
        pool.shutdown()
        pool.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.MILLISECONDS)
        results.forEach { logger.lifecycle(it) }
        val failed = results.count { it.startsWith("FAIL") }
        logger.lifecycle("wrkx: $action complete: ${repos.size} repositories, $failed failed")
        check(failed == 0) {
            "wrkx: $action failed for $failed of ${repos.size} repositories. " +
                "Review each FAIL result above, run its recovery command, and retry './gradlew wrkx-$action'."
        }
    }

    fun cloneRepo(repo: WorkspaceRepository, repoDir: File): String {
        val bareDir = WorkspaceLayout.bareRepository(repoDir, repo)
        if (bareDir.exists() && !isBareRepository(bareDir)) {
            return failure(
                repo = repo,
                operation = "clone",
                cause = "The target exists but is not a bare Git repository.",
                path = bareDir,
                recovery = "Move or remove the target, then run './gradlew wrkx-clone-${repo.sanitizedBuildName}'.",
            )
        }
        if (!bareDir.exists()) {
            bareDir.parentFile?.mkdirs()
            val cloneResult = exec("git", "clone", "--bare", repo.path.get().value, bareDir.absolutePath)
            if (cloneResult != 0) {
                return failure(
                    repo = repo,
                    operation = "clone",
                    cause = "git clone --bare exited with code $cloneResult for remote '${repo.path.get().value}'.",
                    path = bareDir,
                    recovery = "Verify remote access, then run './gradlew wrkx-clone-${repo.sanitizedBuildName}'.",
                )
            }
        }
        return configureAndFetch(repo, bareDir)
    }

    private fun configureAndFetch(
        repo: WorkspaceRepository,
        bareDir: File,
    ): String {
        val refspec = "+refs/heads/*:refs/remotes/origin/*"
        val configResult =
            exec("git", "--git-dir=${bareDir.absolutePath}", "config", "remote.origin.fetch", refspec)
        if (configResult != 0) {
            return failure(
                repo = repo,
                operation = "fetch",
                cause = "Could not configure remote.origin.fetch; git exited with code $configResult.",
                path = bareDir,
                recovery =
                    "Repair the bare repository or recreate it with " +
                        "'./gradlew wrkx-clone-${repo.sanitizedBuildName}'.",
            )
        }
        markRemoteBranchesSeen(bareDir)
        val fetchResult = exec("git", "--git-dir=${bareDir.absolutePath}", "fetch", "origin", "--prune")
        return if (fetchResult == 0) {
            markRemoteBranchesSeen(bareDir)
            "OK ${repo.repoName}: fetched and pruned ${bareDir.absolutePath}"
        } else {
            failure(
                repo = repo,
                operation = "fetch",
                cause = "git fetch origin --prune exited with code $fetchResult.",
                path = bareDir,
                recovery = "Verify remote access, then run './gradlew wrkx-clone-${repo.sanitizedBuildName}'.",
            )
        }
    }

    fun fetchRepo(repo: WorkspaceRepository, repoDir: File): String {
        val bareDir = WorkspaceLayout.bareRepository(repoDir, repo)
        if (!bareDir.exists()) {
            return failure(
                repo = repo,
                operation = "fetch",
                cause = "The shared bare repository does not exist.",
                path = bareDir,
                recovery = "Run './gradlew wrkx-clone-${repo.sanitizedBuildName}', then retry the fetch.",
            )
        }
        if (!isBareRepository(bareDir)) {
            return failure(
                repo = repo,
                operation = "fetch",
                cause = "The target exists but is not a bare Git repository.",
                path = bareDir,
                recovery = "Move or remove the target, then recreate it with the repository clone task.",
            )
        }
        return configureAndFetch(repo, bareDir)
    }

    fun createWorktree(
        repo: WorkspaceRepository,
        repoDir: File,
        workingBranch: String,
        allowedPrefixes: Set<String> = WorkspaceLayout.defaultBranchPrefixes,
    ): String {
        require(workingBranch.isNotBlank()) { "wrkx: A working branch is required to create worktrees." }
        val target = WorkspaceLayout.worktree(repoDir, workingBranch, repo, allowedPrefixes)
        val bareDir = WorkspaceLayout.bareRepository(repoDir, repo)
        val cloneResult = cloneRepo(repo, repoDir)
        return when {
            cloneResult.startsWith("FAIL") -> cloneResult
            target.exists() -> "SKIP ${repo.repoName}: worktree already exists at ${target.absolutePath}"
            else -> {
                target.parentFile?.mkdirs()
                exec("git", "--git-dir=${bareDir.absolutePath}", "worktree", "prune")
                addWorktree(repo, bareDir, target, workingBranch)
            }
        }
    }

    private fun isBareRepository(bareDir: File): Boolean =
        execOutput("git", "--git-dir=${bareDir.absolutePath}", "rev-parse", "--is-bare-repository") == "true"

    private fun addWorktree(
        repo: WorkspaceRepository,
        bareDir: File,
        target: File,
        branch: String,
    ): String {
        val localBranch = "refs/heads/$branch"
        val remoteBranch = "refs/remotes/origin/$branch"
        val base = repo.baseBranch.get().value
        val startPoint =
            when {
                refExists(bareDir, remoteBranch) -> "origin/$branch"
                refExists(bareDir, "refs/remotes/origin/$base") -> "origin/$base"
                refExists(bareDir, "refs/heads/$base") -> base
                else ->
                    return failure(
                        repo = repo,
                        operation = "worktree",
                        cause = "Base branch '$base' was not found in local or origin references.",
                        path = target,
                        recovery =
                            "Verify baseBranch in wrkx.json, then run " +
                                "'./gradlew wrkx-clone-${repo.sanitizedBuildName}'.",
                    )
            }
        val command =
            if (refExists(bareDir, localBranch)) {
                arrayOf("git", "--git-dir=${bareDir.absolutePath}", "worktree", "add", target.absolutePath, branch)
            } else {
                arrayOf(
                    "git",
                    "--git-dir=${bareDir.absolutePath}",
                    "worktree",
                    "add",
                    "-b",
                    branch,
                    target.absolutePath,
                    startPoint,
                )
            }
        val result = exec(*command)
        return if (result == 0) {
            if (refExists(bareDir, remoteBranch)) markBranchRemoteSeen(bareDir, branch)
            "OK ${repo.repoName}: created '$branch' worktree at ${target.absolutePath}"
        } else {
            failure(
                repo = repo,
                operation = "worktree",
                cause = "git worktree add exited with code $result for branch '$branch'.",
                path = target,
                recovery =
                    "Run 'git --git-dir=${bareDir.absolutePath} worktree list', " +
                        "resolve the conflict, and retry.",
            )
        }
    }

    private fun refExists(
        bareDir: File,
        reference: String,
    ): Boolean =
        exec(
            "git",
            "--git-dir=${bareDir.absolutePath}",
            "show-ref",
            "--verify",
            "--quiet",
            reference,
        ) == 0

    @Suppress("LongMethod", "ReturnCount")
    fun pullRepo(
        repo: WorkspaceRepository,
        repoDir: File,
        workingBranch: String?,
        allowedPrefixes: Set<String> = WorkspaceLayout.defaultBranchPrefixes,
    ): String {
        val cloneResult = cloneRepo(repo, repoDir)
        if (cloneResult.startsWith("FAIL")) return cloneResult
        if (workingBranch == null) {
            return "OK ${repo.repoName}: fetched bare repository; no active branch worktree was selected"
        }

        val dir = WorkspaceLayout.worktree(repoDir, workingBranch, repo, allowedPrefixes)
        if (!dir.exists()) {
            return failure(
                repo = repo,
                operation = "pull",
                cause = "The '$workingBranch' worktree does not exist.",
                path = dir,
                recovery = "Run './gradlew wrkx-worktree-${repo.sanitizedBuildName} -Pwrkx.branch=$workingBranch'.",
            )
        }

        val dirty = execOutput("git", "-C", dir.absolutePath, "status", "--porcelain")
        if (dirty.isNotBlank()) {
            return failure(
                repo = repo,
                operation = "pull",
                cause = "The worktree has uncommitted changes; no merge was attempted. Dirty paths: $dirty",
                path = dir,
                recovery = "Commit or stash the changes, then retry './gradlew wrkx-pull-${repo.sanitizedBuildName}'.",
            )
        }

        val checkedOutBranch = execOutput("git", "-C", dir.absolutePath, "branch", "--show-current")
        if (checkedOutBranch != workingBranch) {
            return failure(
                repo = repo,
                operation = "pull",
                cause =
                    "Expected branch '$workingBranch' at the selected worktree, but HEAD is " +
                        "'${checkedOutBranch.ifBlank { "detached" }}'. No merge was attempted.",
                path = dir,
                recovery = "Restore the expected branch in this worktree or recreate it with the WRKX worktree task.",
            )
        }

        val base = repo.baseBranch.get().value
        val bareDir = WorkspaceLayout.bareRepository(repoDir, repo)
        if (!refExists(bareDir, "refs/remotes/origin/$base")) {
            return failure(
                repo = repo,
                operation = "pull",
                cause = "Base branch 'origin/$base' does not exist after fetching.",
                path = bareDir,
                recovery = "Correct baseBranch in wrkx.json or create '$base' on the remote, then retry.",
            )
        }

        val mergeResult = exec("git", "-C", dir.absolutePath, "merge", "--no-edit", "origin/$base")
        if (mergeResult == 0) {
            return "OK ${repo.repoName}: merged origin/$base into '$workingBranch' at ${dir.absolutePath}"
        }

        exec("git", "-C", dir.absolutePath, "merge", "--abort")
        return failure(
            repo = repo,
            operation = "pull",
            cause = "origin/$base could not be merged into '$workingBranch'; the merge was aborted.",
            path = dir,
            recovery = "Merge origin/$base manually in this worktree, resolve conflicts, commit, and retry.",
        )
    }

    @Suppress("ReturnCount")
    fun pruneRepo(
        repo: WorkspaceRepository,
        repoDir: File,
        allowedPrefixes: Set<String> = WorkspaceLayout.defaultBranchPrefixes,
    ): String {
        val bareDir = WorkspaceLayout.bareRepository(repoDir, repo)
        if (!bareDir.exists()) return "SKIP ${repo.repoName}: no bare repository exists at ${bareDir.absolutePath}"

        val fetchResult = fetchRepo(repo, repoDir)
        if (fetchResult.startsWith("FAIL")) return fetchResult
        exec("git", "--git-dir=${bareDir.absolutePath}", "worktree", "prune")

        val baseBranch = repo.baseBranch.get().value
        if (!refExists(bareDir, "refs/remotes/origin/$baseBranch")) {
            return failure(
                repo = repo,
                operation = "prune",
                cause = "Base branch 'origin/$baseBranch' does not exist after fetching.",
                path = bareDir,
                recovery = "Correct baseBranch in wrkx.json before pruning worktrees.",
            )
        }

        val worktrees =
            parseWorktrees(
                execOutput("git", "--git-dir=${bareDir.absolutePath}", "worktree", "list", "--porcelain"),
            )
        val results = worktrees.mapNotNull { pruneWorktree(repo, repoDir, bareDir, baseBranch, allowedPrefixes, it) }
        return if (results.isEmpty()) {
            "OK ${repo.repoName}: no WRKX worktrees were eligible for pruning"
        } else {
            "OK ${repo.repoName}:\n${results.joinToString("\n")}"
        }
    }

    @Suppress("LongParameterList", "ReturnCount")
    private fun pruneWorktree(
        repo: WorkspaceRepository,
        repoDir: File,
        bareDir: File,
        baseBranch: String,
        allowedPrefixes: Set<String>,
        worktree: GitWorktree,
    ): String? {
        val branch = worktree.branch ?: return null
        if (branch == baseBranch || branch in WorkspaceLayout.standaloneBranches) return null
        val expected =
            runCatching { WorkspaceLayout.worktree(repoDir, branch, repo, allowedPrefixes).canonicalFile }
                .getOrNull()
                ?: return null
        if (worktree.path.canonicalFile != expected) return null
        if (!branchRemoteWasSeen(bareDir, branch)) return "KEEP $branch: remote branch was never observed by WRKX"
        if (refExists(bareDir, "refs/remotes/origin/$branch")) return "KEEP $branch: origin/$branch still exists"
        if (!isAncestor(bareDir, "refs/heads/$branch", "refs/remotes/origin/$baseBranch")) {
            return "KEEP $branch: local commits are not fully merged into origin/$baseBranch"
        }
        val dirty = execOutput("git", "-C", worktree.path.absolutePath, "status", "--porcelain")
        if (dirty.isNotBlank()) return "KEEP $branch: worktree has uncommitted changes at ${worktree.path.absolutePath}"

        val removeResult =
            exec("git", "--git-dir=${bareDir.absolutePath}", "worktree", "remove", worktree.path.absolutePath)
        if (removeResult != 0) return "KEEP $branch: git worktree remove failed at ${worktree.path.absolutePath}"
        exec("git", "--git-dir=${bareDir.absolutePath}", "update-ref", "-d", "refs/heads/$branch")
        exec("git", "--git-dir=${bareDir.absolutePath}", "config", "--remove-section", "branch.$branch")
        return "PRUNE $branch: removed ${worktree.path.absolutePath} after remote deletion and verified merge"
    }

    private fun isAncestor(bareDir: File, ancestor: String, descendant: String): Boolean =
        exec(
            "git",
            "--git-dir=${bareDir.absolutePath}",
            "merge-base",
            "--is-ancestor",
            ancestor,
            descendant,
        ) == 0

    private fun markRemoteBranchesSeen(bareDir: File) {
        val branches =
            execOutput(
                "git",
                "--git-dir=${bareDir.absolutePath}",
                "for-each-ref",
                "--format=%(refname:short)",
                "refs/heads",
            ).lineSequence()
                .filter(String::isNotBlank)
        branches
            .filter { refExists(bareDir, "refs/remotes/origin/$it") }
            .forEach { markBranchRemoteSeen(bareDir, it) }
    }

    private fun markBranchRemoteSeen(bareDir: File, branch: String) {
        exec(
            "git",
            "--git-dir=${bareDir.absolutePath}",
            "config",
            "branch.$branch.wrkxRemoteSeen",
            "true",
        )
    }

    private fun branchRemoteWasSeen(bareDir: File, branch: String): Boolean =
        execOutput(
            "git",
            "--git-dir=${bareDir.absolutePath}",
            "config",
            "--bool",
            "--get",
            "branch.$branch.wrkxRemoteSeen",
        ) == "true"

    private fun parseWorktrees(output: String): List<GitWorktree> =
        output
            .split("\n\n")
            .mapNotNull { block ->
                val fields =
                    block.lineSequence().associate { line ->
                        line.substringBefore(' ') to line.substringAfter(' ', "")
                    }
                val path = fields["worktree"]?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                GitWorktree(File(path), fields["branch"]?.removePrefix("refs/heads/"))
            }

    private data class GitWorktree(
        val path: File,
        val branch: String?,
    )

    fun checkoutRepo(
        repo: WorkspaceRepository,
        repoDir: File,
        workingBranch: String,
        allowedPrefixes: Set<String> = WorkspaceLayout.defaultBranchPrefixes,
    ): String =
        createWorktree(
            repo = repo,
            repoDir = repoDir,
            workingBranch = workingBranch.ifBlank { repo.baseBranch.get().value },
            allowedPrefixes = allowedPrefixes,
        )

    private fun failure(
        repo: WorkspaceRepository,
        operation: String,
        cause: String,
        path: File,
        recovery: String,
    ): String =
        """
        FAIL ${repo.repoName}: $operation failed
        Repository: ${repo.repoName} (${repo.path.get().value})
        Path: ${path.absolutePath}
        Cause: $cause
        Preservation: Existing repositories, worktrees, commits, and uncommitted changes were not deleted.
        Recovery: $recovery
        """.trimIndent()

    private const val PROCESS_TIMEOUT_SECONDS = 120L

    private fun exec(vararg cmd: String): Int {
        val process =
            ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .also { it.environment()["GIT_TERMINAL_PROMPT"] = "0" }
                .start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
        val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("Process timed out after ${PROCESS_TIMEOUT_SECONDS}s: ${cmd.joinToString(" ")}\nOutput: $output")
        }
        return process.exitValue()
    }

    private fun execOutput(vararg cmd: String): String {
        val process =
            ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .also { it.environment()["GIT_TERMINAL_PROMPT"] = "0" }
                .start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("Process timed out after ${PROCESS_TIMEOUT_SECONDS}s: ${cmd.joinToString(" ")}\nOutput: $output")
        }
        return output
    }
}
