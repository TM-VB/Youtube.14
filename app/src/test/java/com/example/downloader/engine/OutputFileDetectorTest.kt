package com.example.downloader.engine

import com.example.domain.model.DownloadError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests verifying deterministic output file detection in [OutputFileDetector] / [YtDlpDownloadEngine].
 *
 * Specifically verifies:
 * 1. Output files are selected strictly without maxByOrNull { it.length() }.
 * 2. Intermediate fragments (.f137, .f140), .part files, subtitles, thumbnails, and metadata are excluded.
 * 3. A smaller completed final file is selected over larger intermediate or partial files.
 * 4. Ambiguous candidates return a clear DownloadError instead of picking a random file.
 */
class OutputFileDetectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workDir: File

    @Before
    fun setUp() {
        workDir = tempFolder.newFolder("task_work_dir")
    }

    private fun createDummyFile(dir: File, name: String, sizeBytes: Long): File {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(sizeBytes.coerceAtLeast(1).toInt().coerceAtMost(1024)))
        // Use RandomAccessFile to set exact virtual length if larger
        if (sizeBytes > 1024) {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(sizeBytes)
            }
        }
        return file
    }

    @Test
    fun `resolveOutputFile selects final video and never selects larger part or fragment files`() {
        val taskId = "test_task_101"

        // Create multiple files of varying sizes in the task directory:
        // 1. Partial file: 500 MB (Largest file)
        val partFile = createDummyFile(workDir, "task_$taskId.mp4.part", 500L * 1024 * 1024)

        // 2. Intermediate video stream fragment: 350 MB
        val fragmentVideo = createDummyFile(workDir, "task_$taskId.f137.mp4", 350L * 1024 * 1024)

        // 3. Intermediate audio stream fragment: 30 MB
        val fragmentAudio = createDummyFile(workDir, "task_$taskId.f140.m4a", 30L * 1024 * 1024)

        // 4. Subtitle file: 15 KB
        val subtitleFile = createDummyFile(workDir, "task_$taskId.ar.vtt", 15L * 1024)

        // 5. Thumbnail file: 200 KB
        val thumbnailFile = createDummyFile(workDir, "task_$taskId.webp", 200L * 1024)

        // 6. Metadata file: 8 KB
        val metadataFile = createDummyFile(workDir, "task_$taskId.info.json", 8L * 1024)

        // 7. Actual completed final video output: 15 MB (much smaller than intermediate/part files!)
        val finalExpected = createDummyFile(workDir, "task_$taskId.mp4", 15L * 1024 * 1024)

        val result = OutputFileDetector.resolveOutputFile(
            workDir = workDir,
            taskId = taskId,
            isAudioOnly = false
        )

        assertTrue("Expected successful output file detection", result.isSuccess)
        val resolved = result.getOrNull()
        assertEquals("Must select the true output file task_$taskId.mp4", finalExpected.absolutePath, resolved?.absolutePath)
        assertFalse("Must NOT select part file despite being the largest", resolved?.name == partFile.name)
        assertFalse("Must NOT select intermediate video fragment", resolved?.name == fragmentVideo.name)
        assertFalse("Must NOT select intermediate audio fragment", resolved?.name == fragmentAudio.name)
        assertFalse("Must NOT select subtitle file", resolved?.name == subtitleFile.name)
        assertFalse("Must NOT select thumbnail file", resolved?.name == thumbnailFile.name)
        assertFalse("Must NOT select metadata file", resolved?.name == metadataFile.name)
    }

    @Test
    fun `resolveOutputFile selects audio file and ignores larger video stream fragments`() {
        val taskId = "test_audio_202"

        // Intermediate unmerged video fragment left in directory: 200 MB
        createDummyFile(workDir, "task_$taskId.f137.mp4", 200L * 1024 * 1024)

        // Metadata file
        createDummyFile(workDir, "task_$taskId.info.json", 5L * 1024)

        // Final audio output file: 4 MB
        val expectedAudio = createDummyFile(workDir, "task_$taskId.mp3", 4L * 1024 * 1024)

        val result = OutputFileDetector.resolveOutputFile(
            workDir = workDir,
            taskId = taskId,
            isAudioOnly = true
        )

        assertTrue(result.isSuccess)
        assertEquals(expectedAudio.absolutePath, result.getOrNull()?.absolutePath)
    }

    @Test
    fun `resolveOutputFile prioritizes explicit merged file`() {
        val taskId = "test_merged_303"

        createDummyFile(workDir, "task_$taskId.f137.mp4", 80L * 1024 * 1024)
        createDummyFile(workDir, "task_$taskId.f140.m4a", 15L * 1024 * 1024)
        val mergedFile = createDummyFile(workDir, "task_${taskId}_merged.mp4", 95L * 1024 * 1024)

        val result = OutputFileDetector.resolveOutputFile(
            workDir = workDir,
            taskId = taskId,
            isAudioOnly = false
        )

        assertTrue(result.isSuccess)
        assertEquals(mergedFile.absolutePath, result.getOrNull()?.absolutePath)
    }

    @Test
    fun `resolveOutputFile uses logged destination lines from yt-dlp`() {
        val taskId = "test_logged_404"

        val expectedFile = createDummyFile(workDir, "task_$taskId.mkv", 22L * 1024 * 1024)
        val loggedDestinations = setOf(expectedFile.absolutePath)

        val result = OutputFileDetector.resolveOutputFile(
            workDir = workDir,
            taskId = taskId,
            isAudioOnly = false,
            loggedDestinations = loggedDestinations
        )

        assertTrue(result.isSuccess)
        assertEquals(expectedFile.absolutePath, result.getOrNull()?.absolutePath)
    }

    @Test
    fun `resolveOutputFile returns DownloadError when output cannot be reliably determined`() {
        val taskId = "test_ambiguous_505"

        // Two conflicting valid video files matching task prefix
        createDummyFile(workDir, "task_${taskId}_variant_a.mp4", 10L * 1024 * 1024)
        createDummyFile(workDir, "task_${taskId}_variant_b.mp4", 20L * 1024 * 1024)

        val result = OutputFileDetector.resolveOutputFile(
            workDir = workDir,
            taskId = taskId,
            isAudioOnly = false
        )

        // Must fail with DownloadError instead of picking randomly or using maxByOrNull!
        assertTrue("Ambiguous files must result in failure", result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue("Error must be DownloadError", exception is DownloadError)
    }

    @Test
    fun `resolveOutputFile returns DownloadError when only empty or invalid files exist`() {
        val taskId = "test_empty_606"

        // Empty file (0 bytes)
        val emptyFile = File(workDir, "task_$taskId.mp4")
        emptyFile.createNewFile()

        val result = OutputFileDetector.resolveOutputFile(
            workDir = workDir,
            taskId = taskId,
            isAudioOnly = false
        )

        assertTrue("Empty file must result in failure", result.isFailure)
        assertTrue(result.exceptionOrNull() is DownloadError)
    }

    @Test
    fun `extractDestinationFromLog extracts clean paths from yt-dlp log lines`() {
        val mergerLine = """[Merger] Merging formats into "/data/user/0/cache/task_1.mp4""""
        assertEquals("/data/user/0/cache/task_1.mp4", OutputFileDetector.extractDestinationFromLog(mergerLine))

        val audioLine = """[ExtractAudio] Destination: /data/user/0/cache/task_1.mp3"""
        assertEquals("/data/user/0/cache/task_1.mp3", OutputFileDetector.extractDestinationFromLog(audioLine))

        val alreadyLine = """[download] /data/user/0/cache/task_1.mp4 has already been downloaded"""
        assertEquals("/data/user/0/cache/task_1.mp4", OutputFileDetector.extractDestinationFromLog(alreadyLine))

        val fixupLine = """[FixupM4a] Correcting container in "/data/user/0/cache/task_1.m4a""""
        assertEquals("/data/user/0/cache/task_1.m4a", OutputFileDetector.extractDestinationFromLog(fixupLine))
    }
}
