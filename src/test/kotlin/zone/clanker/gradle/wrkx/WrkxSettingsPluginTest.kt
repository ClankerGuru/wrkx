package zone.clanker.gradle.wrkx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.gradle.api.file.BuildLayout
import org.gradle.api.file.Directory
import org.gradle.api.initialization.Settings
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.ExtensionContainer
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.testfixtures.ProjectBuilder
import java.io.File

class WrkxSettingsPluginTest :
    BehaviorSpec({

        val objects: ObjectFactory =
            ProjectBuilder.builder().build().objects

        fun mockLayout(settingsDir: File): BuildLayout {
            val directory =
                mockk<Directory> {
                    every { asFile } returns settingsDir
                    every { file(any<String>()) } answers {
                        val name = firstArg<String>()
                        mockk<org.gradle.api.file.RegularFile> {
                            every { asFile } returns File(settingsDir, name)
                        }
                    }
                }
            return mockk {
                every { settingsDirectory } returns directory
            }
        }

        fun mockProviders(enabled: String? = null): ProviderFactory {
            val provider =
                mockk<Provider<String>> {
                    every { orNull } returns enabled
                }
            return mockk {
                every { gradleProperty(Wrkx.ENABLED_PROP) } returns provider
            }
        }

        fun createPlugin(
            providers: ProviderFactory = mockProviders(),
            layout: BuildLayout =
                mockLayout(
                    File.createTempFile("wrkx", "").apply {
                        delete()
                        mkdirs()
                    },
                ),
        ): Wrkx.SettingsPlugin =
            objects.newInstance(Wrkx.SettingsPlugin::class.java, providers, layout)

        fun createExtension(settings: Settings = mockk(relaxed = true)): Wrkx.SettingsExtension =
            objects.newInstance(Wrkx.SettingsExtension::class.java, settings)

        given("isDisabled") {

            `when`("property is not set") {
                val plugin = createPlugin(providers = mockProviders(enabled = null))

                then("returns false") {
                    plugin.isDisabled().shouldBeFalse()
                }
            }

            `when`("property is 'false'") {
                val plugin = createPlugin(providers = mockProviders(enabled = "false"))

                then("returns true") {
                    plugin.isDisabled().shouldBeTrue()
                }
            }

            `when`("property is 'FALSE' (case insensitive)") {
                val plugin = createPlugin(providers = mockProviders(enabled = "FALSE"))

                then("returns true") {
                    plugin.isDisabled().shouldBeTrue()
                }
            }

            `when`("property is 'true'") {
                val plugin = createPlugin(providers = mockProviders(enabled = "true"))

                then("returns false") {
                    plugin.isDisabled().shouldBeFalse()
                }
            }
        }

        given("isAlreadyApplied") {

            `when`("extension is not present") {
                val extensions =
                    mockk<ExtensionContainer> {
                        every { findByType(Wrkx.SettingsExtension::class.java) } returns null
                    }
                val settings =
                    mockk<Settings> {
                        every { this@mockk.extensions } returns extensions
                    }
                val plugin = createPlugin()

                then("returns false") {
                    plugin.isAlreadyApplied(settings).shouldBeFalse()
                }
            }

            `when`("extension is present") {
                val ext = mockk<Wrkx.SettingsExtension>()
                val extensions =
                    mockk<ExtensionContainer> {
                        every { findByType(Wrkx.SettingsExtension::class.java) } returns ext
                    }
                val settings =
                    mockk<Settings> {
                        every { this@mockk.extensions } returns extensions
                    }
                val plugin = createPlugin()

                then("returns true") {
                    plugin.isAlreadyApplied(settings).shouldBeTrue()
                }
            }
        }

        given("resolveRepoDir") {

            `when`("settings dir has a parent") {
                val parentDir =
                    File.createTempFile("wrkx-parent", "").apply {
                        delete()
                        mkdirs()
                    }
                val settingsDir = File(parentDir, "myproject").apply { mkdirs() }
                val plugin = createPlugin(layout = mockLayout(settingsDir))

                then("returns sibling -repos directory") {
                    val result = plugin.resolveRepoDir()
                    result.name shouldBe "myproject-repos"
                    result.parentFile shouldBe parentDir
                }

                parentDir.deleteRecursively()
            }

            `when`("settings dir is a filesystem root") {
                val rootDir = File("/")
                val plugin = createPlugin(layout = mockLayout(rootDir))

                then("falls back to settings dir as parent") {
                    val result = plugin.resolveRepoDir()
                    // File("/").name is "" so result is "/-repos"
                    result.name shouldBe "-repos"
                    result.parentFile shouldBe rootDir
                }
            }
        }

        given("populateFromConfig") {

            `when`("config file does not exist") {
                val tmpDir =
                    File.createTempFile("wrkx-cfg", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                val layout = mockLayout(tmpDir)
                val plugin = createPlugin(layout = layout)
                val ext = createExtension()
                val repoDir = File(tmpDir, "repos")

                then("creates wrkx.json with empty array") {
                    plugin.populateFromConfig(ext, repoDir)

                    val configFile = File(tmpDir, "wrkx.json")
                    configFile.shouldExist()
                    configFile.readText().trim() shouldBe "[]"
                    ext.repos shouldHaveSize 0
                }

                tmpDir.deleteRecursively()
            }

            `when`("config file is empty array") {
                val tmpDir =
                    File.createTempFile("wrkx-cfg2", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                File(tmpDir, "wrkx.json").writeText("[]")
                val layout = mockLayout(tmpDir)
                val plugin = createPlugin(layout = layout)
                val ext = createExtension()
                val repoDir = File(tmpDir, "repos")

                then("does not register any repos") {
                    plugin.populateFromConfig(ext, repoDir)
                    ext.repos shouldHaveSize 0
                }

                tmpDir.deleteRecursively()
            }

            `when`("config file is blank") {
                val tmpDir =
                    File.createTempFile("wrkx-cfg3", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                File(tmpDir, "wrkx.json").writeText("   ")
                val layout = mockLayout(tmpDir)
                val plugin = createPlugin(layout = layout)
                val ext = createExtension()
                val repoDir = File(tmpDir, "repos")

                then("does not register any repos") {
                    plugin.populateFromConfig(ext, repoDir)
                    ext.repos shouldHaveSize 0
                }

                tmpDir.deleteRecursively()
            }

            `when`("config file has valid repos") {
                val tmpDir =
                    File.createTempFile("wrkx-cfg4", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                File(tmpDir, "wrkx.json").writeText(
                    """
                    [
                      {
                        "name": "gort",
                        "path": "git@github.com:ClankerGuru/gort.git",
                        "category": "ui",
                        "baseBranch": "develop"
                      },
                      {
                        "name": "wrkx",
                        "path": "git@github.com:ClankerGuru/wrkx.git",
                        "category": "tooling",
                        "substitute": true,
                        "substitutions": ["com.example:wrkx,wrkx"]
                      }
                    ]
                    """.trimIndent(),
                )
                val layout = mockLayout(tmpDir)
                val plugin = createPlugin(layout = layout)
                val ext = createExtension()
                val repoDir = File(tmpDir, "repos")

                then("registers repos from JSON") {
                    plugin.populateFromConfig(ext, repoDir)

                    ext.repos shouldHaveSize 2

                    val gort = ext.repos.getByName("gort")
                    gort.path.get().value shouldBe "git@github.com:ClankerGuru/gort.git"
                    gort.category.get() shouldBe "ui"
                    gort.baseBranch.get().value shouldBe "develop"
                    gort.substitute.get().shouldBeFalse()

                    val wrkx = ext.repos.getByName("wrkx")
                    wrkx.path.get().value shouldBe "git@github.com:ClankerGuru/wrkx.git"
                    wrkx.category.get() shouldBe "tooling"
                    wrkx.substitute.get().shouldBeTrue()
                    wrkx.substitutions.get() shouldHaveSize 1

                    wrkx.clonePath.asFile
                        .get()
                        .name shouldBe "wrkx"
                    gort.clonePath.asFile
                        .get()
                        .name shouldBe "gort"
                }

                tmpDir.deleteRecursively()
            }

            `when`("config file has unknown keys") {
                val tmpDir =
                    File.createTempFile("wrkx-cfg5", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                File(tmpDir, "wrkx.json").writeText(
                    """
                    [{"name": "lib", "path": "org/lib", "unknownField": true}]
                    """.trimIndent(),
                )
                val layout = mockLayout(tmpDir)
                val plugin = createPlugin(layout = layout)
                val ext = createExtension()
                val repoDir = File(tmpDir, "repos")

                then("ignores unknown keys") {
                    plugin.populateFromConfig(ext, repoDir)
                    ext.repos shouldHaveSize 1
                    ext.repos.getByName("lib").shouldNotBeNull()
                }

                tmpDir.deleteRecursively()
            }
        }

        given("createExtension") {

            `when`("called with settings and repoDir") {
                val settings = mockk<Settings>(relaxed = true)
                val ext = mockk<Wrkx.SettingsExtension>(relaxed = true)
                every {
                    settings.extensions.create(
                        Wrkx.EXTENSION_NAME,
                        Wrkx.SettingsExtension::class.java,
                        settings,
                    )
                } returns ext

                val tmpDir =
                    File.createTempFile("wrkx-ext", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                val layout = mockLayout(tmpDir)
                val plugin = createPlugin(layout = layout)

                then("creates and configures extension") {
                    val result = plugin.createExtension(settings, tmpDir)
                    result shouldBe ext
                }

                tmpDir.deleteRecursively()
            }
        }

        given("task registration") {

            `when`("registerCatalogTask is called") {
                val tmpDir =
                    File.createTempFile("wrkx-task", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                val layout = mockLayout(tmpDir)
                val plugin = createPlugin(layout = layout)
                val project = ProjectBuilder.builder().build()

                then("registers the wrkx catalog task") {
                    with(plugin) {
                        project.registerCatalogTask()
                    }
                    project.tasks.findByName(Wrkx.TASK_CATALOG).shouldNotBeNull()
                    project.tasks.findByName(Wrkx.TASK_CATALOG)!!.group shouldBe Wrkx.GROUP
                }

                tmpDir.deleteRecursively()
            }

            `when`("registerCatalogTask is called twice") {
                val tmpDir =
                    File.createTempFile("wrkx-task2", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                val layout = mockLayout(tmpDir)
                val plugin = createPlugin(layout = layout)
                val project = ProjectBuilder.builder().build()

                then("does not duplicate the task") {
                    with(plugin) {
                        project.registerCatalogTask()
                        project.registerCatalogTask()
                    }
                    // No exception means it didn't try to register twice
                    project.tasks.findByName(Wrkx.TASK_CATALOG).shouldNotBeNull()
                }

                tmpDir.deleteRecursively()
            }

            `when`("registerLifecycleTasks is called") {
                val tmpDir =
                    File.createTempFile("wrkx-life", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                val layout = mockLayout(tmpDir)
                val plugin = createPlugin(layout = layout)
                val project = ProjectBuilder.builder().build()
                val ext = createExtension()
                ext.workingBranch = "feature/test"

                then("registers clone, pull, and checkout lifecycle tasks") {
                    with(plugin) {
                        project.registerLifecycleTasks(ext, tmpDir)
                    }
                    project.tasks.findByName(Wrkx.TASK_CLONE).shouldNotBeNull()
                    project.tasks.findByName(Wrkx.TASK_PULL).shouldNotBeNull()
                    project.tasks.findByName(Wrkx.TASK_CHECKOUT).shouldNotBeNull()

                    project.tasks.findByName(Wrkx.TASK_CLONE)!!.group shouldBe Wrkx.GROUP
                    project.tasks.findByName(Wrkx.TASK_PULL)!!.group shouldBe Wrkx.GROUP
                    project.tasks.findByName(Wrkx.TASK_CHECKOUT)!!.group shouldBe Wrkx.GROUP
                }

                tmpDir.deleteRecursively()
            }

            `when`("registerLifecycleTasks is called with null workingBranch") {
                val tmpDir =
                    File.createTempFile("wrkx-life-null", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                val layout = mockLayout(tmpDir)
                val plugin = createPlugin(layout = layout)
                val project = ProjectBuilder.builder().build()
                val ext = createExtension()

                then("registers tasks without error") {
                    with(plugin) {
                        project.registerLifecycleTasks(ext, tmpDir)
                    }
                    project.tasks.findByName(Wrkx.TASK_CHECKOUT).shouldNotBeNull()
                }

                tmpDir.deleteRecursively()
            }

            `when`("registerUtilityTasks is called") {
                val tmpDir =
                    File.createTempFile("wrkx-util", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                val layout = mockLayout(tmpDir)
                val plugin = createPlugin(layout = layout)
                val project = ProjectBuilder.builder().build()
                val ext = createExtension()

                then("registers status and prune tasks") {
                    with(plugin) {
                        project.registerUtilityTasks(ext, tmpDir)
                    }
                    project.tasks.findByName(Wrkx.TASK_STATUS).shouldNotBeNull()
                    project.tasks.findByName(Wrkx.TASK_PRUNE).shouldNotBeNull()
                }

                tmpDir.deleteRecursively()
            }

            `when`("registerPerRepoTasks is called with repos") {
                val tmpDir =
                    File.createTempFile("wrkx-per", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                val layout = mockLayout(tmpDir)
                val plugin = createPlugin(layout = layout)
                val project = ProjectBuilder.builder().build()
                val ext = createExtension()
                ext.repos.register("gort") { repo ->
                    repo.path.set(
                        zone.clanker.gradle.wrkx.model
                            .RepositoryUrl("org/gort"),
                    )
                }

                then("registers per-repo clone, pull, checkout tasks") {
                    with(plugin) {
                        project.registerPerRepoTasks(ext, tmpDir)
                    }
                    project.tasks.findByName("${Wrkx.TASK_CLONE}-gort").shouldNotBeNull()
                    project.tasks.findByName("${Wrkx.TASK_PULL}-gort").shouldNotBeNull()
                    project.tasks.findByName("${Wrkx.TASK_CHECKOUT}-gort").shouldNotBeNull()
                }

                tmpDir.deleteRecursively()
            }

            `when`("registerPerRepoTasks with null workingBranch") {
                val tmpDir =
                    File.createTempFile("wrkx-per-null", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                val layout = mockLayout(tmpDir)
                val plugin = createPlugin(layout = layout)
                val project = ProjectBuilder.builder().build()
                val ext = createExtension()
                ext.repos.register("lib") { repo ->
                    repo.path.set(
                        zone.clanker.gradle.wrkx.model
                            .RepositoryUrl("org/lib"),
                    )
                }

                then("registers tasks without error") {
                    with(plugin) {
                        project.registerPerRepoTasks(ext, tmpDir)
                    }
                    project.tasks.findByName("${Wrkx.TASK_CHECKOUT}-lib").shouldNotBeNull()
                }

                tmpDir.deleteRecursively()
            }
        }

        given("json parser") {

            `when`("parsing with unknown keys") {
                val tmpDir =
                    File.createTempFile("wrkx-json", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                val layout = mockLayout(tmpDir)
                val plugin = createPlugin(layout = layout)

                then("ignores unknown keys") {
                    val result =
                        plugin.json
                            .decodeFromString<List<zone.clanker.gradle.wrkx.model.RepositoryEntry>>(
                                """[{"name":"a","path":"org/a","extra":true}]""",
                            )
                    result shouldHaveSize 1
                    result[0].name shouldBe "a"
                }

                tmpDir.deleteRecursively()
            }
        }
    })
