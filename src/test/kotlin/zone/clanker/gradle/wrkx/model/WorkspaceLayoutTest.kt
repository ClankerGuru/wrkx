package zone.clanker.gradle.wrkx.model

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.gradle.testfixtures.ProjectBuilder
import java.io.File

class WorkspaceLayoutTest :
    BehaviorSpec({
        val objects = ProjectBuilder.builder().build().objects
        val repo = objects.newInstance(WorkspaceRepository::class.java, "gort")
        repo.path.set(RepositoryUrl("git@github.com:ClankerGuru/gort.git"))
        val baseDir = File("/workspace/repos")

        given("a repository and branch") {
            then("resolves the shared bare repository") {
                WorkspaceLayout.bareRepository(baseDir, repo) shouldBe File(baseDir, "bare/gort.git")
            }

            then("encodes the branch into a safe worktree path") {
                WorkspaceLayout.worktree(baseDir, "feature/new catalog", repo) shouldBe
                    File(baseDir, "branches/feature%2Fnew%20catalog/gort")
            }
        }
    })
