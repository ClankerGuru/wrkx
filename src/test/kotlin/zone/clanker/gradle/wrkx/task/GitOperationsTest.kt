package zone.clanker.gradle.wrkx.task

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.file.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.wrkx.model.WorkspaceLayout
import java.io.File

/**
 * Tests for [GitOperations] -- parallel git operations for lifecycle tasks.
 *
 * Uses local bare git repos as clone sources (no network, no Docker).
 *
 * Verifies:
 * - cloneRepo clones from a local bare repo
 * - cloneRepo fetches when the bare target already exists
 * - cloneRepo never creates or checks out a working tree
 * - pullRepo fetches new commits from the bare repo
 * - pullRepo skips when no remote is configured
 * - pullRepo skips when directory is not cloned
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

        given("cloneRepo with a repository that has not been cloned") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "clone-ops")
            val repoDir = File(baseDir, "repos").apply { mkdirs() }
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath)

            `when`("cloneRepo runs") {
                val result = GitOperations.cloneRepo(repo, repoDir)

                then("it creates only a bare repository and returns OK") {
                    result shouldStartWith "OK"
                    val bareDir = WorkspaceLayout.bareRepository(repoDir, repo)
                    bareDir.shouldExist()
                    gitOutput(
                        "git",
                        "--git-dir=${bareDir.absolutePath}",
                        "rev-parse",
                        "--is-bare-repository",
                    ) shouldBe "true"
                    File(repoDir, "clone-ops").shouldNotExist()
                }
            }

            baseDir.deleteRecursively()
        }

        given("cloneRepo with an existing bare repository") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "update-ops")
            val repoDir = File(baseDir, "repos").apply { mkdirs() }
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath)
            GitOperations.cloneRepo(repo, repoDir) shouldStartWith "OK"

            `when`("a new remote branch is pushed and cloneRepo runs again") {
                val tmpWork = File(baseDir, "update-setup")
                gitExec("git", "clone", remote.absolutePath, tmpWork.absolutePath)
                gitExec("git", "-C", tmpWork.absolutePath, "checkout", "-b", "develop")
                File(tmpWork, "dev.txt").writeText("develop branch")
                gitExec("git", "-C", tmpWork.absolutePath, "add", ".")
                gitExec("git", "-C", tmpWork.absolutePath, "commit", "-m", "Add dev file")
                gitExec("git", "-C", tmpWork.absolutePath, "push", "origin", "develop")
                tmpWork.deleteRecursively()
                val result = GitOperations.cloneRepo(repo, repoDir)

                then("it fetches the new branch into the existing bare repository") {
                    result shouldStartWith "OK"
                    val bareDir = WorkspaceLayout.bareRepository(repoDir, repo)
                    gitOutput(
                        "git",
                        "--git-dir=${bareDir.absolutePath}",
                        "show-ref",
                        "--verify",
                        "refs/remotes/origin/develop",
                    ) shouldContain "refs/remotes/origin/develop"
                }
            }

            baseDir.deleteRecursively()
        }

        given("pullRepo with a shared bare repository") {
            val baseDir = tempDir()
            val bareRepo = createBareRepo(baseDir, "pull-ops")
            val repoDir = File(baseDir, "repos").apply { mkdirs() }
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, bareRepo.absolutePath)
            GitOperations.cloneRepo(repo, repoDir) shouldStartWith "OK"

            `when`("a new commit is pushed to bare and pull is executed") {
                // Push a new commit to the bare repo
                val pushDir = File(baseDir, "push-work")
                gitExec("git", "clone", bareRepo.absolutePath, pushDir.absolutePath)
                File(pushDir, "new-file.txt").writeText("pulled content")
                gitExec("git", "-C", pushDir.absolutePath, "add", ".")
                gitExec("git", "-C", pushDir.absolutePath, "commit", "-m", "Add new file")
                gitExec("git", "-C", pushDir.absolutePath, "push")
                pushDir.deleteRecursively()

                val result = GitOperations.pullRepo(repo, repoDir, null)

                then("fetches the new commit into the bare repository and returns OK") {
                    result shouldStartWith "OK"
                    gitOutput(
                        "git",
                        "--git-dir=${WorkspaceLayout.bareRepository(repoDir, repo).absolutePath}",
                        "show-ref",
                        "--verify",
                        "refs/remotes/origin/main",
                    ) shouldContain "refs/remotes/origin/main"
                }
            }

            `when`("an active branch worktree does not exist") {
                val result = GitOperations.pullRepo(repo, repoDir, "feature/missing")

                then("fails with the worktree recovery command") {
                    result shouldStartWith "FAIL"
                    result shouldContain "wrkx-worktree-pull-ops"
                    result shouldContain "feature/missing"
                }
            }

            `when`("the selected worktree is dirty") {
                GitOperations.createWorktree(repo, repoDir, "feature/dirty") shouldStartWith "OK"
                val worktree = WorkspaceLayout.worktree(repoDir, "feature/dirty", repo)
                File(worktree, "dirty.txt").writeText("local change")
                val result = GitOperations.pullRepo(repo, repoDir, "feature/dirty")

                then("fails without deleting the local change") {
                    result shouldStartWith "FAIL"
                    result shouldContain "uncommitted changes"
                    File(worktree, "dirty.txt").readText() shouldBe "local change"
                }
            }

            baseDir.deleteRecursively()
        }

        given("createWorktree with a local bare remote") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "worktree-ops")
            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath)

            `when`("two branch worktrees are created") {
                val first = GitOperations.createWorktree(repo, repoDir, "feature/alpha")
                val second = GitOperations.createWorktree(repo, repoDir, "feature/beta")

                then("one bare repository backs both worktrees") {
                    first shouldStartWith "OK"
                    second shouldStartWith "OK"
                    WorkspaceLayout.bareRepository(repoDir, repo).resolve("HEAD").shouldExist()
                    WorkspaceLayout.worktree(repoDir, "feature/alpha", repo).resolve("README.md").shouldExist()
                    WorkspaceLayout.worktree(repoDir, "feature/beta", repo).resolve("README.md").shouldExist()
                }

                then("each worktree checks out its requested branch") {
                    gitOutput(
                        "git",
                        "-C",
                        WorkspaceLayout.worktree(repoDir, "feature/alpha", repo).absolutePath,
                        "branch",
                        "--show-current",
                    ) shouldBe "feature/alpha"
                    gitOutput(
                        "git",
                        "-C",
                        WorkspaceLayout.worktree(repoDir, "feature/beta", repo).absolutePath,
                        "branch",
                        "--show-current",
                    ) shouldBe "feature/beta"
                }
            }

            baseDir.deleteRecursively()
        }

        given("a configured base branch that appears on origin after the initial clone") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "remote-base")
            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().build()
            val baseBranch = "Release/Remote-Base"
            val repo = createTestRepo(project.objects, remote.absolutePath, baseBranch = baseBranch)
            GitOperations.cloneRepo(repo, repoDir) shouldStartWith "OK"
            val setup = File(baseDir, "remote-base-setup")
            gitExec("git", "clone", remote.absolutePath, setup.absolutePath)
            gitExec("git", "-C", setup.absolutePath, "checkout", "-b", baseBranch)
            File(setup, "remote-base.txt").writeText("remote base")
            gitExec("git", "-C", setup.absolutePath, "add", ".")
            gitExec("git", "-C", setup.absolutePath, "commit", "-m", "Add remote base")
            gitExec("git", "-C", setup.absolutePath, "push", "origin", baseBranch)

            `when`("a worktree is created after fetching the new remote base") {
                val result = GitOperations.createWorktree(repo, repoDir, "Feature/From-Remote-Base")
                val bareDir = WorkspaceLayout.bareRepository(repoDir, repo)

                then("the remote base is created locally and used for the worktree") {
                    result shouldStartWith "OK"
                    gitOutput(
                        "git",
                        "--git-dir=${bareDir.absolutePath}",
                        "rev-parse",
                        "refs/heads/$baseBranch",
                    ) shouldBe
                        gitOutput(
                            "git",
                            "--git-dir=${bareDir.absolutePath}",
                            "rev-parse",
                            "refs/remotes/origin/$baseBranch",
                        )
                    WorkspaceLayout
                        .worktree(repoDir, "Feature/From-Remote-Base", repo)
                        .resolve("remote-base.txt")
                        .shouldExist()
                }
            }

            baseDir.deleteRecursively()
        }

        given("a configured base branch that does not exist locally or remotely") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "missing-base")
            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().build()
            val baseBranch = "Release/Local-Base"
            val repo = createTestRepo(project.objects, remote.absolutePath, baseBranch = baseBranch)

            `when`("a worktree is created") {
                val result = GitOperations.createWorktree(repo, repoDir, "Feature/From-Local-Base")
                val bareDir = WorkspaceLayout.bareRepository(repoDir, repo)

                then("the base is created locally from the fetched remote default branch") {
                    result shouldStartWith "OK"
                    gitOutput(
                        "git",
                        "--git-dir=${bareDir.absolutePath}",
                        "rev-parse",
                        "refs/heads/$baseBranch",
                    ) shouldBe
                        gitOutput(
                            "git",
                            "--git-dir=${bareDir.absolutePath}",
                            "rev-parse",
                            "refs/remotes/origin/main",
                        )
                    (
                        gitExec(
                            "git",
                            "--git-dir=${bareDir.absolutePath}",
                            "show-ref",
                            "--verify",
                            "refs/remotes/origin/$baseBranch",
                        ) == 0
                    ) shouldBe false
                }
            }

            baseDir.deleteRecursively()
        }

        given("a missing base branch and a remote without a valid default HEAD") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "missing-default")
            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath, baseBranch = "Release/Missing-Base")
            GitOperations.cloneRepo(repo, repoDir) shouldStartWith "OK"
            val bareDir = WorkspaceLayout.bareRepository(repoDir, repo)
            gitExec("git", "--git-dir=${bareDir.absolutePath}", "remote", "set-head", "origin", "--auto") shouldBe 0
            gitExec(
                "git",
                "--git-dir=${remote.absolutePath}",
                "symbolic-ref",
                "HEAD",
                "refs/heads/missing",
            ) shouldBe 0

            then("stale local origin HEAD is rejected when fresh default discovery fails") {
                val result = GitOperations.createWorktree(repo, repoDir, "Feature/Missing-Default")
                result shouldContain "default branch could not be determined"
                WorkspaceLayout.worktree(repoDir, "Feature/Missing-Default", repo).shouldNotExist()
            }

            baseDir.deleteRecursively()
        }

        given("an invalid configured base branch") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "invalid-base")
            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath, baseBranch = "-f")

            then("worktree creation rejects it before creating a local branch or worktree") {
                val result = GitOperations.createWorktree(repo, repoDir, "Feature/Invalid-Base")
                result shouldContain "not a valid Git branch name"
                WorkspaceLayout.worktree(repoDir, "Feature/Invalid-Base", repo).shouldNotExist()
            }

            baseDir.deleteRecursively()
        }

        given("a valid missing base branch blocked by an existing ref namespace") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "blocked-base")
            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath, baseBranch = "Release/New-Base")
            GitOperations.cloneRepo(repo, repoDir) shouldStartWith "OK"
            val bareDir = WorkspaceLayout.bareRepository(repoDir, repo)
            gitExec("git", "--git-dir=${bareDir.absolutePath}", "branch", "Release", "origin/main") shouldBe 0

            then("worktree creation reports the local branch failure without creating a directory") {
                val result = GitOperations.createWorktree(repo, repoDir, "Feature/Blocked-Base")
                result shouldContain "Could not create local base branch"
                WorkspaceLayout.worktree(repoDir, "Feature/Blocked-Base", repo).shouldNotExist()
            }

            baseDir.deleteRecursively()
        }

        given("an existing worktree") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "existing-worktree")
            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath)
            GitOperations.createWorktree(repo, repoDir, "feature/alpha") shouldStartWith "OK"

            `when`("the same worktree is requested again") {
                val bareDir = WorkspaceLayout.bareRepository(repoDir, repo)
                gitExec("git", "--git-dir=${bareDir.absolutePath}", "branch", "-D", "main") shouldBe 0
                val result = GitOperations.createWorktree(repo, repoDir, "feature/alpha")

                then("its missing local base is restored before the worktree is skipped") {
                    result shouldStartWith "SKIP"
                    gitExec(
                        "git",
                        "--git-dir=${bareDir.absolutePath}",
                        "show-ref",
                        "--verify",
                        "refs/heads/main",
                    ) shouldBe 0
                }
            }

            baseDir.deleteRecursively()
        }

        given("a custom branch prefix") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "custom-prefix")
            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath)

            `when`("the prefix is explicitly allowed") {
                val result =
                    GitOperations.createWorktree(
                        repo,
                        repoDir,
                        "experiment/new-renderer",
                        WorkspaceLayout.defaultBranchPrefixes + "experiment",
                    )

                then("the worktree is created in the custom prefix directory") {
                    result shouldStartWith "OK"
                    File(repoDir, "experiment/new-renderer/custom-prefix").shouldExist()
                }
            }

            baseDir.deleteRecursively()
        }

        given("an invalid branch name") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "invalid-branch")
            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath)

            then("it is rejected before a bare repository is cloned") {
                shouldThrow<IllegalArgumentException> {
                    GitOperations.createWorktree(repo, repoDir, "unknown/new-renderer")
                }
                WorkspaceLayout.bareRepository(repoDir, repo).shouldNotExist()
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

                then("all bare repos are cloned successfully") {
                    WorkspaceLayout.bareRepository(repoDir, repoA).shouldExist()
                    WorkspaceLayout.bareRepository(repoDir, repoB).shouldExist()
                    WorkspaceLayout.bareRepository(repoDir, repoC).shouldExist()
                    File(repoDir, "parallel-a").shouldNotExist()
                    File(repoDir, "parallel-b").shouldNotExist()
                    File(repoDir, "parallel-c").shouldNotExist()
                }
            }

            `when`("running parallel on already-cloned repos") {
                val results = java.util.concurrent.CopyOnWriteArrayList<String>()
                GitOperations.runParallel(listOf(repoA, repoB, repoC), "clone") { repo ->
                    GitOperations.cloneRepo(repo, repoDir).also { results.add(it) }
                }

                then("all existing bare repos are fetched successfully") {
                    results.forEach { it shouldStartWith "OK" }
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
                    ex.message shouldContain "clone failed"
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
                    ex.message shouldContain "explode failed"
                }
            }

            baseDir.deleteRecursively()
        }
    })
