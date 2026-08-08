package zone.clanker.gradle.wrkx.model

import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Filesystem layout for shared bare repositories and branch worktrees. */
internal object WorkspaceLayout {
    fun bareRepository(
        repoDir: File,
        repo: WorkspaceRepository,
    ): File = File(repoDir, "bare/${repo.directoryName}.git")

    fun worktree(
        repoDir: File,
        branch: String,
        repo: WorkspaceRepository,
    ): File = File(repoDir, "branches/${branchKey(branch)}/${repo.directoryName}")

    internal fun branchKey(branch: String): String =
        URLEncoder.encode(branch, StandardCharsets.UTF_8).replace("+", "%20")
}
