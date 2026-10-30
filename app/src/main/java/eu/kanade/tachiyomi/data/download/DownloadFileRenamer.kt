package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import java.io.IOException

/**
 * Moves [source] to [targetName] inside [parent], replacing whatever already has that name.
 *
 * A document provider does not overwrite on rename: renaming onto a name that is already taken
 * still reports success, but stores the file as "name (1)". That copy is invisible to the page
 * lookup, which matches the exact name, so the page is downloaded again and the chapter's file
 * count stops matching its page count. The existing target is therefore removed first, and a
 * rename that ends up under a different name is a failure instead of a silent duplicate.
 */
internal fun finalizeDownloadFile(source: UniFile, parent: UniFile, targetName: String): UniFile {
    deleteExistingTarget(parent, targetName)

    val renamed = try {
        source.renameTo(targetName)
    } catch (_: Exception) {
        false
    }
    if (renamed) {
        // A null name can't be checked; only a name that is known to differ is a collision.
        val renamedTo = source.name
        if (renamedTo == null || renamedTo == targetName) return source
        // The provider redirected the rename to "name (1)" despite the target being free. Keeping
        // that file would leave the page on disk twice, invisible to the page lookup.
        source.delete()
        throw IOException("Provider renamed the download to $renamedTo instead of $targetName")
    }

    val target = parent.createFile(targetName)
        ?: throw IOException("Unable to create download target: $targetName")
    val createdName = target.name
    if (createdName != null && createdName != targetName) {
        target.delete()
        throw IOException("Provider stored the download as $createdName instead of $targetName")
    }
    try {
        source.openInputStream().use { input ->
            target.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        if (!source.delete()) {
            target.delete()
            throw IOException("Unable to remove temporary download after copying: ${source.name}")
        }
    } catch (error: Exception) {
        target.delete()
        if (error is IOException) throw error
        throw IOException("Unable to copy download to: $targetName", error)
    }
    return target
}

/**
 * Removes whatever [targetName] already holds so a rename replaces it instead of being renamed to
 * "name (1)" by the provider.
 *
 * The source is still under its temporary name here, so it can never be the file being removed.
 */
private fun deleteExistingTarget(parent: UniFile, targetName: String) {
    val existing = parent.findFile(targetName) ?: return
    if (!existing.delete()) {
        throw IOException("Unable to replace existing download: $targetName")
    }
}
