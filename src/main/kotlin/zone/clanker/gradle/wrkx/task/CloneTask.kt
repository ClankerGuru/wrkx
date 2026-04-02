package zone.clanker.gradle.wrkx.task

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import zone.clanker.gradle.wrkx.Wrkx
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.File
import javax.inject.Inject

/**
 * Clones a single repository from the URL defined in [WorkspaceRepository.path].
 *
 * Registered per-repo as `wrkx-clone-<name>` by [Wrkx.SettingsPlugin].
 * Runs `git clone <url> <target>` via [ExecOperations].
 * Skips if the target directory already exists.
 * After cloning, checks out the baseBranch from [WorkspaceRepository.baseBranch]
 * if it's not `main`.
 *
 * ```bash
 * ./gradlew wrkx-clone-gort     # clone just gort
 * ./gradlew wrkx-clone           # clone all (lifecycle task)
 * ```
 *
 * @param repo the repository to clone
 * @param repoDir base directory where repos are cloned
 * @param execOps Gradle-injected process execution service
 * @see Wrkx.SettingsPlugin
 * @see PullTask
 * @see CheckoutTask
 */
@org.gradle.api.tasks.UntrackedTask(because = "Clones a single external repository")
abstract class CloneTask
    @Inject
    constructor(
        private val repo: WorkspaceRepository,
        private val repoDir: File,
        private val execOps: ExecOperations,
    ) : DefaultTask() {
        init {
            group = Wrkx.GROUP
            description = "Clone ${repo.repoName} from its remote into the repos directory"
        }

        /**
         * Clone the repository from its remote URL into the repos directory.
         *
         * Skips if the target directory already exists. After cloning, checks
         * out the baseBranch if it differs from `main`.
         *
         * ```bash
         * ./gradlew wrkx-clone-gort
         * ```
         */
        @TaskAction
        fun clone() {
            val target = File(repoDir, repo.directoryName)
            if (target.exists()) {
                logger.lifecycle(
                    """
                    wrkx: SKIP ${repo.repoName} -- directory already exists at ${target.absolutePath}.
                    To re-clone, delete the directory first: rm -rf ${target.absolutePath}
                    """.trimIndent(),
                )
                return
            }

            val url = repo.path.get().value
            target.parentFile?.mkdirs()
            logger.lifecycle("wrkx: Cloning ${repo.repoName} from $url into ${target.absolutePath}...")

            execOps.exec {
                it.commandLine("git", "clone", url, target.absolutePath)
            }

            logger.lifecycle("wrkx: Cloned ${repo.repoName}")
            checkoutBaseBranch(target)
        }

        private fun checkoutBaseBranch(targetDir: File) {
            val branch = repo.baseBranch.get().value
            if (branch.isBlank() || branch == "main") return

            val checkout =
                ProcessBuilder("git", "checkout", branch)
                    .directory(targetDir)
                    .redirectErrorStream(true)
                    .start()
            checkout.inputStream.bufferedReader().readText()
            if (checkout.waitFor() == 0) {
                logger.lifecycle("wrkx: Checked out baseBranch '$branch' for ${repo.repoName}")
                return
            }

            logger.warn(
                """
                wrkx: baseBranch '$branch' does not exist as a remote branch for ${repo.repoName}.
                Creating a new local branch '$branch' from the default branch instead.
                If this is unexpected, verify the 'baseBranch' in ${Wrkx.CONFIG_FILE} for this repo.
                """.trimIndent(),
            )
            val create =
                ProcessBuilder("git", "checkout", "-b", branch)
                    .directory(targetDir)
                    .redirectErrorStream(true)
                    .start()
            val output = create.inputStream.bufferedReader().readText()
            check(create.waitFor() == 0) {
                """
                wrkx: Failed to create branch '$branch' for ${repo.repoName}.
                Git output: $output
                Check that the repository at ${targetDir.absolutePath} is a valid git checkout.
                """.trimIndent()
            }
            logger.lifecycle("wrkx: Created local branch '$branch' for ${repo.repoName}")
        }
    }
