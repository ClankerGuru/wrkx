package zone.clanker.gradle.wrkx.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val json = Json

class ArtifactSubstitutionTest :
    BehaviorSpec({

        given("construction") {

            `when`("valid inputs") {
                data class Case(
                    val artifact: String,
                    val project: String,
                )

                withData(
                    Case("com.example:lib", "lib"),
                    Case("zone.clanker:gort-tokens", "tokens"),
                    Case("io.ktor:ktor-core", ":"),
                ) { (artifact, project) ->
                    val sub = ArtifactSubstitution(ArtifactId(artifact), ProjectPath(project))
                    sub.artifact.value shouldBe artifact
                    sub.project.value shouldBe project
                }
            }

            `when`("blank artifact") {
                withData(
                    nameFn = { "artifact '$it' should fail" },
                    "",
                    "   ",
                    "\t",
                ) { artifact ->
                    shouldThrow<IllegalArgumentException> {
                        ArtifactId(artifact)
                    }
                }
            }

            `when`("blank project") {
                withData(
                    nameFn = { "project '$it' should fail" },
                    "",
                    "   ",
                    "\t",
                ) { project ->
                    shouldThrow<IllegalArgumentException> {
                        ProjectPath(project)
                    }
                }
            }
        }

        given("deserialization of valid strings") {
            data class Case(
                val input: String,
                val artifact: String,
                val project: String,
            )

            withData(
                Case("com.example:lib,lib", "com.example:lib", "lib"),
                Case("zone.clanker:gort-tokens,tokens", "zone.clanker:gort-tokens", "tokens"),
                Case("org.foo:bar-api,bar-api", "org.foo:bar-api", "bar-api"),
                Case(" com.example:core , core-project ", "com.example:core", "core-project"),
                Case("io.ktor:ktor-server-core,:", "io.ktor:ktor-server-core", ":"),
                Case("a.b:c,d", "a.b:c", "d"),
                Case("com.example:lib,sub-project-name", "com.example:lib", "sub-project-name"),
                Case("group:artifact,project-with-many-dashes", "group:artifact", "project-with-many-dashes"),
            ) { (input, expectedArtifact, expectedProject) ->
                val sub = json.decodeFromString<ArtifactSubstitution>("\"$input\"")
                sub.artifact.value shouldBe expectedArtifact
                sub.project.value shouldBe expectedProject
            }
        }

        given("deserialization of invalid strings") {
            withData(
                nameFn = { "'$it' should fail" },
                "no-comma-here",
                ",missing-left",
                "missing-right,",
                "",
                "  ,  ",
                ",",
                "   ",
                "only-artifact:",
                "single-word",
            ) { input ->
                shouldThrow<SerializationException> {
                    json.decodeFromString<ArtifactSubstitution>("\"$input\"")
                }
            }
        }

        given("deserialization of wrong JSON types") {
            withData(
                nameFn = { "type $it should fail" },
                "42",
                "true",
                "false",
                "null",
                "3.14",
                "[]",
                "{}",
                "[\"com.example:lib,lib\"]",
                "{\"artifact\":\"x\",\"project\":\"y\"}",
            ) { jsonValue ->
                shouldThrow<SerializationException> {
                    json.decodeFromString<ArtifactSubstitution>(jsonValue)
                }
            }
        }

        given("serialization round-trip") {
            withData(
                nameFn = { it.toString() },
                ArtifactSubstitution(ArtifactId("com.example:lib"), ProjectPath("lib")),
                ArtifactSubstitution(ArtifactId("zone.clanker:gort"), ProjectPath("gort")),
                ArtifactSubstitution(ArtifactId("org.foo:bar-api"), ProjectPath("bar-api")),
                ArtifactSubstitution(ArtifactId("io.ktor:core"), ProjectPath(":")),
            ) { sub ->
                val serialized = json.encodeToString(ArtifactSubstitution.serializer(), sub)
                val deserialized = json.decodeFromString<ArtifactSubstitution>(serialized)
                deserialized shouldBe sub
            }
        }

        given("toString") {
            `when`("called on valid substitutions") {
                then("produces artifact,project format") {
                    val sub =
                        ArtifactSubstitution(
                            ArtifactId("com.example:lib"),
                            ProjectPath("lib"),
                        )
                    sub.toString() shouldBe "com.example:lib,lib"
                }
            }
        }

        given("ProjectPath.gradlePath") {
            `when`("project is root") {
                then("returns ':'") {
                    ProjectPath(":").gradlePath shouldBe ":"
                }
            }

            `when`("project is a name") {
                then("returns ':name'") {
                    ProjectPath("tokens").gradlePath shouldBe ":tokens"
                }
            }
        }

        given("ArtifactId toString") {
            `when`("called on a valid artifact") {
                then("returns the raw value") {
                    ArtifactId("com.example:lib").toString() shouldBe "com.example:lib"
                }
            }
        }

        given("ArtifactId serialization round-trip via JSON") {
            `when`("serializing and deserializing") {
                then("produces the original value") {
                    val original = ArtifactId("zone.clanker:gort-tokens")
                    val serialized = json.encodeToString(ArtifactId.serializer(), original)
                    serialized shouldBe "\"zone.clanker:gort-tokens\""
                    val deserialized = json.decodeFromString(ArtifactId.serializer(), serialized)
                    deserialized shouldBe original
                }
            }

            `when`("deserializing a blank value") {
                then("throws SerializationException") {
                    shouldThrow<SerializationException> {
                        json.decodeFromString(ArtifactId.serializer(), "\"  \"")
                    }
                }
            }
        }

        given("ProjectPath toString") {
            `when`("called on a valid project path") {
                then("returns the raw value") {
                    ProjectPath("tokens").toString() shouldBe "tokens"
                }
            }
        }

        given("ProjectPath serialization round-trip via JSON") {
            `when`("serializing and deserializing") {
                then("produces the original value") {
                    val original = ProjectPath("tokens")
                    val serialized = json.encodeToString(ProjectPath.serializer(), original)
                    serialized shouldBe "\"tokens\""
                    val deserialized = json.decodeFromString(ProjectPath.serializer(), serialized)
                    deserialized shouldBe original
                }
            }

            `when`("deserializing a blank value") {
                then("throws SerializationException") {
                    shouldThrow<SerializationException> {
                        json.decodeFromString(ProjectPath.serializer(), "\"  \"")
                    }
                }
            }
        }

        given("edge cases in comma handling") {
            `when`("input has comma in project portion") {
                then("splits on first comma only") {
                    val sub = json.decodeFromString<ArtifactSubstitution>("\"com.example:lib,project,extra\"")
                    sub.artifact.value shouldBe "com.example:lib"
                    sub.project.value shouldBe "project,extra"
                }
            }
        }
    })
