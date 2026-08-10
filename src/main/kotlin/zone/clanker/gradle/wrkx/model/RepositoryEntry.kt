package zone.clanker.gradle.wrkx.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single repository entry deserialized from `wrkx.json`.
 *
 * ```json
 * {
 *   "name": "gort",
 *   "path": "git@github.com:org/repo.git",
 *   "categories": ["checkout", "ui"],
 *   "substitute": true,
 *   "substitutions": ["zone.clanker:gort-tokens,tokens"],
 *   "baseBranch": "main"
 * }
 * ```
 *
 * [name] is a user-chosen unique identifier (valid Kotlin identifier).
 * [path] is the repository URL or local path (`git clone` target).
 * All other fields have defaults.
 * The plugin reads this at settings evaluation time and creates
 * a [WorkspaceRepository] for each entry in the [Wrkx.SettingsExtension.repos] container.
 *
 * @property name user-chosen unique identifier for this repo
 * @property path repository URL or path (any format `git clone` accepts)
 * @property categories grouping labels for display in `wrkx-status`
 * @property category deprecated singular grouping label retained for existing configuration files
 * @property substitute master switch for dependency substitution
 * @property substitutions Maven artifacts this repo produces locally
 * @property baseBranch the repo's default branch
 * @see WorkspaceRepository
 */
@Serializable
data class RepositoryEntry(
    @SerialName("name")
    val name: String,
    @SerialName("path")
    val path: RepositoryUrl,
    @Deprecated("Use categories")
    @SerialName("category")
    val category: String = "",
    @SerialName("substitute")
    val substitute: Boolean = false,
    @SerialName("substitutions")
    val substitutions: List<ArtifactSubstitution> = emptyList(),
    @SerialName("baseBranch")
    val baseBranch: GitReference = GitReference("main"),
    @SerialName("categories")
    val categories: List<String> = emptyList(),
) {
    /** Directory name derived from [path], used as the clone target folder name. */
    val directoryName: String get() = path.directoryName

    /** Normalized categories from the canonical list and deprecated singular value. */
    @Suppress("DEPRECATION")
    val effectiveCategories: List<String>
        get() =
            (categories + category)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
}
