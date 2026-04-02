package zone.clanker.gradle.wrkx.task

import org.gradle.api.model.ObjectFactory
import zone.clanker.gradle.wrkx.model.GitReference
import zone.clanker.gradle.wrkx.model.RepositoryUrl
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.File

/**
 * Creates a local bare git repo with an initial commit.
 * Returns the path to the bare repo that can be used as a clone source.
 */
fun createBareRepo(parentDir: File, name: String): File {
    val bareDir = File(parentDir, "$name.git")
    bareDir.mkdirs()

    // Init bare repo
    ProcessBuilder("git", "init", "--bare", bareDir.absolutePath)
        .redirectErrorStream(true)
        .start()
        .waitFor()

    // Create a temp working copy, make a commit, push to bare
    val workDir = File(parentDir, "$name-work")
    workDir.mkdirs()
    ProcessBuilder("git", "clone", bareDir.absolutePath, workDir.absolutePath)
        .redirectErrorStream(true)
        .start()
        .waitFor()
    File(workDir, "README.md").writeText("# $name\n")
    ProcessBuilder("git", "-C", workDir.absolutePath, "add", ".")
        .redirectErrorStream(true)
        .start()
        .waitFor()
    ProcessBuilder("git", "-C", workDir.absolutePath, "commit", "-m", "Initial commit")
        .redirectErrorStream(true)
        .start()
        .waitFor()
    ProcessBuilder("git", "-C", workDir.absolutePath, "push")
        .redirectErrorStream(true)
        .start()
        .waitFor()
    workDir.deleteRecursively()

    return bareDir
}

fun createTestRepo(
    objects: ObjectFactory,
    url: String,
    name: String = RepositoryUrl(url).directoryName,
    baseBranch: String = "main",
): WorkspaceRepository {
    val camelName =
        name.split(Regex("[-_ ]+")).filter { it.isNotEmpty() }.let { parts ->
            if (parts.isEmpty()) {
                name
            } else {
                parts[0].lowercase() +
                    parts.drop(1).joinToString("") { p ->
                        p.replaceFirstChar { it.uppercaseChar() }
                    }
            }
        }
    val repo = objects.newInstance(WorkspaceRepository::class.java, camelName)
    repo.path.set(RepositoryUrl(url))
    repo.category.set("")
    repo.substitutions.set(emptyList())
    repo.substitute.set(false)
    repo.baseBranch.set(GitReference(baseBranch))
    return repo
}
