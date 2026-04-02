package zone.clanker.gradle.wrkx.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A repository URL or path accepted by `git clone`.
 *
 * Supports SSH, HTTPS, file paths, and relative paths:
 * ```kotlin
 * RepositoryUrl("git@github.com:org/repo.git")
 * RepositoryUrl("https://github.com/org/repo.git")
 * RepositoryUrl("/Users/dev/local-repo")
 * RepositoryUrl("../sibling-project")
 * ```
 *
 * Derives the [directoryName] from the last path segment,
 * stripping `.git` suffixes and URL prefixes.
 *
 * @property value the raw URL or path string, must not be blank
 * @see RepositoryEntry
 * @see ArtifactSubstitution
 */
@Serializable(with = RepositoryUrlSerializer::class)
@JvmInline
value class RepositoryUrl(
    val value: String,
) {
    init {
        require(value.isNotBlank()) {
            """
            Repository URL is blank. Each entry in wrkx.json must have a non-empty 'path' field.
            Accepted formats: 'org/repo', 'git@host:org/repo.git', 'https://host/repo.git', or a local path.
            """.trimIndent()
        }
    }

    /**
     * Directory name derived from the URL's last path segment.
     *
     * Strips `.git` suffixes and URL prefixes:
     * ```kotlin
     * RepositoryUrl("git@github.com:org/repo.git").directoryName  // "repo"
     * RepositoryUrl("../sibling").directoryName                    // "sibling"
     * ```
     */
    val directoryName: String
        get() {
            val cleaned = value.trimEnd('/').removeSuffix(".git")
            return cleaned.substringAfterLast("/").substringAfterLast(":")
        }

    override fun toString(): String = value
}

/**
 * Serializer for [RepositoryUrl] that reads/writes the raw URL string.
 *
 * @see RepositoryUrl
 */
object RepositoryUrlSerializer : KSerializer<RepositoryUrl> {
    override val descriptor = PrimitiveSerialDescriptor("RepositoryUrl", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): RepositoryUrl {
        val raw = decoder.decodeString()
        return runCatching { RepositoryUrl(raw) }
            .getOrElse { e -> throw SerializationException(e.message, e) }
    }

    override fun serialize(encoder: Encoder, value: RepositoryUrl) = encoder.encodeString(value.value)
}
