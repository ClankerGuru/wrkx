package zone.clanker.gradle.wrkx.task

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import zone.clanker.gradle.wrkx.Wrkx
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.File
import javax.inject.Inject

/** Creates or updates one shared bare repository under `workspace-repos/bare`. */
@org.gradle.api.tasks.UntrackedTask(because = "Clones or fetches a single external bare repository")
abstract class CloneTask
    @Inject
    constructor(
        private val repo: WorkspaceRepository,
        private val repoDir: File,
    ) : DefaultTask() {
        init {
            group = Wrkx.GROUP
            description =
                "Create ${repo.repoName}'s shared bare repository under <workspace>-repos/bare " +
                "or fetch/prune it when " +
                "present; does not create, switch, merge, or delete worktrees"
        }

        /** Run `git clone --bare` when missing, otherwise fetch and prune the existing bare repository. */
        @TaskAction
        fun clone() {
            val result = GitOperations.cloneRepo(repo, repoDir)
            logger.lifecycle(result)
            check(!result.startsWith("FAIL")) { "wrkx: $result" }
        }
    }
