package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga

class ChapterWithoutUrlSyncTest {

    @Test
    fun `keeps the readable chapters of a source that returns one without a url`() = runTest {
        val added = slot<List<Chapter>>()
        val deps = syncDeps(added)
        val manga = Manga.create().copy(id = 3, title = "Sample")
        val broken = SChapter.create().apply { name = "Chapter 2" }
        val valid = SChapter.create().apply {
            name = "Chapter 1"
            url = "chapter-1"
        }

        deps.sync.await(listOf(valid, broken), manga, deps.source, manualFetch = true)

        assertEquals(listOf("Chapter 1"), added.captured.map { it.name })
    }

    @Test
    fun `survives a chapter implementation whose url was never initialized`() = runTest {
        val added = slot<List<Chapter>>()
        val deps = syncDeps(added)
        val manga = Manga.create().copy(id = 3, title = "Sample")

        deps.sync.await(
            listOf(
                ThrowingUrlChapter(name = "Chapter 1"),
                SChapter.create().apply {
                    name = "Chapter 2"
                    url = "chapter-2"
                },
            ),
            manga,
            deps.source,
            manualFetch = true,
        )

        assertEquals(listOf("Chapter 2"), added.captured.map { it.name })
    }

    private class SyncDeps(val sync: SyncChaptersWithSource, val source: HttpSource)

    private fun syncDeps(added: CapturingSlot<List<Chapter>>): SyncDeps {
        val repository = mockk<ChapterRepository>(relaxed = true)
        val source = mockk<HttpSource>(relaxed = true)
        val libraryPreferences = mockk<LibraryPreferences>()
        val excluded = mockk<GetExcludedScanlators>()
        coEvery { repository.getChapterByMangaId(any(), any()) } returns emptyList()
        coEvery { repository.updateFromRemote(emptyList(), capture(added), emptyList()) } returns emptyList()
        coEvery { excluded.await(any()) } returns emptySet()
        every { libraryPreferences.markDuplicateReadChapterAsRead.get() } returns emptySet()

        val sync = SyncChaptersWithSource(
            downloadManager = mockk(relaxed = true),
            downloadProvider = mockk(relaxed = true),
            chapterRepository = repository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = mockk<UpdateManga>(relaxed = true),
            getExcludedScanlators = excluded,
            libraryPreferences = libraryPreferences,
        )
        return SyncDeps(sync, source)
    }
}

/**
 * A chapter whose url is read before it was ever assigned, like an extension that forgets to set it.
 */
private class ThrowingUrlChapter(
    override var name: String,
    override var chapter_number: Float = -1f,
    override var scanlator: String? = null,
    override var date_upload: Long = 0,
    override var memo: JsonObject = JsonObject(emptyMap()),
) : SChapter {
    override var url: String
        get() = throw UninitializedPropertyAccessException("lateinit property url has not been initialized")

        @Suppress("UNUSED_PARAMETER")
        set(value) = throw UnsupportedOperationException()
}
