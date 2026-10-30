package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class DownloadFileRenamerTest {

    @Test
    fun `returns source when provider supports rename`() {
        val source = mockk<UniFile>()
        val parent = mockk<UniFile>()
        every { parent.findFile("001.jpg") } returns null
        every { source.renameTo("001.jpg") } returns true
        every { source.name } returns "001.jpg"

        val result = finalizeDownloadFile(source, parent, "001.jpg")

        assertSame(source, result)
        verify(exactly = 0) { parent.createFile(any()) }
    }

    @Test
    fun `removes a file already stored under the target name before renaming`() {
        val source = mockk<UniFile>()
        val parent = mockk<UniFile>()
        val existing = mockk<UniFile>()
        every { parent.findFile("001.jpg") } returns existing
        every { existing.delete() } returns true
        every { source.renameTo("001.jpg") } returns true
        every { source.name } returns "001.jpg"

        assertSame(source, finalizeDownloadFile(source, parent, "001.jpg"))

        // Without the delete the provider would have stored the page as "001 (1).jpg" instead.
        verifyOrder {
            existing.delete()
            source.renameTo("001.jpg")
        }
    }

    @Test
    fun `rejects the duplicate a provider creates instead of replacing the target`() {
        val source = mockk<UniFile>()
        val parent = mockk<UniFile>()
        every { parent.findFile("001.jpg") } returns null
        every { source.renameTo("001.jpg") } returns true
        every { source.name } returns "001 (1).jpg"
        every { source.delete() } returns true

        assertThrows(IOException::class.java) {
            finalizeDownloadFile(source, parent, "001.jpg")
        }

        verify { source.delete() }
        verify(exactly = 0) { parent.createFile(any()) }
    }

    @Test
    fun `copies and removes temporary file when provider rejects rename`() {
        val content = "downloaded image".toByteArray()
        val output = ByteArrayOutputStream()
        val source = mockk<UniFile>()
        val parent = mockk<UniFile>()
        val target = mockk<UniFile>()
        every { parent.findFile("001.jpg") } returns null
        every { source.renameTo("001.jpg") } returns false
        every { parent.createFile("001.jpg") } returns target
        every { target.name } returns "001.jpg"
        every { source.openInputStream() } returns ByteArrayInputStream(content)
        every { target.openOutputStream() } returns output
        every { source.delete() } returns true

        val result = finalizeDownloadFile(source, parent, "001.jpg")

        assertSame(target, result)
        assertArrayEquals(content, output.toByteArray())
        verifyOrder {
            source.renameTo("001.jpg")
            parent.createFile("001.jpg")
            source.openInputStream()
            target.openOutputStream()
            source.delete()
        }
    }

    @Test
    fun `uses copy fallback when rename throws`() {
        val source = mockk<UniFile>()
        val parent = mockk<UniFile>()
        val target = mockk<UniFile>()
        every { parent.findFile("chapter.cbz") } returns null
        every { source.renameTo("chapter.cbz") } throws UnsupportedOperationException()
        every { parent.createFile("chapter.cbz") } returns target
        every { target.name } returns "chapter.cbz"
        every { source.openInputStream() } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
        every { target.openOutputStream() } returns ByteArrayOutputStream()
        every { source.delete() } returns true

        assertSame(target, finalizeDownloadFile(source, parent, "chapter.cbz"))
    }

    @Test
    fun `rejects a copy that the provider stored under another name`() {
        val source = mockk<UniFile>()
        val parent = mockk<UniFile>()
        val target = mockk<UniFile>()
        every { parent.findFile("001.jpg") } returns null
        every { source.renameTo("001.jpg") } returns false
        every { parent.createFile("001.jpg") } returns target
        every { target.name } returns "001 (1).jpg"
        every { target.delete() } returns true

        assertThrows(IOException::class.java) {
            finalizeDownloadFile(source, parent, "001.jpg")
        }

        verify { target.delete() }
        verify(exactly = 0) { source.delete() }
    }

    @Test
    fun `removes incomplete target and preserves source when copy fails`() {
        val source = mockk<UniFile>()
        val parent = mockk<UniFile>()
        val target = mockk<UniFile>()
        every { parent.findFile("001.jpg") } returns null
        every { source.renameTo("001.jpg") } returns false
        every { parent.createFile("001.jpg") } returns target
        every { target.name } returns "001.jpg"
        every { source.openInputStream() } throws IOException("read failed")
        every { target.delete() } returns true

        assertThrows(IOException::class.java) {
            finalizeDownloadFile(source, parent, "001.jpg")
        }
        verify { target.delete() }
        verify(exactly = 0) { source.delete() }
    }
}
