package zone.clanker.gradle.wrkx.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.datatest.withData
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val json = Json

class GitReferenceTest :
    BehaviorSpec({

        given("construction") {

            `when`("valid refs") {
                withData(
                    "main",
                    "develop",
                    "feature/my-branch",
                    "v1.0.0",
                    "release/2.0",
                    "HEAD",
                    "abc123",
                ) { ref ->
                    GitReference(ref).value shouldBe ref
                }
            }

            `when`("empty string") {
                then("is allowed (git treats empty ref as default)") {
                    GitReference("").value shouldBe ""
                }
            }
        }

        given("isDefault") {

            `when`("ref is main") {
                then("returns true") {
                    GitReference("main").isDefault.shouldBeTrue()
                }
            }

            `when`("ref is anything else") {
                withData(
                    nameFn = { "ref '$it' is not default" },
                    "develop",
                    "master",
                    "feature/x",
                    "v1.0.0",
                    "",
                ) { ref ->
                    GitReference(ref).isDefault.shouldBeFalse()
                }
            }
        }

        given("serialization") {

            `when`("serializing") {
                then("produces a plain string") {
                    val encoded = json.encodeToString(GitReference.serializer(), GitReference("develop"))
                    encoded shouldBe "\"develop\""
                }
            }

            `when`("deserializing valid strings") {
                withData(
                    nameFn = { "ref '$it'" },
                    "main",
                    "develop",
                    "feature/my-branch",
                    "v2.0.0",
                    "",
                ) { input ->
                    val decoded = json.decodeFromString<GitReference>("\"$input\"")
                    decoded.value shouldBe input
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
                        json.decodeFromString<GitReference>(jsonValue)
                    }
                }
            }

            `when`("round-tripping") {
                withData(
                    "main",
                    "feature/deep/nested/branch",
                    "v1.2.3-rc1",
                ) { input ->
                    val original = GitReference(input)
                    val serialized = json.encodeToString(GitReference.serializer(), original)
                    val deserialized = json.decodeFromString<GitReference>(serialized)
                    deserialized shouldBe original
                }
            }
        }

        given("toString") {
            `when`("called") {
                then("returns the raw value") {
                    GitReference("develop").toString() shouldBe "develop"
                }
            }
        }
    })
