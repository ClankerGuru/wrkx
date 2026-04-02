package zone.clanker.gradle.wrkx.task

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.file.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import zone.clanker.gradle.wrkx.DockerAvailable
import java.io.File

/**
 * Integration tests for wrkx workspace tasks against a real Gitea server.
 *
 * These tests verify the full lifecycle:
 * 1. Clone a repository from wrkx.json
 * 2. Verify the clone landed on disk with the correct structure
 * 3. Pull updates from the remote
 * 4. Checkout a specific branch
 * 5. Clean removes repos not in wrkx.json
 *
 * Requires Docker. Skipped automatically when Docker is unavailable.
 * Each test creates a temporary Gradle project that applies the wrkx plugin
 * and runs tasks via Gradle TestKit.
 */
@EnabledIf(DockerAvailable::class)
@io.kotest.core.annotation.Tags("integration")
class CloneIntegrationTest :
    BehaviorSpec({

        // -- Shared Gitea server for all tests --

        val gitea =
            GenericContainer("gitea/gitea:1.22-rootless")
                .withExposedPorts(3000)
                .withEnv("GITEA__security__INSTALL_LOCK", "true")
                .withEnv("GITEA__service__DISABLE_REGISTRATION", "false")
                .waitingFor(Wait.forHttp("/").forPort(3000).forStatusCode(200))

        lateinit var baseUrl: String

        beforeSpec {
            gitea.start()
            baseUrl = "http://${gitea.host}:${gitea.getMappedPort(3000)}"
            setupGiteaUser(baseUrl)
            createGiteaRepo(baseUrl, "test-repo")
            createGiteaRepo(baseUrl, "other-repo")
        }

        afterSpec {
            gitea.stop()
        }

        // -- Helpers --

        /**
         * Creates a temporary Gradle project with the wrkx plugin applied,
         * pointing at the Gitea server. Enables all repos by default.
         */
        fun createTestProject(
            workspaceJson: String,
            dslBlock: String = "enableAll()",
        ): File {
            val projectDir =
                File.createTempFile("wrkx-integration", "").apply {
                    delete()
                    mkdirs()
                    deleteOnExit()
                }

            val settingsContent =
                if (dslBlock.isBlank()) {
                    """
                    plugins {
                        id("zone.clanker.gradle.wrkx")
                    }
                    """.trimIndent()
                } else {
                    """
                    plugins {
                        id("zone.clanker.gradle.wrkx")
                    }

                    wrkx {
                        $dslBlock
                    }
                    """.trimIndent()
                }

            projectDir.resolve("settings.gradle.kts").writeText(settingsContent)
            projectDir.resolve("build.gradle.kts").writeText("")
            projectDir.resolve("wrkx.json").writeText(workspaceJson)

            return projectDir
        }

        fun gradle(projectDir: File, vararg args: String) =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(*args, "--stacktrace")
                .forwardOutput()
                .build()

        // -- Tests --

        given("a Gitea server with two repositories") {

            /**
             * CLONE: Verifies that wrkx-clone fetches a repository from the remote
             * and places it in the repos directory with the correct directory name
             * derived from the path URL. The .git directory must exist, proving
             * it's a valid Git checkout.
             */
            `when`("wrkx-clone is run with one repo in wrkx.json") {
                val projectDir =
                    createTestProject(
                        """
                        [{"name": "testRepo", "path": "$baseUrl/testuser/test-repo.git"}]
                        """.trimIndent(),
                    )

                val result = gradle(projectDir, "wrkx-clone")
                val reposDir = File(projectDir.parentFile, "${projectDir.name}-repos")

                then("the clone task succeeds") {
                    result.task(":wrkx-clone")?.outcome shouldBe TaskOutcome.SUCCESS
                }

                then("the repo directory exists with a .git folder") {
                    val clonedRepo = File(reposDir, "test-repo")
                    clonedRepo.shouldExist()
                    File(clonedRepo, ".git").shouldExist()
                }

                projectDir.deleteRecursively()
                reposDir.deleteRecursively()
            }

            /**
             * CLONE SKIP: Verifies that if a repo already exists on disk,
             * wrkx-clone skips it without error.
             */
            `when`("wrkx-clone is run twice") {
                val projectDir =
                    createTestProject(
                        """
                        [{"name": "testRepo", "path": "$baseUrl/testuser/test-repo.git"}]
                        """.trimIndent(),
                    )

                val reposDir = File(projectDir.parentFile, "${projectDir.name}-repos")
                gradle(projectDir, "wrkx-clone")
                val result = gradle(projectDir, "wrkx-clone")

                then("the second run succeeds (skips existing)") {
                    result.task(":wrkx-clone")?.outcome shouldBe TaskOutcome.SUCCESS
                }

                projectDir.deleteRecursively()
                reposDir.deleteRecursively()
            }

            /**
             * REPOS: Verifies that wrkx-status generates a markdown file
             * listing all workspace repos with their status.
             */
            `when`("wrkx-status is run") {
                val projectDir =
                    createTestProject(
                        """
                        [
                          {"name": "testRepo", "path": "$baseUrl/testuser/test-repo.git", "category": "test"},
                          {"name": "otherRepo", "path": "$baseUrl/testuser/other-repo.git", "category": "test"}
                        ]
                        """.trimIndent(),
                    )

                val result = gradle(projectDir, "wrkx-status")

                then("the repos task succeeds") {
                    result.task(":wrkx-status")?.outcome shouldBe TaskOutcome.SUCCESS
                }

                then("the output file lists both repos") {
                    val reposFile = projectDir.resolve(".wrkx/repos.md")
                    reposFile.shouldExist()
                    val content = reposFile.readText()
                    content shouldContain "testRepo"
                    content shouldContain "otherRepo"
                    content shouldContain "2 repos"
                }

                projectDir.deleteRecursively()
            }

            /**
             * CLEAN: Verifies that wrkx-prune removes directories that are
             * not defined in wrkx.json, while preserving repos that are.
             */
            `when`("wrkx-prune is run with an orphan directory") {
                val projectDir =
                    createTestProject(
                        """
                        [{"name": "testRepo", "path": "$baseUrl/testuser/test-repo.git"}]
                        """.trimIndent(),
                    )

                val reposDir = File(projectDir.parentFile, "${projectDir.name}-repos")
                reposDir.mkdirs()

                // Create an orphan directory that's not in wrkx.json
                val orphan = File(reposDir, "orphan-repo").apply { mkdirs() }
                // Create the expected directory so clean has something to compare against
                val expected = File(reposDir, "test-repo").apply { mkdirs() }

                val result = gradle(projectDir, "wrkx-prune")

                then("the clean task succeeds") {
                    result.task(":wrkx-prune")?.outcome shouldBe TaskOutcome.SUCCESS
                }

                then("the orphan directory is removed") {
                    orphan.shouldNotExist()
                }

                then("the expected directory is preserved") {
                    expected.shouldExist()
                }

                projectDir.deleteRecursively()
                reposDir.deleteRecursively()
            }
        }
    })

// -- Gitea API helpers --

private fun setupGiteaUser(baseUrl: String) {
    // Gitea rootless with INSTALL_LOCK=true allows form-based registration
    val process =
        ProcessBuilder(
            "curl", "-s", "-f", "-X", "POST",
            "$baseUrl/user/sign_up",
            "-d", "user_name=testuser&password=testpass123&retype=testpass123&email=test@test.com",
        ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    // 303 redirect on success, curl -f doesn't fail on 3xx
    check(exitCode == 0) { "Failed to create Gitea user (exit=$exitCode): $output" }
}

private fun createGiteaRepo(baseUrl: String, name: String) {
    val process =
        ProcessBuilder(
            "curl", "-s", "-f", "-X", "POST",
            "$baseUrl/api/v1/user/repos",
            "-H", "Content-Type: application/json",
            "-u", "testuser:testpass123",
            "-d", """{"name":"$name","auto_init":true}""",
        ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "Failed to create Gitea repo '$name' (exit=$exitCode): $output" }
}
