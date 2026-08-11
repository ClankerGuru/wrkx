package zone.clanker.gradle.wrkx.task

import org.gradle.api.DefaultTask
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.tasks.TaskAction
import zone.clanker.gradle.wrkx.Wrkx
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.File
import javax.inject.Inject

/**
 * Removes clean WRKX worktrees after their remote branch is deleted and fully merged.
 *
 * ```bash
 * ./gradlew wrkx-prune
 * ```
 *
 * @param repos the container of all registered [WorkspaceRepository] entries
 * @param repoDir base directory containing shared bare repositories and worktrees
 * @param allowedBranchPrefixes branch prefixes managed by this workspace
 * @see StatusTask
 * @see CloneTask
 */
@org.gradle.api.tasks.UntrackedTask(because = "Removes repo directories not in wrkx.json")
abstract class PruneTask
    @Inject
    constructor(
        private val repos: NamedDomainObjectContainer<WorkspaceRepository>,
        private val repoDir: File,
        private val allowedBranchPrefixes: Set<String>,
    ) : DefaultTask() {
        init {
            group = Wrkx.GROUP
            description =
                "Fetch every repository, then remove only clean WRKX worktrees whose branch was previously observed " +
                "on origin, is now deleted there, and is fully merged into origin/baseBranch"
        }

        /**
         * Fetch remote state and safely remove worktrees that satisfy every pruning condition.
         */
        @TaskAction
        fun prune() {
            if (!repoDir.exists()) {
                logger.lifecycle("wrkx: No repository directory exists at ${repoDir.absolutePath}; nothing to prune.")
                return
            }
            GitOperations.runParallel(repos.toList(), "prune") { repo ->
                GitOperations.pruneRepo(repo, repoDir, allowedBranchPrefixes)
            }
        }
    }
