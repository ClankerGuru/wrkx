package zone.clanker.gradle.wrkx

import org.gradle.api.model.ObjectFactory
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.wrkx.model.ArtifactSubstitution
import zone.clanker.gradle.wrkx.model.GitReference
import zone.clanker.gradle.wrkx.model.RepositoryUrl
import zone.clanker.gradle.wrkx.model.WorkspaceRepository

object TestFactory {
    private val objects: ObjectFactory by lazy {
        ProjectBuilder.builder().build().objects
    }

    @Suppress("LongParameterList")
    fun repo(
        name: String,
        path: String,
        category: String = "",
        substitutions: List<ArtifactSubstitution> = emptyList(),
        substitute: Boolean = false,
        baseBranch: String = "main",
    ): WorkspaceRepository {
        val repo = objects.newInstance(WorkspaceRepository::class.java, name)
        repo.path.set(RepositoryUrl(path))
        repo.category.set(category)
        repo.substitutions.set(substitutions)
        repo.substitute.set(substitute)
        repo.baseBranch.set(GitReference(baseBranch))
        return repo
    }
}
