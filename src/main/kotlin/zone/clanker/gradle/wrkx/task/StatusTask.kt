package zone.clanker.gradle.wrkx.task

import org.gradle.api.DefaultTask
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.tasks.TaskAction
import zone.clanker.gradle.wrkx.Wrkx
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import zone.clanker.gradle.wrkx.report.ReposCatalogRenderer
import java.io.File
import javax.inject.Inject

/**
 * Generates a workspace status report at `.wrkx/repos.md`.
 *
 * Lists all repos from `wrkx.json` with their clone status,
 * substitution config, category, and ref. Delegates rendering
 * to [ReposCatalogRenderer].
 *
 * ```bash
 * ./gradlew wrkx-status
 * cat .wrkx/repos.md
 * ```
 *
 * @param repos the container of all registered [WorkspaceRepository] entries
 * @param repoDir base directory where repos are cloned (checked for existence)
 * @see PruneTask
 * @see Wrkx.SettingsExtension
 */
@org.gradle.api.tasks.UntrackedTask(because = "Reads external repo state from disk")
abstract class StatusTask
    @Inject
    constructor(
        private val repos: NamedDomainObjectContainer<WorkspaceRepository>,
        private val repoDir: File,
    ) : DefaultTask() {
        init {
            group = Wrkx.GROUP
            description = "Generate workspace status report at ${Wrkx.OUTPUT_DIR}/repos.md"
        }

        /**
         * Generate the Markdown status report listing all workspace repos
         * and write it to `.wrkx/repos.md`.
         *
         * ```bash
         * ./gradlew wrkx-status
         * ```
         */
        @TaskAction
        fun generate() {
            val out = File(project.projectDir, "${Wrkx.OUTPUT_DIR}/repos.md")
            out.parentFile.mkdirs()

            val renderer = ReposCatalogRenderer(repos.toList(), repoDir)
            out.writeText(renderer.render())

            logger.lifecycle("wrkx: Generated repos catalog at ${out.name} (${repos.size} repos)")
        }
    }
