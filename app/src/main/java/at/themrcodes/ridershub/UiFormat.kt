package at.themrcodes.ridershub

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Stable English formatting for the app's currently English-only UI. */
internal object UiFormat {
    fun decimal(value: Number, fractionDigits: Int, minimumWidth: Int = 0): String {
        require(fractionDigits >= 0)
        require(minimumWidth >= 0)
        val width = if (minimumWidth == 0) "" else "0$minimumWidth"
        return String.format(Locale.US, "%$width.${fractionDigits}f", value.toDouble())
    }

    fun duration(seconds: Double): String {
        val total = seconds.roundToInt().coerceAtLeast(0)
        return if (total >= 3600) {
            String.format(Locale.US, "%d:%02d h", total / 3600, total % 3600 / 60)
        } else {
            String.format(Locale.US, "%d:%02d", total / 60, total % 60)
        }
    }

    fun rideDate(value: String, zoneId: ZoneId = ZoneId.systemDefault()): String = runCatching {
        DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm", Locale.US)
            .format(Instant.parse(value).atZone(zoneId))
    }.getOrDefault(value)

    fun localDateTime(value: String, zoneId: ZoneId = ZoneId.systemDefault()): String = runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(Locale.US)
            .format(Instant.parse(value).atZone(zoneId))
    }.getOrDefault(value)
}
