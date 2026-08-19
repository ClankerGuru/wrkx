package zone.clanker.gradle.wrkx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.gradle.api.initialization.Settings
import org.gradle.api.model.ObjectFactory
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.wrkx.model.RepositoryUrl

/**
 * Empty means the catalog is gone from wrkx.json, then settings.
 * `disableAll()` only flips enablement. It does not delete catalog entries.
 * A full catalog with every repo disabled is not an empty workspace.
 */
class WrkxCatalogNotEmptyTest :
    BehaviorSpec({
        val objects: ObjectFactory = ProjectBuilder.builder().build().objects

        fun createExtension(): Wrkx.SettingsExtension =
            objects.newInstance(Wrkx.SettingsExtension::class.java, mockk<Settings>(relaxed = true))

        given("a wrkx catalog with registered repos") {
            val ext = createExtension()
            ext.repos.register("srcxPlugin") { repo ->
                repo.path.set(RepositoryUrl("https://github.com/ClankerGuru/srcx.git"))
            }
            ext.repos.register("turbineLibrary") { repo ->
                repo.path.set(RepositoryUrl("https://github.com/cashapp/turbine.git"))
            }
            ext.enableAll()

            `when`("disableAll is applied") {
                ext.disableAll()

                then("every repo is disabled") {
                    ext.repos.forEach { it.enabled.shouldBeFalse() }
                }

                then("the catalog is still there — this is not empty") {
                    ext.repos shouldHaveSize 2
                    ext.repos.names.toList() shouldContainExactly listOf("srcxPlugin", "turbineLibrary")
                }
            }
        }

        given("an empty wrkx.json catalog") {
            val ext = createExtension()

            `when`("no repos are registered") {
                then("the catalog is empty") {
                    ext.repos shouldHaveSize 0
                }
            }
        }

        given("the 20:40 thirteen real WRKX builds") {
            val ext = createExtension()
            val names =
                listOf(
                    "okhttp",
                    "coroutines",
                    "mosaic",
                    "srcx",
                    "wrkx",
                    "clikt",
                    "okio",
                    "clkx-agents",
                    "opsx",
                    "kaml",
                    "datetime",
                    "turbine",
                    "codepoints",
                )
            names.forEach { name ->
                ext.repos.register(name) { repo ->
                    repo.path.set(RepositoryUrl("https://example.invalid/$name.git"))
                }
            }
            ext.enableAll()
            ext.disableAll()
            names.forEach { name -> ext.enable(ext.repos.getByName(name)) }

            `when`("the 13 real clones are enabled after disableAll") {
                then("the catalog stays 13 — shrinking to finish generate is a bounce") {
                    names.size shouldBe 13
                    names shouldNotContain "kotest"
                    names shouldNotContain "dummy"
                    ext.repos shouldHaveSize 13
                    ext.repos.count { it.enabled } shouldBe 13
                    ext.repos.names.toSet() shouldNotContain "kotest"
                    ext.repos.names.toSet() shouldNotContain "dummy"
                }

                then("coroutines and kaml stay; chunky libs are not dropped to paper over kotest") {
                    val required =
                        setOf("okhttp", "coroutines", "kaml", "mosaic", "okio", "srcx", "wrkx", "clikt")
                    ext.repos.names.toSet() shouldContainAll required
                    ext.repos.names.contains("okhttp") shouldBe true
                    ext.repos.names.contains("coroutines") shouldBe true
                    ext.repos.names.contains("kaml") shouldBe true
                    ext.repos.names.contains("kotest") shouldBe false
                }
            }
        }
    })
