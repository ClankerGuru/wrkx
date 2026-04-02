package zone.clanker.gradle.wrkx.task

import org.gradle.api.DefaultTask
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.tasks.TaskAction
import zone.clanker.gradle.wrkx.Wrkx
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.File
import javax.inject.Inject

/**
 * Removes cloned repo directories that are not defined in `wrkx.json`.
 *
 * Compares directories on disk in the repos directory against the
 * [repos] container. Any directory that doesn't match a known repo
 * is deleted. Directories that match are never touched.
 *
 * ```bash
 * ./gradlew wrkx-prune
 * ```
 *
 * Fails if the repos directory doesn't exist — run [CloneTask] first.
 *
 * @param repos the container of all registered [WorkspaceRepository] entries
 * @param repoDir base directory where repos are cloned
 * @see StatusTask
 * @see CloneTask
 */
@org.gradle.api.tasks.UntrackedTask(because = "Removes repo directories not in wrkx.json")
abstract class PruneTask
    @Inject
    constructor(
        private val repos: NamedDomainObjectContainer<WorkspaceRepository>,
        private val repoDir: File,
    ) : DefaultTask() {
        init {
            group = Wrkx.GROUP
            description = "Remove repo directories not defined in ${Wrkx.CONFIG_FILE}"
        }

        /**
         * Scan the repos directory and remove any subdirectory that does not correspond
         * to a repository defined in `wrkx.json`.
         *
         * ```bash
         * ./gradlew wrkx-prune
         * ```
         */
        @TaskAction
        fun prune() {
            check(repoDir.exists()) {
                """
                wrkx: Repos directory does not exist at ${repoDir.absolutePath}.
                Run './gradlew ${Wrkx.TASK_CLONE}' to clone repos first.
                If the directory was moved, update the project layout so the repos directory is at the expected location.
                """.trimIndent()
            }

            val knownDirectoryNames = repos.map { it.directoryName }.toSet()

            val dirsOnDisk =
                repoDir
                    .listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?: emptyList()

            val orphans = dirsOnDisk.filter { it.name !in knownDirectoryNames }

            if (orphans.isEmpty()) {
                logger.lifecycle(
                    "wrkx: No orphaned repos found. " +
                        "All directories in ${repoDir.absolutePath} match ${Wrkx.CONFIG_FILE}.",
                )
                return
            }

            orphans.forEach { dir ->
                logger.lifecycle(
                    """
                    wrkx: Removing '${dir.name}' — not defined in ${Wrkx.CONFIG_FILE}.
                    Directory: ${dir.absolutePath}
                    """.trimIndent(),
                )
                dir.deleteRecursively()
            }

            logger.lifecycle("wrkx: Pruned ${orphans.size} orphaned repo(s) from ${repoDir.absolutePath}")
        }
    }
