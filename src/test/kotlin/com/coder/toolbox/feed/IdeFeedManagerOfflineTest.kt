package com.coder.toolbox.feed

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.store.CoderSettingsStore
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class IdeFeedManagerOfflineTest {
    private lateinit var context: CoderToolboxContext
    private lateinit var settingsStore: CoderSettingsStore
    private lateinit var logger: Logger
    private lateinit var ideFeedManager: IdeFeedManager

    private val moshi = Moshi.Builder()
        .add(IdeTypeAdapter())
        .build()

    private val originalUserHome = System.getProperty("user.home")

    private val releaseIdes = listOf(
        Ide("RR", "241.1", "2024.1", IdeType.RELEASE),
        Ide("RR", "242.1", "2024.2", IdeType.RELEASE),
        Ide("RR", "242.2", "2024.2.1", IdeType.RELEASE),
        Ide("IU", "241.1", "2024.1", IdeType.RELEASE),
        Ide("IU", "242.1", "2024.2", IdeType.RELEASE),
        Ide("IC", "241.1", "2024.1", IdeType.RELEASE),
        Ide("GO", "241.1", "2024.1", IdeType.RELEASE)
    )

    private val eapIdes = listOf(
        Ide("RR", "243.1", "2024.3", IdeType.EAP),
        Ide("RR", "242.1", "2024.2-EAP", IdeType.EAP),
        Ide("IU", "243.1", "2024.3", IdeType.EAP),
        Ide("GO", "243.1", "2024.3", IdeType.EAP)
    )

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        // Divert cache files to a temporary directory to avoid overwriting real data
        System.setProperty("user.home", tempDir.toAbsolutePath().toString())

        context = mockk<CoderToolboxContext>()
        logger = mockk(relaxed = true)
        settingsStore = mockk(relaxed = true)

        every { context.logger } returns logger
        every { context.settingsStore } returns settingsStore
        every { settingsStore.globalDataDirectory } returns tempDir.toString()

        // Write cache files
        writeIdsToFile(tempDir.resolve("release.json"), releaseIdes)
        writeIdsToFile(tempDir.resolve("eap.json"), eapIdes)

        // Initialize IdeFeedManager in offline mode
        ideFeedManager = IdeFeedManager(context, null) { true }
    }

    private fun writeIdsToFile(path: Path, ides: List<Ide>) {
        val listType = Types.newParameterizedType(List::class.java, Ide::class.java)
        val adapter = moshi.adapter<List<Ide>>(listType)
        val json = adapter.toJson(ides)
        path.writeText(json)
    }

    @AfterEach
    fun tearDown() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome)
        } else {
            System.clearProperty("user.home")
        }
    }

    @Test
    fun `given a list of cached release builds when findBestMatch is called with a valid product code and type then it returns the matching IDE with highest build`() =
        runTest {
            // When
            val result = ideFeedManager.findBestMatch("RR", IdeType.RELEASE, listOf("241.1", "242.1"))

            // Then
            assertNotNull(result)
            assertEquals("242.1", result?.build)
            assertEquals("RR", result?.code)
            assertEquals(IdeType.RELEASE, result?.type)
        }

    @Test
    fun `given cached builds that do not intersect with loaded IDEs when findBestMatch is called then it returns null`() =
        runTest {
            // When
            val result = ideFeedManager.findBestMatch(
                "RR",
                IdeType.RELEASE,
                listOf("253.1") // Build present in neither list
            )

            // Then
            assertNull(result)
        }

    @Test
    fun `given multiple matching cached builds when findBestMatch is called then it returns the highest build`() =
        runTest {
            // When
            val result = ideFeedManager.findBestMatch(
                "RR",
                IdeType.RELEASE,
                listOf("240.1", "241.1", "242.1", "242.2", "243.1")
            )

            // Then
            assertEquals("242.2", result?.build)
            assertEquals(IdeType.RELEASE, result?.type)
        }

    @Test
    fun `given eap type requested when findBestMatch is called then it filters for eap ides from cache`() = runTest {
        // When
        val result = ideFeedManager.findBestMatch(
            "RR",
            IdeType.EAP,
            listOf("243.1")
        )

        // Then
        assertNotNull(result)
        assertEquals(IdeType.EAP, result?.type)
        assertEquals("243.1", result?.build)
        assertEquals("RR", result?.code)
    }

    @Test
    fun `given mixed release and eap IDEs in cache when matching release then it ignores eaps even if they have higher builds`() =
        runTest {
            // When requesting RELEASE 243.1 (which exists as EAP) or 242.1 (exists as both)
            val result = ideFeedManager.findBestMatch(
                "RR",
                IdeType.RELEASE,
                listOf("242.1", "243.1")
            )

            // Then
            assertNotNull(result)
            assertEquals("242.1", result?.build)
            assertEquals(IdeType.RELEASE, result?.type)
        }

    @Test
    fun `given mixed release and eap IDEs in cache when matching eap then it ignores releases even if they have higher builds`() =
        runTest {
            // When we ask for EAP, and list includes 242.2 (release only) and 242.1 (EAP and Release)
            val result = ideFeedManager.findBestMatch(
                "RR",
                IdeType.EAP,
                listOf("242.1", "242.2")
            )

            // Then
            assertNotNull(result)
            assertEquals("242.1", result?.build)
            assertEquals(IdeType.EAP, result?.type)
        }

    @Test
    fun `given empty available builds list when finding best match then it returns null`() = runTest {
        // When
        val result = ideFeedManager.findBestMatch(
            "RR",
            IdeType.RELEASE,
            emptyList() // Empty constraints
        )

        // Then
        assertNull(result)
    }

    @Test
    fun `given cached ides do not match product code when finding best match then it returns null`() = runTest {
        // When asking for a non-existent product code "XX"
        val result = ideFeedManager.findBestMatch(
            "XX",
            IdeType.RELEASE,
            listOf("241.1")
        )

        // Then
        assertNull(result)
    }

    @Test
    fun `given available builds contains values not in cached ides when finding best match then it only considers the highest build that intersects the two lists`() =
        runTest {
            // When: requesting 241.1 (exists) and 999.9 (does not exist) for RR Release
            val result = ideFeedManager.findBestMatch(
                "RR",
                IdeType.RELEASE,
                listOf("231.1", "241.1", "999.9")
            )

            // Then
            assertNotNull(result)
            assertEquals("241.1", result?.build)
        }

    @Test
    fun `given cache files are missing when loading ides then it handles gracefully and returns empty list`() =
        runTest {
            // Given: cache files are deleted/missing
            val tempDir = java.nio.file.Paths.get(context.settingsStore.globalDataDirectory)
            tempDir.resolve("release.json").toFile().delete()
            tempDir.resolve("eap.json").toFile().delete()

            // Force reload by creating new instance or clearing cache if possible. 
            // IdeFeedManager caches data in memory, so we need a new instance to test loading.
            val newIdeFeedManager = IdeFeedManager(context, null) { true }

            // When
            val result = newIdeFeedManager.loadIdes()

            // Then
            assertEquals(0, result.size)
        }
}
