package zone.clanker.gradle.wrkx.task

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import org.gradle.testfixtures.ProjectBuilder
import java.io.File

/**
 * Tests for [PullTask] -- pulls latest changes via `git pull --ff-only`.
 *
 * Uses local bare git repos (no network, no Docker).
 *
 * Verifies:
 * - Pull succeeds on a cloned repo with no upstream changes
 * - Pull fetches new commits from the bare repo
 * - Fails with clear error when directory doesn't exist
 */
class PullTaskTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("wrkx-pull", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        given("a cloned repo from a local bare source") {
            val baseDir = tempDir()
            val bareRepo = createBareRepo(baseDir, "pull-test")
            val repoDir = File(baseDir, "repos")
            repoDir.mkdirs()

            // Clone it first
            ProcessBuilder(
                "git", "clone", bareRepo.absolutePath,
                File(repoDir, "pull-test").absolutePath,
            ).redirectErrorStream(true)
                .start()
                .waitFor()

            val project = ProjectBuilder.builder().build()

            `when`("pull is executed with no new changes") {
                val repo =
                    createTestRepo(project.objects, bareRepo.absolutePath, name = "pull-test")
                repo.clonePath.set(File(repoDir, "pull-test"))

                val task =
                    project.tasks
                        .register("wrkx-pull-test", PullTask::class.java, repo, repoDir)
                        .get()
                task.pull()

                then("succeeds without error") {
                    // If we got here without exception, pull worked
                    File(repoDir, "pull-test/README.md").readText() shouldContain "pull-test"
                }
            }

            `when`("a new commit is pushed to the bare repo and pull is executed") {
                // Make a new commit in the bare repo via a temp clone
                val pushDir = File(baseDir, "push-work")
                ProcessBuilder("git", "clone", bareRepo.absolutePath, pushDir.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                File(pushDir, "new-file.txt").writeText("new content")
                ProcessBuilder("git", "-C", pushDir.absolutePath, "add", ".")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                ProcessBuilder("git", "-C", pushDir.absolutePath, "commit", "-m", "Add new file")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                ProcessBuilder("git", "-C", pushDir.absolutePath, "push")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                pushDir.deleteRecursively()

                val repo =
                    createTestRepo(project.objects, bareRepo.absolutePath, name = "pull-test")
                repo.clonePath.set(File(repoDir, "pull-test"))

                val task =
                    project.tasks
                        .register("wrkx-pull-updated", PullTask::class.java, repo, repoDir)
                        .get()
                task.pull()

                then("pulls the new commit") {
                    File(repoDir, "pull-test/new-file.txt").readText() shouldContain "new content"
                }
            }

            baseDir.deleteRecursively()
        }

        given("a repo that doesn't exist on disk") {
            val baseDir = tempDir()
            val repoDir = File(baseDir, "repos")
            repoDir.mkdirs()

            val project = ProjectBuilder.builder().build()
            val repo =
                createTestRepo(project.objects, "org/missing-repo", name = "missing-repo")
            repo.clonePath.set(File(repoDir, "missing-repo"))

            `when`("pull is executed") {
                then("fails with a descriptive error") {
                    val ex =
                        shouldThrow<IllegalStateException> {
                            val task =
                                project.tasks
                                    .register(
                                        "wrkx-pull-missing",
                                        PullTask::class.java,
                                        repo,
                                        repoDir,
                                    ).get()
                            task.pull()
                        }
                    ex.message shouldContain "not found at"
                    ex.message shouldContain "wrkx-clone"
                }
            }

            baseDir.deleteRecursively()
        }

        given("a repo with no remote configured") {
            val baseDir = tempDir()
            val repoDir = File(baseDir, "repos")
            repoDir.mkdirs()

            val localDir = File(repoDir, "local-only")
            localDir.mkdirs()
            val initResult =
                ProcessBuilder("git", "init", localDir.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            check(initResult == 0) { "git init failed" }

            val project = ProjectBuilder.builder().build()
            val repo =
                createTestRepo(project.objects, "file:///org/local-only")
            repo.clonePath.set(localDir)

            `when`("pull is executed") {
                val task =
                    project.tasks
                        .register("wrkx-pull-local", PullTask::class.java, repo, repoDir)
                        .get()
                task.pull()

                then("skips without error") {
                    // If we got here, it skipped gracefully
                }
            }

            baseDir.deleteRecursively()
        }
    })
