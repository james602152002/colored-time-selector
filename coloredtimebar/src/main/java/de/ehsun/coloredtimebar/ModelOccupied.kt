package de.ehsun.coloredtimebar

import android.graphics.Color

data class ModelOccupied(
    var time: String? = null,
    var timeRange: ClosedRange<SimpleTime>? = null,
    var text: String? = null,
    var color: Int = Color.RED,
    var onClick: () -> Unit = {},
)
