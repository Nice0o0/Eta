package io.github.mangi.eta.ui.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 单条消息的本地时间：今天只显示时分，昨天、今年和更早补上相对日期，
 * 避免每条消息都重复完整年月日。
 */
@Composable
internal fun MessageTimestampLabel(
    timestampMillis: Long?,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    if (timestampMillis == null || timestampMillis <= 0L) return
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val use24HourClock = DateFormat.is24HourFormat(context)
    val yesterdayLabel = context.getString(io.github.mangi.eta.R.string.time_yesterday)
    val text = remember(timestampMillis, locale, use24HourClock, yesterdayLabel) {
        formatMessageTimestamp(
            timestampMillis = timestampMillis,
            locale = locale,
            yesterdayLabel = yesterdayLabel,
            use24HourClock = use24HourClock,
        )
    }
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.72f),
        textAlign = textAlign,
        maxLines = 1,
        modifier = modifier,
    )
}

internal fun formatMessageTimestamp(
    timestampMillis: Long,
    locale: Locale,
    yesterdayLabel: String,
    use24HourClock: Boolean,
    nowMillis: Long = System.currentTimeMillis(),
    zone: TimeZone = TimeZone.getDefault(),
): String {
    val timePattern = if (use24HourClock) "HH:mm" else "h:mm a"
    val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }
    val time = SimpleDateFormat(timePattern, locale).apply { timeZone = zone }
        .format(Date(timestampMillis))
    val day = dayFormat.format(Date(timestampMillis))
    val today = dayFormat.format(Date(nowMillis))
    if (day == today) return time

    val yesterday = dayFormat.format(Date(nowMillis - ONE_DAY_MILLIS))
    if (day == yesterday) return "$yesterdayLabel $time"

    val yearFormat = SimpleDateFormat("yyyy", Locale.US).apply { timeZone = zone }
    val sameYear = yearFormat.format(Date(timestampMillis)) == yearFormat.format(Date(nowMillis))
    val dateFormat = SimpleDateFormat(
        when {
            locale.language == "zh" -> "M月d日"
            else -> "MMM d"
        },
        locale,
    ).apply { timeZone = zone }
    if (sameYear) return "${dateFormat.format(Date(timestampMillis))} $time"

    val fullFormat = SimpleDateFormat(
        when {
            locale.language == "zh" -> "yyyy年M月d日"
            else -> "MMM d, yyyy"
        },
        locale,
    ).apply { timeZone = zone }
    return "${fullFormat.format(Date(timestampMillis))} $time"
}

private const val ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L
