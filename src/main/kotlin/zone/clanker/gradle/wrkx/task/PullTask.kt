package zone.clanker.gradle.wrkx.task

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import zone.clanker.gradle.wrkx.Wrkx
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.File
import javax.inject.Inject

/**
 * Pulls the latest changes for a single repository using `git pull --ff-only`.
 *
 * Registered per-repo as `wrkx-pull-<name>` by [Wrkx.SettingsPlugin].
 * Fails if the repo directory doesn't exist -- run [CloneTask] first.
 *
 * ```bash
 * ./gradlew wrkx-pull-gort      # pull just gort
 * ./gradlew wrkx-pull            # pull all (lifecycle task)
 * ```
 *
 * @param repo the repository to pull
 * @param repoDir base directory where repos are cloned
 * @param execOps Gradle-injected process execution service
 * @see CloneTask
 * @see CheckoutTask
 */
@org.gradle.api.tasks.UntrackedTask(because = "Pulls latest changes for a single repository")
abstract class PullTask
    @Inject
    constructor(
        private val repo: WorkspaceRepository,
        private val repoDir: File,
        private val execOps: ExecOperations,
    ) : DefaultTask() {
        init {
            group = Wrkx.GROUP
            description = "Pull baseBranch for ${repo.repoName} from its remote"
        }

        /**
         * Fetch and fast-forward merge the baseBranch from the remote.
         *
         * Skips repos with no configured remote. Fails if the repo directory
         * does not exist on disk.
         *
         * ```bash
         * ./gradlew wrkx-pull-gort
         * ```
         */
        @TaskAction
        fun pull() {
            val dir = File(repoDir, repo.directoryName)
            check(dir.exists()) {
                """
                wrkx: Cannot pull '${repo.repoName}' -- directory not found at ${dir.absolutePath}.
                Run './gradlew ${Wrkx.TASK_CLONE}-${repo.sanitizedBuildName}' to clone it first.
                """.trimIndent()
            }

            if (!hasRemote(dir)) {
                logger.lifecycle("wrkx: Skipping pull for ${repo.repoName} — no remote configured")
                return
            }

            val base = repo.baseBranch.get().value
            logger.lifecycle("wrkx: Pulling ${repo.repoName} (baseBranch: $base)...")
            execOps.exec {
                it.workingDir = dir
                it.commandLine("git", "fetch", "origin", base)
            }
            execOps.exec {
                it.workingDir = dir
                it.commandLine("git", "merge", "--ff-only", "origin/$base")
            }
            logger.lifecycle("wrkx: Pulled ${repo.repoName} from $base")
        }

        private fun hasRemote(dir: File): Boolean {
            val output = java.io.ByteArrayOutputStream()
            execOps.exec {
                it.workingDir = dir
                it.commandLine("git", "remote")
                it.standardOutput = output
            }
            return output.toString().trim().isNotEmpty()
        }
    }
