package zone.clanker.gradle.wrkx.model

import java.io.File

/** Filesystem layout for shared bare repositories and branch worktrees. */
internal object WorkspaceLayout {
    val defaultBranchPrefixes: Set<String> = setOf("feature", "bugfix", "custom", "poc", "release")
    val standaloneBranches: Set<String> = setOf("main", "dev")

    private val validPrefix = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    private val validBranchName = Regex("^[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*$")
    private val reservedDirectories = standaloneBranches + setOf("bare", "branches")

    fun bareRepository(
        repoDir: File,
        repo: WorkspaceRepository,
    ): File = File(repoDir, "bare/${repo.directoryName}.git")

    fun worktree(
        repoDir: File,
        branch: String,
        repo: WorkspaceRepository,
        allowedPrefixes: Set<String> = defaultBranchPrefixes,
    ): File = File(repoDir, "${branchDirectory(branch, allowedPrefixes)}/${repo.directoryName}")

    fun validatePrefix(prefix: String) {
        require(validPrefix.matches(prefix)) {
            "wrkx: Branch prefix '$prefix' must be lowercase kebab-case."
        }
        require(prefix !in reservedDirectories) {
            "wrkx: Branch prefix '$prefix' is reserved and cannot be added."
        }
    }

    internal fun branchDirectory(
        branch: String,
        allowedPrefixes: Set<String> = defaultBranchPrefixes,
    ): String {
        require(branch == branch.trim() && branch.isNotEmpty()) {
            "wrkx: Working branch must not be blank or contain surrounding whitespace."
        }
        if (branch in standaloneBranches) return branch

        val parts = branch.split('/')
        require(parts.size == BRANCH_PART_COUNT) {
            "wrkx: Branch '$branch' must be '<prefix>/<kebab-case-name>' or one of $standaloneBranches."
        }
        val (prefix, name) = parts
        val normalizedPrefix = prefix.lowercase()
        require(normalizedPrefix in allowedPrefixes) {
            "wrkx: Branch prefix '$prefix' is not allowed. Allowed prefixes: ${allowedPrefixes.sorted()}."
        }
        require(validBranchName.matches(name)) {
            "wrkx: Branch name '$name' must contain only letters, numbers, and single hyphens."
        }
        return "$normalizedPrefix/$name"
    }

    private const val BRANCH_PART_COUNT = 2
}
