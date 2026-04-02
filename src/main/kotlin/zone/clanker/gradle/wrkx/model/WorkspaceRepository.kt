package zone.clanker.gradle.wrkx.model

import org.gradle.api.Named
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * A Gradle-managed repository in the workspace.
 *
 * Created by the [Wrkx.SettingsExtension.repos][zone.clanker.gradle.wrkx.Wrkx.SettingsExtension]
 * container from entries in `wrkx.json`.
 * Implements [Named] so it can be looked up by name in the container:
 *
 * ```kotlin
 * // In settings.gradle.kts:
 * wrkx {
 *     enable(gort, coreModels)
 * }
 * ```
 *
 * Properties are Gradle managed -- declared abstract, backed by Gradle's
 * property system, and set during plugin configuration.
 *
 * @see RepositoryEntry
 * @see zone.clanker.gradle.wrkx.Wrkx.SettingsExtension
 */
public abstract class WorkspaceRepository
    @Inject
    constructor(
        private val name: String,
    ) : Named {
        override fun getName(): String = name

        /** Repository URL or local path used by `git clone`. */
        public abstract val path: Property<RepositoryUrl>

        /** Grouping label for display in the `wrkx-status` report. */
        public abstract val category: Property<String>

        /** Maven artifacts this repo provides locally for dependency substitution. */
        public abstract val substitutions: ListProperty<ArtifactSubstitution>

        /** Master switch for dependency substitution from local source. */
        public abstract val substitute: Property<Boolean>

        /** The repo's default branch (where `wrkx-pull` syncs from). */
        public abstract val baseBranch: Property<GitReference>

        /** Absolute directory where this repo is cloned on disk. */
        public abstract val clonePath: DirectoryProperty

        /** Whether this repo is enabled for composite build inclusion. */
        public var enabled: Boolean = false
            private set

        /** Enable or disable this repo for composite build inclusion. */
        public fun enable(value: Boolean = true) {
            enabled = value
        }

        /** Human-readable name of this repo (same as the container registration name). */
        public val repoName: String get() = name

        /** Directory name derived from the repo URL, used for the clone target. */
        public val directoryName: String get() = path.get().directoryName

        /**
         * Gradle-safe build name derived from [directoryName].
         *
         * Non-alphanumeric characters are replaced with hyphens and the result
         * is lowercased. Used as the `includeBuild` name and in per-repo task names.
         *
         * ```kotlin
         * // For a repo with path "git@github.com:org/my-lib.git":
         * repo.sanitizedBuildName  // "my-lib"
         * ```
         */
        public val sanitizedBuildName: String
            get() {
                val raw = directoryName
                val sanitized =
                    raw
                        .replace(Regex("[^a-zA-Z0-9-]"), "-")
                        .replace(Regex("-+"), "-")
                        .trim('-')
                        .lowercase()
                require(sanitized.isNotBlank()) {
                    """
                    Cannot derive a valid Gradle build name for repo '$repoName'.
                    The directory name '$raw' sanitizes to an empty string.
                    Rename the repo or use a name with at least one alphanumeric character.
                    """.trimIndent()
                }
                return sanitized
            }
    }
