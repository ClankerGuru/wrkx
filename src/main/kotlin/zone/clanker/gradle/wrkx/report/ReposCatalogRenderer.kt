package zone.clanker.gradle.wrkx.report

import zone.clanker.gradle.wrkx.Wrkx
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.File

/**
 * Renders workspace repository status as a Markdown catalog.
 *
 * Produces three sections: summary table, category breakdown,
 * and machine-readable config. Used by [StatusTask][zone.clanker.gradle.wrkx.task.StatusTask]
 * to generate `.wrkx/repos.md`.
 *
 * ```kotlin
 * val renderer = ReposCatalogRenderer(repos, repoDir)
 * val markdown = renderer.render()
 * File(".wrkx/repos.md").writeText(markdown)
 * ```
 *
 * @property repos the list of all workspace repos to render
 * @property repoDir base directory where repos are cloned (used to check clone status)
 * @see zone.clanker.gradle.wrkx.task.StatusTask
 */
internal class ReposCatalogRenderer(
    private val repos: List<WorkspaceRepository>,
    private val repoDir: File,
) {
    /**
     * Render the full Markdown catalog as a single string.
     *
     * Returns a "no repos" message when the list is empty, otherwise
     * produces a summary table, category breakdown, and machine-readable section.
     *
     * @return the complete Markdown report content
     */
    fun render(): String {
        if (repos.isEmpty()) {
            return """
                |# Workspace Repos
                |
                |No repos configured. Add repos to `${Wrkx.CONFIG_FILE}`.
                """.trimMargin() + "\n"
        }

        val grouped = repos.groupBy { it.category.get().ifBlank { "default" } }
        val total = repos.size
        val cloned = repos.count { isCloned(it) }
        val substituted = repos.count { it.substitute.get() }
        val enabled = repos.count { it.enabled }

        return buildString {
            append(
                """
                |# Workspace Repos
                |
                |**$total repos** -- $cloned cloned on disk, $substituted with substitution, $enabled enabled
                |
                |## All Repos
                |
                || # | Name | Path | Category | Cloned | Enabled | Substitute | Base Branch | Substitutions |
                |---|------|------|----------|--------|---------|------------|-------------|---------------|
                |
                """.trimMargin(),
            )
            appendSummaryRows(grouped)
            appendLine()
            appendCategoryBreakdown(grouped)
            appendMachineReadable()
        }
    }

    private fun isCloned(repo: WorkspaceRepository): Boolean =
        File(repoDir, repo.directoryName).exists()

    private fun StringBuilder.appendSummaryRows(
        grouped: Map<String, List<WorkspaceRepository>>,
    ) {
        val rows =
            grouped.entries
                .sortedBy { it.key }
                .flatMap { (category, categoryRepos) ->
                    categoryRepos.sortedBy { it.repoName }.map { it to category }
                }
        rows.forEachIndexed { index, (repo, category) ->
            val onDisk = if (isCloned(repo)) "yes" else "no"
            val enabledStr = if (repo.enabled) "yes" else "no"
            val sub = if (repo.substitute.get()) "yes" else "no"
            val baseBranch = repo.baseBranch.get()
            val pathStr = repo.path.get().value
            val subs =
                repo.substitutions.get().let { list ->
                    if (list.isNotEmpty()) {
                        list.joinToString(", ") { "`$it`" }
                    } else {
                        "--"
                    }
                }
            appendLine(
                "| ${index + 1} | `${repo.repoName}` | `$pathStr` | $category" +
                    " | $onDisk | $enabledStr | $sub | `$baseBranch` | $subs |",
            )
        }
    }

    private fun StringBuilder.appendCategoryBreakdown(
        grouped: Map<String, List<WorkspaceRepository>>,
    ) {
        appendLine("## By Category")
        appendLine()
        for ((category, categoryRepos) in grouped.entries.sortedBy { it.key }) {
            appendLine("### $category")
            appendLine()
            categoryRepos.sortedBy { it.repoName }.forEach { repo ->
                appendRepoEntry(repo)
            }
            appendLine()
        }
    }

    private fun StringBuilder.appendRepoEntry(repo: WorkspaceRepository) {
        val status = if (isCloned(repo)) "cloned" else "not cloned"
        val enabledStr = if (repo.enabled) "enabled" else "disabled"
        val extra = if (repo.substitute.get()) ", substitution on" else ""
        val baseBranch = repo.baseBranch.get()
        appendLine(
            "- **${repo.repoName}** (${repo.path.get()}) -- $status, $enabledStr$extra" +
                " -- baseBranch: `$baseBranch`",
        )
        if (repo.substitute.get()) {
            for (sub in repo.substitutions.get()) {
                appendLine("  - substitutes: `$sub`")
            }
        }
    }

    private fun StringBuilder.appendMachineReadable() {
        appendLine("## Machine-Readable Config")
        appendLine()
        appendLine("```")
        for (repo in repos.sortedBy { it.repoName }) {
            val subs = repo.substitutions.get().joinToString(";")
            appendLine(
                "name=${repo.repoName}" +
                    " path=${repo.path.get()}" +
                    " category=${repo.category.get()}" +
                    " cloned=${isCloned(repo)}" +
                    " enabled=${repo.enabled}" +
                    " substitute=${repo.substitute.get()}" +
                    " baseBranch=${repo.baseBranch.get()}" +
                    " substitutions=[$subs]",
            )
        }
        appendLine("```")
    }
}
