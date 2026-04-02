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
 *   "category": "ui",
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
 * @property category grouping label for display in `wrkx-status`
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
    @SerialName("category")
    val category: String = "",
    @SerialName("substitute")
    val substitute: Boolean = false,
    @SerialName("substitutions")
    val substitutions: List<ArtifactSubstitution> = emptyList(),
    @SerialName("baseBranch")
    val baseBranch: GitReference = GitReference("main"),
) {
    /** Directory name derived from [path], used as the clone target folder name. */
    val directoryName: String get() = path.directoryName
}
