package zone.clanker.gradle.wrkx.task

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.gradle.testfixtures.ProjectBuilder
import java.io.File

/**
 * Tests for [GitOperations] -- parallel git operations for lifecycle tasks.
 *
 * Uses local bare git repos as clone sources (no network, no Docker).
 *
 * Verifies:
 * - cloneRepo clones from a local bare repo
 * - cloneRepo skips when target directory already exists
 * - cloneRepo checks out a non-default baseBranch after cloning
 * - pullRepo fetches new commits from the bare repo
 * - pullRepo skips when no remote is configured
 * - pullRepo skips when directory is not cloned
 * - checkoutRepo switches to baseBranch
 * - checkoutRepo detects dirty working directory
 * - checkoutRepo creates workingBranch from baseBranch
 * - runParallel executes work across multiple repos concurrently
 */
class GitOperationsTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("wrkx-gitops", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        fun gitExec(vararg cmd: String): Int =
            ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()
                .let {
                    it.inputStream.bufferedReader().readText()
                    it.waitFor()
                }

        fun gitOutput(vararg cmd: String): String =
            ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()
                .let {
                    val out =
                        it.inputStream
                            .bufferedReader()
                            .readText()
                            .trim()
                    it.waitFor()
                    out
                }

        given("cloneRepo with a local bare git repo") {
            val baseDir = tempDir()
            val bareRepo = createBareRepo(baseDir, "clone-ops")
            val repoDir = File(baseDir, "repos").apply { mkdirs() }
            val project = ProjectBuilder.builder().build()

            `when`("the target directory does not exist") {
                val repo = createTestRepo(project.objects, bareRepo.absolutePath)
                val result = GitOperations.cloneRepo(repo, repoDir)

                then("clones the repo and returns OK") {
                    result shouldStartWith "OK"
                    File(repoDir, "clone-ops").shouldExist()
                    File(repoDir, "clone-ops/.git").shouldExist()
                    File(repoDir, "clone-ops/README.md").shouldExist()
                }
            }

            `when`("the target directory already exists") {
                val repo = createTestRepo(project.objects, bareRepo.absolutePath)
                val result = GitOperations.cloneRepo(repo, repoDir)

                then("skips and returns SKIP") {
                    result shouldStartWith "SKIP"
                    result shouldContain "already exists"
                }
            }

            `when`("cloning with a non-default baseBranch that exists on remote") {
                // Create a bare repo with a develop branch
                val branchBare = createBareRepo(baseDir, "clone-branch")
                val tmpWork = File(baseDir, "branch-setup")
                gitExec("git", "clone", branchBare.absolutePath, tmpWork.absolutePath)
                gitExec("git", "-C", tmpWork.absolutePath, "checkout", "-b", "develop")
                File(tmpWork, "dev.txt").writeText("develop branch")
                gitExec("git", "-C", tmpWork.absolutePath, "add", ".")
                gitExec("git", "-C", tmpWork.absolutePath, "commit", "-m", "Add dev file")
                gitExec("git", "-C", tmpWork.absolutePath, "push", "origin", "develop")
                tmpWork.deleteRecursively()

                val branchRepoDir = File(baseDir, "branch-repos").apply { mkdirs() }
                val repo =
                    createTestRepo(
                        project.objects,
                        branchBare.absolutePath,
                        baseBranch = "develop",
                    )
                val result = GitOperations.cloneRepo(repo, branchRepoDir)

                then("clones and checks out the branch") {
                    result shouldStartWith "OK"
                    val cloneDir = File(branchRepoDir, "clone-branch")
                    cloneDir.shouldExist()
                    val branch =
                        gitOutput(
                            "git", "-C", cloneDir.absolutePath, "branch", "--show-current",
                        )
                    branch shouldBe "develop"
                }
            }

            `when`("cloning with a baseBranch that does not exist anywhere") {
                val nobranchBare = createBareRepo(baseDir, "clone-nobranch")
                val nobranchRepoDir = File(baseDir, "nobranch-repos").apply { mkdirs() }
                val repo =
                    createTestRepo(
                        project.objects,
                        nobranchBare.absolutePath,
                        baseBranch = "nonexistent-branch",
                    )
                val result = GitOperations.cloneRepo(repo, nobranchRepoDir)

                then("falls back to checkout -b and creates local branch") {
                    result shouldStartWith "OK"
                    val cloneDir = File(nobranchRepoDir, "clone-nobranch")
                    cloneDir.shouldExist()
                    val branch =
                        gitOutput(
                            "git", "-C", cloneDir.absolutePath, "branch", "--show-current",
                        )
                    branch shouldBe "nonexistent-branch"
                }

                nobranchRepoDir.deleteRecursively()
            }

            baseDir.deleteRecursively()
        }

        given("pullRepo with a cloned repo") {
            val baseDir = tempDir()
            val bareRepo = createBareRepo(baseDir, "pull-ops")
            val repoDir = File(baseDir, "repos").apply { mkdirs() }
            val project = ProjectBuilder.builder().build()

            // Clone it first
            val cloneDir = File(repoDir, "pull-ops")
            gitExec("git", "clone", bareRepo.absolutePath, cloneDir.absolutePath)

            `when`("a new commit is pushed to bare and pull is executed") {
                // Push a new commit to the bare repo
                val pushDir = File(baseDir, "push-work")
                gitExec("git", "clone", bareRepo.absolutePath, pushDir.absolutePath)
                File(pushDir, "new-file.txt").writeText("pulled content")
                gitExec("git", "-C", pushDir.absolutePath, "add", ".")
                gitExec("git", "-C", pushDir.absolutePath, "commit", "-m", "Add new file")
                gitExec("git", "-C", pushDir.absolutePath, "push")
                pushDir.deleteRecursively()

                val repo = createTestRepo(project.objects, bareRepo.absolutePath)
                val result = GitOperations.pullRepo(repo, repoDir)

                then("pulls the new commit and returns OK") {
                    result shouldStartWith "OK"
                    File(cloneDir, "new-file.txt").readText() shouldContain "pulled content"
                }
            }

            `when`("the repo directory does not exist") {
                // Use a URL that produces a directoryName with no matching directory on disk
                val repo = createTestRepo(project.objects, "/tmp/nonexistent-repo.git")
                val result = GitOperations.pullRepo(repo, repoDir)

                then("skips with not-cloned message") {
                    result shouldStartWith "SKIP"
                    result shouldContain "not cloned"
                }
            }

            `when`("the repo has no remote configured") {
                // Create a local git repo without any remote
                val localDir = File(repoDir, "no-remote").apply { mkdirs() }
                gitExec("git", "init", localDir.absolutePath)
                File(localDir, "file.txt").writeText("local only")
                gitExec("git", "-C", localDir.absolutePath, "add", ".")
                gitExec("git", "-C", localDir.absolutePath, "commit", "-m", "Init")

                // Use a URL whose directoryName matches the local dir name "no-remote"
                val repo = createTestRepo(project.objects, "file:///org/no-remote")
                val result = GitOperations.pullRepo(repo, repoDir)

                then("skips with no-remote message") {
                    result shouldStartWith "SKIP"
                    result shouldContain "no remote"
                }
            }

            baseDir.deleteRecursively()
        }

        given("checkoutRepo with a cloned repo") {
            val baseDir = tempDir()
            val bareRepo = createBareRepo(baseDir, "checkout-ops")
            val repoDir = File(baseDir, "repos").apply { mkdirs() }
            val project = ProjectBuilder.builder().build()

            val cloneDir = File(repoDir, "checkout-ops")
            gitExec("git", "clone", bareRepo.absolutePath, cloneDir.absolutePath)

            `when`("checking out the baseBranch (main)") {
                val repo =
                    createTestRepo(
                        project.objects,
                        bareRepo.absolutePath,
                        baseBranch = "main",
                    )
                val result = GitOperations.checkoutRepo(repo, repoDir, "")

                then("checks out main and returns OK") {
                    result shouldStartWith "OK"
                    val branch =
                        gitOutput(
                            "git", "-C", cloneDir.absolutePath, "branch", "--show-current",
                        )
                    branch shouldBe "main"
                }
            }

            `when`("working directory is dirty") {
                File(cloneDir, "dirty.txt").writeText("uncommitted")

                val repo =
                    createTestRepo(
                        project.objects,
                        bareRepo.absolutePath,
                        baseBranch = "main",
                    )
                val result = GitOperations.checkoutRepo(repo, repoDir, "")

                then("returns FAIL with dirty working directory message") {
                    result shouldStartWith "FAIL"
                    result shouldContain "dirty working directory"
                }

                // Clean up
                File(cloneDir, "dirty.txt").delete()
            }

            `when`("workingBranch does not exist yet") {
                val repo =
                    createTestRepo(
                        project.objects,
                        bareRepo.absolutePath,
                        baseBranch = "main",
                    )
                val result = GitOperations.checkoutRepo(repo, repoDir, "feature/new-ops")

                then("creates the new branch from origin/baseBranch and returns OK") {
                    result shouldStartWith "OK"
                    val branch =
                        gitOutput(
                            "git", "-C", cloneDir.absolutePath, "branch", "--show-current",
                        )
                    branch shouldBe "feature/new-ops"
                }

                // Return to main for subsequent tests
                gitExec("git", "-C", cloneDir.absolutePath, "checkout", "main")
            }

            `when`("the repo directory does not exist") {
                // Use a URL whose directoryName does not match any directory in repoDir
                val repo =
                    createTestRepo(
                        project.objects,
                        "/tmp/missing-checkout.git",
                        baseBranch = "main",
                    )
                val result = GitOperations.checkoutRepo(repo, repoDir, "main")

                then("skips with not-cloned message") {
                    result shouldStartWith "SKIP"
                    result shouldContain "not cloned"
                }
            }

            baseDir.deleteRecursively()
        }

        given("runParallel with multiple repos") {
            val baseDir = tempDir()
            val project = ProjectBuilder.builder().build()

            val bareA = createBareRepo(baseDir, "parallel-a")
            val bareB = createBareRepo(baseDir, "parallel-b")
            val bareC = createBareRepo(baseDir, "parallel-c")
            val repoDir = File(baseDir, "repos").apply { mkdirs() }

            val repoA = createTestRepo(project.objects, bareA.absolutePath)
            val repoB = createTestRepo(project.objects, bareB.absolutePath)
            val repoC = createTestRepo(project.objects, bareC.absolutePath)

            `when`("cloning multiple repos in parallel") {
                val results = java.util.concurrent.CopyOnWriteArrayList<String>()
                GitOperations.runParallel(listOf(repoA, repoB, repoC), "clone") { repo ->
                    GitOperations.cloneRepo(repo, repoDir).also { results.add(it) }
                }

                then("all repos are cloned successfully") {
                    File(repoDir, "parallel-a").shouldExist()
                    File(repoDir, "parallel-b").shouldExist()
                    File(repoDir, "parallel-c").shouldExist()
                }
            }

            `when`("running parallel on already-cloned repos") {
                val results = java.util.concurrent.CopyOnWriteArrayList<String>()
                GitOperations.runParallel(listOf(repoA, repoB, repoC), "clone") { repo ->
                    GitOperations.cloneRepo(repo, repoDir).also { results.add(it) }
                }

                then("all repos are skipped") {
                    results.forEach { it shouldStartWith "SKIP" }
                }
            }

            `when`("running parallel with an empty repo list") {
                // Should not throw, just prints a message
                GitOperations.runParallel(emptyList(), "noop") { "unreachable" }

                then("completes without error") {
                    // If we got here, it did not throw
                }
            }

            `when`("running parallel with a failing action") {
                val failRepo =
                    createTestRepo(
                        project.objects,
                        "file:///nonexistent/path",
                    )

                then("fails the build with an error") {
                    val ex =
                        io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
                            GitOperations.runParallel(listOf(failRepo), "clone") { repo ->
                                GitOperations.cloneRepo(repo, repoDir)
                            }
                        }
                    ex.message shouldContain "failed during clone"
                }
            }

            `when`("an action throws an exception") {
                val throwRepo =
                    createTestRepo(
                        project.objects,
                        bareA.absolutePath,
                        name = "throw-repo",
                    )

                then("fails the build with the exception message") {
                    val ex =
                        io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
                            GitOperations.runParallel(listOf(throwRepo), "explode") {
                                throw IllegalStateException("boom")
                            }
                        }
                    ex.message shouldContain "failed during explode"
                }
            }

            baseDir.deleteRecursively()
        }
    })
