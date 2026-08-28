package at.themrcodes.ridershub

import at.themrcodes.ridershub.session.BatteryCapacityObservation
import at.themrcodes.ridershub.session.BatteryLongevityChart
import at.themrcodes.ridershub.session.LongevityGranularity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class UiLocaleRegressionTest {
    @Test
    fun englishUiFormattingIsStableUnderGermanAndTurkishDefaults() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val expectedLocalDateTime =
                UiFormat.localDateTime("2030-01-02T15:04:05Z", ZoneId.of("UTC"))
            listOf(Locale.GERMANY, Locale.forLanguageTag("tr-TR")).forEach { deviceLocale ->
                Locale.setDefault(deviceLocale)

                assertEquals("12.34", UiFormat.decimal(12.34, 2))
                assertEquals("08.1", UiFormat.decimal(8.1, 1, minimumWidth = 4))
                assertEquals("1:01 h", UiFormat.duration(3660.0))
                assertEquals(
                    "Wed, 2 Jan · 15:04",
                    UiFormat.rideDate("2030-01-02T15:04:05Z", ZoneId.of("UTC")),
                )
                assertEquals(
                    expectedLocalDateTime,
                    UiFormat.localDateTime("2030-01-02T15:04:05Z", ZoneId.of("UTC")),
                )
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun longevityLabelsAreStableUnderGermanDefaultLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val observations = listOf(
                BatteryCapacityObservation(
                    cycleId = "synthetic-cycle",
                    observedAt = Instant.parse("2030-01-02T10:00:00Z"),
                    normalizedFullRangeKm = 30.0,
                    observedDistanceKm = 6.0,
                    observedDepletionPercent = 20.0,
                    fullChargeObserved = true,
                ),
            )

            val daily = BatteryLongevityChart.aggregate(
                observations,
                LongevityGranularity.DAY,
                ZoneId.of("UTC"),
            )
            val monthly = BatteryLongevityChart.aggregate(
                observations,
                LongevityGranularity.MONTH,
                ZoneId.of("UTC"),
            )

            assertEquals("2 Jan", daily.single().label)
            assertEquals("Jan", monthly.single().label)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun productionKotlinDoesNotUseImplicitDefaultLocaleApis() {
        val sourceRoot = sequenceOf(
            Path.of("src/main/java"),
            Path.of("app/src/main/java"),
        ).firstOrNull(Files::isDirectory)
        requireNotNull(sourceRoot) { "Could not locate the production Kotlin source root" }

        val forbidden = listOf(
            "case conversion without Locale" to Regex("""\.(?:lowercase|uppercase)\(\)"""),
            "String format without Locale" to Regex(""""[^"\n]*%[^"\n]*"\.format\((?!Locale\.)"""),
            "date pattern without Locale" to Regex("""DateTimeFormatter\.ofPattern\(\s*"[^"]+"\s*\)"""),
            "localized date formatter without Locale" to Regex(
                """DateTimeFormatter\.ofLocalized(?:Date|Time|DateTime)\([^)]*\)(?!\s*\.withLocale)""",
            ),
            "default Locale lookup" to Regex("""Locale\.getDefault\(\)"""),
        )
        val offenders = buildList {
            Files.walk(sourceRoot).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                    .forEach { path ->
                        val source = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                        forbidden.forEach { (description, pattern) ->
                            if (pattern.containsMatchIn(source)) {
                                add("${sourceRoot.relativize(path)}: $description")
                            }
                        }
                    }
            }
        }

        assertTrue(
            "Locale-sensitive production APIs must declare Locale.US for English UI or " +
                "Locale.ROOT for normalization:\n${offenders.joinToString("\n")}",
            offenders.isEmpty(),
        )
    }
}
