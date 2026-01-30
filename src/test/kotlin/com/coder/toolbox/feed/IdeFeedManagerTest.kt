package com.coder.toolbox.feed

import com.coder.toolbox.CoderToolboxContext
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import io.mockk.coEvery
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

class IdeFeedManagerTest {
    private lateinit var context: CoderToolboxContext
    private lateinit var logger: Logger
    private lateinit var feedService: JetBrainsFeedService
    private lateinit var ideFeedManager: IdeFeedManager

    private val originalUserHome = System.getProperty("user.home")

    private val releaseProducts = listOf(
        IdeProduct(
            "RustRover", "RR", "RustRover", listOf(
                IdeRelease("241.1", "2024.1", IdeType.RELEASE, "2024-01-01"),
                IdeRelease("242.1", "2024.2", IdeType.RELEASE, "2024-01-01"),
                IdeRelease("242.2", "2024.2.1", IdeType.RELEASE, "2024-01-01"),
            )
        ),
        IdeProduct(
            "IntelliJ IDEA Ultimate", "IU", "IntelliJ IDEA Ultimate", listOf(
                IdeRelease("241.1", "2024.1", IdeType.RELEASE, "2024-01-01"),
                IdeRelease("242.1", "2024.2", IdeType.RELEASE, "2024-01-01"),
            )
        ),
        IdeProduct(
            "IntelliJ IDEA Community Edition", "IC", "IntelliJ IDEA Community Edition", listOf(
                IdeRelease("241.1", "2024.1", IdeType.RELEASE, "2024-01-01"),
            )
        ),
        IdeProduct(
            "GoLand", "GO", "GoLand", listOf(
                IdeRelease("241.1", "2024.1", IdeType.RELEASE, "2024-01-01"),
            )
        ),
        IdeProduct(
            "Rider", "RS", "Rider", listOf(
                IdeRelease("2025.3.2.65536", "2025.3.2", IdeType.RELEASE, "2024-01-01"),
            )
        )
    )

    private val eapProducts = listOf(
        IdeProduct(
            "RustRover", "RR", "RustRover", listOf(
                IdeRelease("243.1", "2024.3", IdeType.EAP, "2024-01-01"),
                IdeRelease("242.1", "2024.2-EAP", IdeType.EAP, "2024-01-01"),
            )
        ),
        IdeProduct(
            "IntelliJ IDEA Ultimate", "IU", "IntelliJ IDEA Ultimate", listOf(
                IdeRelease("243.1", "2024.3", IdeType.EAP, "2024-01-01"),
            )
        ),
        IdeProduct(
            "GoLand", "GO", "GoLand", listOf(
                IdeRelease("243.1", "2024.3", IdeType.EAP, "2024-01-01"),
            )
        )
    )

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        // Divert cache files to a temporary directory to avoid overwriting real data
        System.setProperty("user.home", tempDir.toAbsolutePath().toString())

        context = mockk<CoderToolboxContext>()
        logger = mockk(relaxed = true)

        every { context.logger } returns logger
        feedService = mockk()
        ideFeedManager = IdeFeedManager(context, feedService)
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
    fun `given a list of available release builds when findBestMatch is called with a valid product code and type then it returns the matching IDE with highest build`() =
        runTest {
            // Given
            coEvery { feedService.fetchReleaseFeed() } returns releaseProducts.flatMap { product ->
                product.releases.mapNotNull { release -> Ide.from(product, release) }
            }
            coEvery { feedService.fetchEapFeed() } returns eapProducts.flatMap { product ->
                product.releases.mapNotNull { release -> Ide.from(product, release) }
            }

            // When
            val result = ideFeedManager.findBestMatch("RR", IdeType.RELEASE, listOf("241.1", "242.1"))

            // Then
            assertNotNull(result)
            assertEquals("242.1", result?.build)
            assertEquals("RR", result?.code)
            assertEquals(IdeType.RELEASE, result?.type)
        }

    @Test
    fun `given a list of available release builds when findBestMatch is called with a valid product code that has a null intellijProductCode then it returns the matching IDE with highest build`() =
        runTest {
            // Given
            coEvery { feedService.fetchReleaseFeed() } returns releaseProducts.flatMap { product ->
                product.releases.mapNotNull { release -> Ide.from(product, release) }
            }
            coEvery { feedService.fetchEapFeed() } returns eapProducts.flatMap { product ->
                product.releases.mapNotNull { release -> Ide.from(product, release) }
            }

            // When
            val result = ideFeedManager.findBestMatch("RS", IdeType.RELEASE, listOf("2025.3.2.65536"))

            // Then
            assertNotNull(result)
            assertEquals("2025.3.2.65536", result?.build)
            assertEquals("RS", result?.code)
            assertEquals(IdeType.RELEASE, result?.type)
        }

    @Test
    fun `given available builds that do not intersect with loaded IDEs when findBestMatch is called then it returns null`() =
        runTest {
            // Given
            coEvery { feedService.fetchReleaseFeed() } returns releaseProducts.flatMap { product ->
                product.releases.mapNotNull { release -> Ide.from(product, release) }
            }
            coEvery { feedService.fetchEapFeed() } returns eapProducts.flatMap { product ->
                product.releases.mapNotNull { release -> Ide.from(product, release) }
            }

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
    fun `given multiple matching builds when findBestMatch is called then it returns the highest build`() = runTest {
        // Given
        coEvery { feedService.fetchReleaseFeed() } returns releaseProducts.flatMap { product ->
            product.releases.mapNotNull { release -> Ide.from(product, release) }
        }
        coEvery { feedService.fetchEapFeed() } returns eapProducts.flatMap { product ->
            product.releases.mapNotNull { release -> Ide.from(product, release) }
        }

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
    fun `given eap type requested when findBestMatch is called then it filters for eap ides`() = runTest {
        // Given
        coEvery { feedService.fetchReleaseFeed() } returns releaseProducts.flatMap { product ->
            product.releases.mapNotNull { release -> Ide.from(product, release) }
        }
        coEvery { feedService.fetchEapFeed() } returns eapProducts.flatMap { product ->
            product.releases.mapNotNull { release -> Ide.from(product, release) }
        }

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
    fun `given mixed release and eap IDEs when matching release then it ignores eaps even if they have higher builds`() =
        runTest {
            // Given
            // In our dataset: RR release has 241.1, 242.1, 242.2. RR eap has 242.1, 243.1.
            coEvery { feedService.fetchReleaseFeed() } returns releaseProducts.flatMap { product ->
                product.releases.mapNotNull { release -> Ide.from(product, release) }
            }
            coEvery { feedService.fetchEapFeed() } returns eapProducts.flatMap { product ->
                product.releases.mapNotNull { release -> Ide.from(product, release) }
            }

            // When requesting RELEASE 243.1 (which exists as EAP) or 242.1 (exists as both)
            // We only ask for 242.1 and 243.1. 243.1 is EAP only. 242.1 is both.
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
    fun `given mixed release and eap IDEs when matching eap then it ignores releases even if they have higher builds`() =
        runTest {
            // Given
            // In our dataset: RR release has 242.2 (higher than 242.1). RR eap has 242.1.
            coEvery { feedService.fetchReleaseFeed() } returns releaseProducts.flatMap { product ->
                product.releases.mapNotNull { release -> Ide.from(product, release) }
            }
            coEvery { feedService.fetchEapFeed() } returns eapProducts.flatMap { product ->
                product.releases.mapNotNull { release -> Ide.from(product, release) }
            }

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
        // Given
        coEvery { feedService.fetchReleaseFeed() } returns releaseProducts.flatMap { product ->
            product.releases.mapNotNull { release -> Ide.from(product, release) }
        }
        coEvery { feedService.fetchEapFeed() } returns eapProducts.flatMap { product ->
            product.releases.mapNotNull { release -> Ide.from(product, release) }
        }

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
    fun `given loaded ides do not match product code when finding best match then it returns null`() = runTest {
        // Given
        coEvery { feedService.fetchReleaseFeed() } returns releaseProducts.flatMap { product ->
            product.releases.mapNotNull { release -> Ide.from(product, release) }
        }
        coEvery { feedService.fetchEapFeed() } returns eapProducts.flatMap { product ->
            product.releases.mapNotNull { release -> Ide.from(product, release) }
        }

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
    fun `given available builds contains values not in loaded ides when finding best match then it only considers the highest build that intersects the two lists`() =
        runTest {
            // Given
            coEvery { feedService.fetchReleaseFeed() } returns releaseProducts.flatMap { product ->
                product.releases.mapNotNull { release -> Ide.from(product, release) }
            }
            coEvery { feedService.fetchEapFeed() } returns eapProducts.flatMap { product ->
                product.releases.mapNotNull { release -> Ide.from(product, release) }
            }

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
    fun `given network error when loading ides then it handles exception gracefully and returns empty list or cached data`() =
        runTest {
            // Given
            coEvery { feedService.fetchReleaseFeed() } throws RuntimeException("Network error")
            coEvery { feedService.fetchEapFeed() } returns emptyList()

            // When
            val result = ideFeedManager.loadIdes()

            // Then
            assertEquals(0, result.size)
        }

    @Test
    fun `given feed containing unknown types when findBestMatch is called then it ignores unsupported types`() =
        runTest {
            // Given
            val unknownTypeProduct = IdeProduct(
                "RustRover",
                "RR",
                "RustRover",
                listOf(IdeRelease("245.1", "2024.5", IdeType.UNSUPPORTED, "2024-01-01"))
            )
            val validProduct = IdeProduct(
                "RustRover",
                "RR",
                "RustRover",
                listOf(IdeRelease("241.1", "2024.1", IdeType.RELEASE, "2024-01-01"))
            )

            coEvery { feedService.fetchReleaseFeed() } returns listOf(
                unknownTypeProduct,
                validProduct
            ).flatMap { product ->
                product.releases.mapNotNull { release -> Ide.from(product, release) }
            }
            coEvery { feedService.fetchEapFeed() } returns emptyList()

            // When
            val result = ideFeedManager.findBestMatch(
                "RR",
                IdeType.RELEASE,
                listOf("241.1", "245.1")
            )

            // Then
            assertNotNull(result)
            assertEquals("241.1", result?.build) // Should match valid IDE, ignoring the unsupported one
            assertEquals(IdeType.RELEASE, result?.type)
        }

    @Test
    fun `given feed containing null builds when findBestMatch is called then it ignores them`() =
        runTest {
            // Given
            val nullBuildProduct = IdeProduct(
                "RustRover",
                "RR",
                "RustRover",
                listOf(IdeRelease(null, "2024.5", IdeType.RELEASE, "2024-01-01"))
            )
            val validProduct = IdeProduct(
                "RustRover",
                "RR",
                "RustRover",
                listOf(IdeRelease("241.1", "2024.1", IdeType.RELEASE, "2024-01-01"))
            )

            coEvery { feedService.fetchReleaseFeed() } returns listOf(
                nullBuildProduct,
                validProduct
            ).flatMap { product ->
                product.releases.mapNotNull { release -> Ide.from(product, release) }
            }
            coEvery { feedService.fetchEapFeed() } returns emptyList()

            // When
            val result = ideFeedManager.findBestMatch(
                "RR",
                IdeType.RELEASE,
                listOf("241.1")
            )

            // Then
            assertNotNull(result)
            assertEquals("241.1", result?.build)
            assertEquals(IdeType.RELEASE, result?.type)
        }
}
