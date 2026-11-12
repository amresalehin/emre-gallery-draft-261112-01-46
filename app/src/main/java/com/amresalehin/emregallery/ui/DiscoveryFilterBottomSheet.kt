package com.amresalehin.emregallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amresalehin.emregallery.data.DiscoveryFilterState
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryFilterBottomSheet(
    filterState: DiscoveryFilterState,
    matchingCount: Int = 0,
    onFilterChange: (DiscoveryFilterState) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = modifier.testTag("discovery_filter_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            // Header: Title, Live Match Count, and Reset Defaults button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Media Filters",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "$matchingCount matching items • Updates live",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 34.dp, top = 2.dp)
                    )
                }

                TextButton(
                    onClick = {
                        val reset = DiscoveryFilterState()
                        onFilterChange(reset)
                    },
                    modifier = Modifier.testTag("filter_reset_defaults_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Reset",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Reset",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Top Row of Category Toggle Switches
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // All (Text label as requested)
                    CategoryTogglePill(
                        text = "All",
                        contentDescription = "All media",
                        checked = filterState.enabledCategories.contains("ALL"),
                        onCheckedChange = { checked ->
                            val updated = if (checked) {
                                filterState.enabledCategories + "ALL"
                            } else {
                                filterState.enabledCategories - "ALL"
                            }
                            onFilterChange(filterState.copy(enabledCategories = updated))
                        },
                        tag = "category_toggle_all"
                    )

                    // Videos (Icon only)
                    CategoryTogglePill(
                        icon = Icons.Default.Videocam,
                        contentDescription = "Videos",
                        checked = filterState.enabledCategories.contains("VIDEOS"),
                        onCheckedChange = { checked ->
                            val updated = if (checked) {
                                filterState.enabledCategories + "VIDEOS"
                            } else {
                                filterState.enabledCategories - "VIDEOS"
                            }
                            onFilterChange(filterState.copy(enabledCategories = updated))
                        },
                        tag = "category_toggle_videos"
                    )

                    // Streams (Icon only)
                    CategoryTogglePill(
                        icon = Icons.Default.Podcasts,
                        contentDescription = "Streams",
                        checked = filterState.enabledCategories.contains("STREAMS"),
                        onCheckedChange = { checked ->
                            val updated = if (checked) {
                                filterState.enabledCategories + "STREAMS"
                            } else {
                                filterState.enabledCategories - "STREAMS"
                            }
                            onFilterChange(filterState.copy(enabledCategories = updated))
                        },
                        tag = "category_toggle_streams"
                    )

                    // Images (Icon only)
                    CategoryTogglePill(
                        icon = Icons.Default.Image,
                        contentDescription = "Images",
                        checked = filterState.enabledCategories.contains("IMAGES"),
                        onCheckedChange = { checked ->
                            val updated = if (checked) {
                                filterState.enabledCategories + "IMAGES"
                            } else {
                                filterState.enabledCategories - "IMAGES"
                            }
                            onFilterChange(filterState.copy(enabledCategories = updated))
                        },
                        tag = "category_toggle_images"
                    )
                }
            }

            // Dimension Slider 1: Minimum Width (Pocket screen reference: 0 to 4000 px)
            val minWidthDisplay = when {
                filterState.minWidth <= 0 -> "0 px (Any)"
                filterState.minWidth >= 4000 -> "4000+ px (∞)"
                else -> "${filterState.minWidth} px"
            }
            DimensionSliderRow(
                icon = Icons.Default.SwapHoriz,
                label = "Minimum Width",
                badgeText = minWidthDisplay,
                value = filterState.minWidth.toFloat(),
                valueRange = 0f..4000f,
                onValueChange = { newVal ->
                    onFilterChange(filterState.copy(minWidth = newVal.roundToInt()))
                },
                testTagPrefix = "min_width"
            )

            Spacer(Modifier.height(18.dp))

            // Dimension Slider 2: Minimum Height (Pocket screen reference: 0 to 4000 px)
            val minHeightDisplay = when {
                filterState.minHeight <= 0 -> "0 px (Any)"
                filterState.minHeight >= 4000 -> "4000+ px (∞)"
                else -> "${filterState.minHeight} px"
            }
            DimensionSliderRow(
                icon = Icons.Default.SwapVert,
                label = "Minimum Height",
                badgeText = minHeightDisplay,
                value = filterState.minHeight.toFloat(),
                valueRange = 0f..4000f,
                onValueChange = { newVal ->
                    onFilterChange(filterState.copy(minHeight = newVal.roundToInt()))
                },
                testTagPrefix = "min_height"
            )

            Spacer(Modifier.height(18.dp))

            // Media Size Slider 3: Minimum Media Size (Pocket screen reference: 0 to 10240 KB)
            val currentKb = (filterState.minSizeBytes / 1024L).toInt()
            val sizeBadgeText = when {
                currentKb <= 0 -> "0 KB (Any)"
                currentKb < 1024 -> "$currentKb KB"
                currentKb >= 10240 -> "10 MB"
                else -> "${String.format(Locale.US, "%.1f", currentKb / 1024f)} MB"
            }

            DimensionSliderRow(
                icon = Icons.Default.Folder,
                label = "Minimum Media Size",
                badgeText = sizeBadgeText,
                value = currentKb.toFloat(),
                valueRange = 0f..10240f,
                onValueChange = { newKb ->
                    val bytes = (newKb.roundToInt() * 1024L)
                    onFilterChange(filterState.copy(minSizeBytes = bytes))
                },
                testTagPrefix = "min_size"
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(20.dp))

            // Section: Filter by Media Type (Supported formats)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Filter by Media Type",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            onFilterChange(
                                filterState.copy(enabledFormats = DiscoveryFilterState.ALL_FORMATS.toSet())
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("All", style = MaterialTheme.typography.labelSmall)
                    }

                    TextButton(
                        onClick = {
                            onFilterChange(
                                filterState.copy(enabledFormats = emptySet())
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("None", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Images Section (SVG & PNG disabled by default)
            FormatCategorySection(
                title = "Image Formats",
                formats = DiscoveryFilterState.IMAGE_FORMATS,
                enabledFormats = filterState.enabledFormats,
                onToggleFormat = { fmt ->
                    val updated = if (filterState.enabledFormats.contains(fmt)) {
                        filterState.enabledFormats - fmt
                    } else {
                        filterState.enabledFormats + fmt
                    }
                    onFilterChange(filterState.copy(enabledFormats = updated))
                }
            )

            Spacer(Modifier.height(14.dp))

            // Video Section
            FormatCategorySection(
                title = "Video Formats",
                formats = DiscoveryFilterState.VIDEO_FORMATS,
                enabledFormats = filterState.enabledFormats,
                onToggleFormat = { fmt ->
                    val updated = if (filterState.enabledFormats.contains(fmt)) {
                        filterState.enabledFormats - fmt
                    } else {
                        filterState.enabledFormats + fmt
                    }
                    onFilterChange(filterState.copy(enabledFormats = updated))
                }
            )

            Spacer(Modifier.height(14.dp))

            // Streaming Section
            FormatCategorySection(
                title = "Live & Streams",
                formats = DiscoveryFilterState.STREAM_FORMATS,
                enabledFormats = filterState.enabledFormats,
                onToggleFormat = { fmt ->
                    val updated = if (filterState.enabledFormats.contains(fmt)) {
                        filterState.enabledFormats - fmt
                    } else {
                        filterState.enabledFormats + fmt
                    }
                    onFilterChange(filterState.copy(enabledFormats = updated))
                }
            )

            Spacer(Modifier.height(14.dp))

            // Audio Section
            FormatCategorySection(
                title = "Audio Formats",
                formats = DiscoveryFilterState.AUDIO_FORMATS,
                enabledFormats = filterState.enabledFormats,
                onToggleFormat = { fmt ->
                    val updated = if (filterState.enabledFormats.contains(fmt)) {
                        filterState.enabledFormats - fmt
                    } else {
                        filterState.enabledFormats + fmt
                    }
                    onFilterChange(filterState.copy(enabledFormats = updated))
                }
            )

            Spacer(Modifier.height(24.dp))

            // Done Button to dismiss sheet
            Button(
                onClick = onDismissRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("filter_done_btn"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = "Done",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Capsule pill button containing an icon or text and a switch.
 */
@Composable
private fun CategoryTogglePill(
    icon: ImageVector? = null,
    text: String? = null,
    contentDescription: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (checked) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .border(
                width = 1.dp,
                color = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(tag)
    ) {
        if (!text.isNullOrBlank()) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .height(24.dp)
                .width(36.dp),
            thumbContent = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

/**
 * Reusable row displaying an Icon, Label, Badge (value), and Slider.
 */
@Composable
private fun DimensionSliderRow(
    icon: ImageVector,
    label: String,
    badgeText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    testTagPrefix: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Value badge pill (as in screenshot e.g. "480 px", "540 px", "10 KB")
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("${testTagPrefix}_badge")
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Custom Slider with smooth continuous movement
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("${testTagPrefix}_slider"),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        )
    }
}

/**
 * Sub-section displaying format chips with selected state.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatCategorySection(
    title: String,
    formats: List<String>,
    enabledFormats: Set<String>,
    onToggleFormat: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            formats.forEach { format ->
                val isSelected = enabledFormats.contains(format)
                FilterChip(
                    selected = isSelected,
                    onClick = { onToggleFormat(format) },
                    label = {
                        Text(
                            text = format,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    leadingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.testTag("format_chip_${format.lowercase(Locale.ROOT)}")
                )
            }
        }
    }
}
