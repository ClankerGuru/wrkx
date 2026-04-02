package zone.clanker.gradle.wrkx.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val json = Json

class RepositoryUrlTest :
    BehaviorSpec({

        given("construction") {

            `when`("valid URLs") {
                withData(
                    "org/repo",
                    "git@github.com:user/repo.git",
                    "https://github.com/user/repo.git",
                    "/tmp/local/bare-repo.git",
                    "../sibling-project",
                    "simple-name",
                ) { url ->
                    RepositoryUrl(url).value shouldBe url
                }
            }

            `when`("blank input") {
                withData(
                    nameFn = { "'$it' should fail" },
                    "",
                    "   ",
                    "\t",
                    "\n",
                ) { input ->
                    shouldThrow<IllegalArgumentException> {
                        RepositoryUrl(input)
                    }
                }
            }
        }

        given("directoryName extraction") {
            data class Case(
                val input: String,
                val expected: String,
            )

            withData(
                Case("my-lib", "my-lib"),
                Case("org/my-lib", "my-lib"),
                Case("deep/nested/org/my-lib", "my-lib"),
                Case("https://github.com/user/repo.git", "repo"),
                Case("git@github.com:user/repo.git", "repo"),
                Case("git@gitlab.com:org/sub/deep.git", "deep"),
                Case("/tmp/repos/bare-source.git", "bare-source"),
                Case("../sibling", "sibling"),
                Case("repo/", "repo"),
                Case("repo.git/", "repo"),
                Case("org/repo.git", "repo"),
            ) { (input, expected) ->
                RepositoryUrl(input).directoryName shouldBe expected
            }
        }

        given("serialization") {

            `when`("serializing") {
                then("produces a plain string") {
                    val encoded = json.encodeToString(RepositoryUrl.serializer(), RepositoryUrl("org/repo"))
                    encoded shouldBe "\"org/repo\""
                }
            }

            `when`("deserializing valid strings") {
                withData(
                    "org/repo",
                    "git@github.com:user/repo.git",
                    "https://example.com/repo.git",
                    "simple",
                ) { input ->
                    val decoded = json.decodeFromString<RepositoryUrl>("\"$input\"")
                    decoded.value shouldBe input
                }
            }

            `when`("deserializing blank strings") {
                withData(
                    nameFn = { "blank '$it' should fail" },
                    "",
                    "   ",
                ) { input ->
                    shouldThrow<SerializationException> {
                        json.decodeFromString<RepositoryUrl>("\"$input\"")
                    }
                }
            }

            `when`("deserializing wrong JSON types") {
                withData(
                    nameFn = { "type $it should fail" },
                    "42",
                    "true",
                    "null",
                    "[]",
                    "{}",
                ) { jsonValue ->
                    shouldThrow<SerializationException> {
                        json.decodeFromString<RepositoryUrl>(jsonValue)
                    }
                }
            }

            `when`("round-tripping") {
                withData(
                    "org/repo",
                    "git@github.com:ClankerGuru/gort.git",
                    "/tmp/local-bare.git",
                ) { input ->
                    val original = RepositoryUrl(input)
                    val serialized = json.encodeToString(RepositoryUrl.serializer(), original)
                    val deserialized = json.decodeFromString<RepositoryUrl>(serialized)
                    deserialized shouldBe original
                }
            }
        }

        given("toString") {
            `when`("called") {
                then("returns the raw value") {
                    RepositoryUrl("org/repo").toString() shouldBe "org/repo"
                }
            }
        }
    })
