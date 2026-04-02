package zone.clanker.gradle.wrkx.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

class RepositoryEntryTest :
    BehaviorSpec({

        fun fixture(name: String): String =
            this::class.java.classLoader
                .getResource("fixtures/$name")
                ?.readText()
                ?: error("Missing fixture: fixtures/$name")

        fun parse(name: String): List<RepositoryEntry> {
            val text = fixture(name).trim()
            if (text.isEmpty()) return emptyList()
            return json.decodeFromString<List<RepositoryEntry>>(text)
        }

        given("happy-path.json") {
            val entries = parse("happy-path.json")

            then("parses all 5 entries") {
                entries shouldHaveSize 5
            }

            then("first entry has full fields") {
                entries[0].name shouldBe "gort"
                entries[0].path shouldBe RepositoryUrl("git@github.com:ClankerGuru/gort.git")
                entries[0].category shouldBe "ui"
                entries[0].substitute shouldBe true
                entries[0].substitutions shouldHaveSize 3
                entries[0].substitutions[0].artifact shouldBe ArtifactId("zone.clanker:gort-tokens")
                entries[0].substitutions[0].project shouldBe ProjectPath("tokens")
                entries[0].baseBranch shouldBe GitReference("main")
                entries[0].directoryName shouldBe "gort"
            }

            then("second entry has substitute disabled") {
                entries[1].substitute shouldBe false
                entries[1].baseBranch shouldBe GitReference("develop")
                entries[1].directoryName shouldBe "openspec-gradle"
            }

            then("third entry uses defaults") {
                entries[2].substitute shouldBe false
                entries[2].substitutions.shouldBeEmpty()
                entries[2].baseBranch shouldBe GitReference("main")
                entries[2].directoryName shouldBe "shared-lib"
            }

            then("fourth entry is a local path") {
                entries[3].path shouldBe RepositoryUrl("/Users/dev/local-bare-repo.git")
                entries[3].directoryName shouldBe "local-bare-repo"
            }

            then("fifth entry is a relative path") {
                entries[4].directoryName shouldBe "sibling-project"
            }
        }

        given("minimal.json") {
            val entries = parse("minimal.json")

            then("parses 3 entries with defaults") {
                entries shouldHaveSize 3
                entries.forEach { entry ->
                    entry.category shouldBe ""
                    entry.substitute shouldBe false
                    entry.substitutions.shouldBeEmpty()
                    entry.baseBranch shouldBe GitReference("main")
                }
            }
        }

        given("substitutions-heavy.json") {
            val entries = parse("substitutions-heavy.json")

            then("multi-module has 5 substitutions") {
                entries[0].substitutions shouldHaveSize 5
                entries[0].substitutions[4].artifact shouldBe ArtifactId("com.example:benchmark")
                entries[0].substitutions[4].project shouldBe ProjectPath(":benchmark")
            }

            then("subs-disabled still has substitutions listed but disabled") {
                entries[2].substitute shouldBe false
                entries[2].substitutions shouldHaveSize 1
            }

            then("no-subs has substitute true but empty list") {
                entries[3].substitute shouldBe true
                entries[3].substitutions.shouldBeEmpty()
            }
        }

        given("edge-cases.json") {
            val entries = parse("edge-cases.json")

            then("parses all 8 entries") {
                entries shouldHaveSize 8
            }

            then("handles dots in repo path") {
                entries[0].directoryName shouldBe "repo-with-dots.v2"
                entries[0].baseBranch shouldBe GitReference("feature/deep/nested/branch")
            }

            then("handles custom port in URL") {
                entries[1].directoryName shouldBe "repo"
            }

            then("handles uppercase") {
                entries[2].directoryName shouldBe "UPPERCASE-Repo"
            }

            then("handles numbers in path") {
                entries[3].directoryName shouldBe "repo-with-numbers-123"
            }

            then("handles explicit empty defaults") {
                entries[4].category shouldBe ""
                entries[4].substitute shouldBe false
            }

            then("handles release candidate baseBranch") {
                entries[5].baseBranch shouldBe GitReference("v1.2.3-rc1")
            }

            then("handles SHA baseBranch") {
                entries[6].baseBranch shouldBe GitReference("abc123def456")
            }

            then("ignores unknown fields") {
                entries[7].path shouldBe RepositoryUrl("org/with-unknown-fields")
                entries[7].category shouldBe "test"
            }
        }

        given("empty inputs") {

            `when`("JSON is an empty array") {
                then("returns empty list") {
                    json.decodeFromString<List<RepositoryEntry>>("[]").shouldBeEmpty()
                }
            }
        }

        given("invalid fixtures") {

            `when`("path is blank") {
                then("throws SerializationException") {
                    shouldThrow<SerializationException> {
                        parse("invalid-blank-name.json")
                    }
                }
            }

            `when`("name is missing") {
                then("throws SerializationException") {
                    shouldThrow<SerializationException> {
                        parse("invalid-missing-name.json")
                    }
                }
            }

            `when`("substitution format is invalid") {
                then("throws SerializationException") {
                    shouldThrow<SerializationException> {
                        parse("invalid-bad-substitution.json")
                    }
                }
            }

            `when`("field types are wrong") {
                then("throws SerializationException") {
                    shouldThrow<SerializationException> {
                        parse("invalid-wrong-types.json")
                    }
                }
            }

            `when`("JSON is not an array") {
                then("throws SerializationException") {
                    shouldThrow<SerializationException> {
                        parse("invalid-not-array.json")
                    }
                }
            }

            `when`("JSON is malformed") {
                then("throws SerializationException") {
                    shouldThrow<SerializationException> {
                        parse("invalid-malformed.json")
                    }
                }
            }
        }

        given("directoryName extraction") {
            data class DirNameCase(
                val input: String,
                val expected: String,
            )

            withData(
                DirNameCase("my-lib", "my-lib"),
                DirNameCase("org/my-lib", "my-lib"),
                DirNameCase("https://github.com/user/repo.git", "repo"),
                DirNameCase("git@github.com:user/repo.git", "repo"),
                DirNameCase("/tmp/repos/bare-source.git", "bare-source"),
                DirNameCase("git@gitlab.com:org/sub/deep-repo.git", "deep-repo"),
                DirNameCase("org/repo/", "repo"),
                DirNameCase("simple", "simple"),
            ) { (input, expected) ->
                RepositoryEntry(name = "test", path = RepositoryUrl(input)).directoryName shouldBe expected
            }
        }
    })
