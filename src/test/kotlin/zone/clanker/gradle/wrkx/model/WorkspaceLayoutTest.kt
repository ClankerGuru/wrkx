package zone.clanker.gradle.wrkx.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testfixtures.ProjectBuilder
import java.io.File

class WorkspaceLayoutTest :
    BehaviorSpec({
        val objects = ProjectBuilder.builder().build().objects
        val repo = objects.newInstance(WorkspaceRepository::class.java, "gort")
        repo.path.set(RepositoryUrl("git@github.com:ClankerGuru/gort.git"))
        val baseDir = File("/workspace/workspace-repos")

        given("the shared bare repository") {
            then("it is stored under bare") {
                WorkspaceLayout.bareRepository(baseDir, repo) shouldBe File(baseDir, "bare/gort.git")
            }
        }

        given("built-in prefixed branches") {
            WorkspaceLayout.defaultBranchPrefixes.forEach { prefix ->
                then("$prefix maps directly to its prefix and branch directories") {
                    WorkspaceLayout.worktree(baseDir, "$prefix/new-checkout-flow", repo) shouldBe
                        File(baseDir, "$prefix/new-checkout-flow/gort")
                }
            }
        }

        given("standalone branches") {
            WorkspaceLayout.standaloneBranches.forEach { branch ->
                then("$branch maps directly to its own directory") {
                    WorkspaceLayout.worktree(baseDir, branch, repo) shouldBe File(baseDir, "$branch/gort")
                }
            }
        }

        given("uppercase prefixed branches") {
            then("uppercase is preserved in the Git branch name and the filesystem prefix is normalized") {
                WorkspaceLayout.worktree(baseDir, "Feature/NewCheckout-UI", repo) shouldBe
                    File(baseDir, "feature/NewCheckout-UI/gort")
            }
        }

        given("an additional allowed prefix") {
            then("it maps to the same hierarchical layout") {
                WorkspaceLayout.worktree(
                    baseDir,
                    "experiment/new-renderer",
                    repo,
                    WorkspaceLayout.defaultBranchPrefixes + "experiment",
                ) shouldBe File(baseDir, "experiment/new-renderer/gort")
            }
        }

        given("invalid working branches") {
            val invalidBranches =
                listOf(
                    "",
                    " ",
                    "feature",
                    "feature/",
                    "/new-name",
                    "unknown/new-name",
                    "feature/new_name",
                    "feature/new name",
                    "feature/-new-name",
                    "feature/new-name-",
                    "feature/new--name",
                    "feature/one/two",
                    "../feature",
                    "feature/../main",
                    " main",
                    "main ",
                )

            invalidBranches.forEach { branch ->
                then("'$branch' is rejected") {
                    shouldThrow<IllegalArgumentException> {
                        WorkspaceLayout.worktree(baseDir, branch, repo)
                    }
                }
            }
        }

        given("invalid additional prefixes") {
            val invalidPrefixes =
                listOf(
                    "",
                    " ",
                    "Feature",
                    "new_prefix",
                    "new prefix",
                    "-new",
                    "new-",
                    "new--prefix",
                    "bare",
                    "main",
                    "dev",
                )

            invalidPrefixes.forEach { prefix ->
                then("'$prefix' is rejected") {
                    shouldThrow<IllegalArgumentException> {
                        WorkspaceLayout.validatePrefix(prefix)
                    }
                }
            }

            then("the error identifies an unknown branch prefix") {
                val error =
                    shouldThrow<IllegalArgumentException> {
                        WorkspaceLayout.branchDirectory("unknown/new-name")
                    }
                error.message shouldContain "Allowed prefixes"
            }
        }
    })
