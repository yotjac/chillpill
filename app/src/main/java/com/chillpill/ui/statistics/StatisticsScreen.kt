package com.chillpill.ui.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chillpill.ChillpillApp
import com.chillpill.ui.common.AppIcon
import androidx.compose.ui.res.stringResource
import com.chillpill.R

private const val FocusedAlpha = 1f
private const val UnfocusedAlpha = 0.3f

private val AttemptedColorHoney = Color(0xFFD4A017)
private val ChartGridLineAlpha = 0.15f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    app: ChillpillApp,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: StatisticsViewModel = viewModel(factory = StatisticsViewModel.Factory(app))
    val selectedRange by viewModel.selectedRange.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val focusedSeries by viewModel.focusedSeries.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.statistics_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp)
            ) {
                TimeRange.entries.forEach { range ->
                    SegmentedButton(
                        selected = selectedRange == range,
                        onClick = { viewModel.onRangeSelected(range) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = range.ordinal,
                            count = TimeRange.entries.size
                        )
                    ) {
                        Text(stringResource(range.labelRes))
                    }
                }
            }

            if (isLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (stats.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.statistics_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    LegendItem(
                        label = stringResource(R.string.statistics_legend_attempted),
                        color = AttemptedColorHoney,
                        isFocused = focusedSeries == FocusedSeries.ATTEMPTED,
                        onClick = { viewModel.onLegendClicked(FocusedSeries.ATTEMPTED) }
                    )
                    LegendItem(
                        label = stringResource(R.string.statistics_legend_entered),
                        color = MaterialTheme.colorScheme.primary,
                        isFocused = focusedSeries == FocusedSeries.ENTERED,
                        onClick = { viewModel.onLegendClicked(FocusedSeries.ENTERED) }
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(stats, key = { it.packageName }) { stat ->
                        AppStatCard(
                            stat = stat,
                            focusedSeries = focusedSeries
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendItem(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    isFocused: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(vertical = 4.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = color,
            modifier = Modifier.size(12.dp)
        ) {}
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun AppStatCard(
    stat: AppStatistic,
    focusedSeries: FocusedSeries,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(),
        elevation = CardDefaults.elevatedCardElevation()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(
                packageName = stat.packageName,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stat.appLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.statistics_today_summary, stat.attemptsToday, stat.enteredToday),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                BarChart(
                    buckets = stat.buckets,
                    focusedSeries = focusedSeries,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                )
            }
        }
    }
}

/** Rounds up to a scale max divisible by 4 so grid lines at 25%, 50%, 75% are whole numbers. */
private fun niceScaleMax(dataMax: Int): Int {
    if (dataMax <= 0) return 4
    return maxOf(4, ((dataMax + 3) / 4) * 4)
}

@Composable
private fun BarChart(
    buckets: List<BucketStat>,
    focusedSeries: FocusedSeries,
    modifier: Modifier = Modifier
) {
    val attemptedColor = AttemptedColorHoney
    val enteredColor = MaterialTheme.colorScheme.primary
    val chartBackgroundColor = MaterialTheme.colorScheme.surface
    val onChartColor = MaterialTheme.colorScheme.onSurface
    val maxValue = buckets.maxOfOrNull { maxOf(it.attempts, it.entered) }?.coerceAtLeast(1) ?: 1
    val scaleMax = niceScaleMax(maxValue)
    val barGapPx = 2.dp
    val density = LocalDensity.current
    val barInsetPx = with(density) { 6.dp.toPx() }
    val gridLineColor = onChartColor.copy(alpha = ChartGridLineAlpha)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = onChartColor.copy(alpha = 0.45f), fontSize = 9.sp)

    Canvas(modifier = modifier) {
        if (buckets.isEmpty()) return@Canvas
        val chartWidth = size.width
        val chartHeight = size.height

        drawRect(color = chartBackgroundColor, size = size)

        val sampleLabel = textMeasurer.measure(scaleMax.toString(), labelStyle)
        val rightMargin = sampleLabel.size.width + 6.dp.toPx()
        val barAreaWidth = chartWidth - rightMargin

        val gridLineCount = 4
        for (i in 1 until gridLineCount) {
            val y = chartHeight * i / gridLineCount
            drawLine(
                color = gridLineColor,
                start = Offset(0f, y),
                end = Offset(barAreaWidth, y),
                strokeWidth = 1f
            )
            val value = scaleMax * (gridLineCount - i) / gridLineCount
            val measured = textMeasurer.measure(value.toString(), labelStyle)
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(
                    chartWidth - measured.size.width - 2.dp.toPx(),
                    y - measured.size.height / 2f
                )
            )
        }

        val barRegionWidth = barAreaWidth - 2 * barInsetPx
        val barGroupWidth = (barRegionWidth - barGapPx.toPx() * (buckets.size - 1)) / buckets.size.toFloat()
        val barWidth = (barGroupWidth - barGapPx.toPx()).coerceAtLeast(1f)

        buckets.forEachIndexed { index, bucket ->
            val left = barInsetPx + index * (barGroupWidth + barGapPx.toPx())
            val attemptedAlpha = when (focusedSeries) {
                FocusedSeries.NONE -> FocusedAlpha
                FocusedSeries.ATTEMPTED -> FocusedAlpha
                FocusedSeries.ENTERED -> UnfocusedAlpha
            }
            val enteredAlpha = when (focusedSeries) {
                FocusedSeries.NONE -> FocusedAlpha
                FocusedSeries.ATTEMPTED -> UnfocusedAlpha
                FocusedSeries.ENTERED -> FocusedAlpha
            }

            val attemptedHeight = (bucket.attempts.toFloat() / scaleMax * chartHeight).coerceAtLeast(0f)
            val enteredHeight = (bucket.entered.toFloat() / scaleMax * chartHeight).coerceAtLeast(0f)
            val cornerRadius = CornerRadius(2.dp.toPx())

            drawRoundRect(
                color = attemptedColor.copy(alpha = attemptedAlpha),
                topLeft = Offset(left, chartHeight - attemptedHeight),
                size = Size(barWidth, attemptedHeight),
                cornerRadius = cornerRadius
            )
            drawRoundRect(
                color = enteredColor.copy(alpha = enteredAlpha),
                topLeft = Offset(left, chartHeight - enteredHeight),
                size = Size(barWidth, enteredHeight),
                cornerRadius = cornerRadius
            )
        }
    }
}
