package zone.clanker.gradle.wrkx.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A Git ref: branch name, tag, or SHA.
 *
 * ```kotlin
 * GitReference("main")           // default branch
 * GitReference("develop")        // feature branch
 * GitReference("v1.2.3")         // tag
 * GitReference("abc123def456")   // commit SHA
 * ```
 *
 * Used as the `baseBranch` field in [RepositoryEntry] to specify the repo's
 * default branch.
 *
 * @property value the raw ref string
 * @see RepositoryEntry.baseBranch
 */
@Serializable(with = GitReferenceSerializer::class)
@JvmInline
value class GitReference(
    val value: String,
) {
    /** Returns `true` when this reference points to the `main` branch. */
    val isDefault: Boolean get() = value == "main"

    override fun toString(): String = value
}

/**
 * Serializer for [GitReference] that reads/writes the raw ref string.
 *
 * @see GitReference
 */
object GitReferenceSerializer : KSerializer<GitReference> {
    override val descriptor = PrimitiveSerialDescriptor("GitReference", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder) = GitReference(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: GitReference) = encoder.encodeString(value.value)
}
