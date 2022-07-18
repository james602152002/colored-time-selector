package de.ehsun.coloredtimebar

import android.content.Context
import android.graphics.*
import android.os.Build
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

open class TimelineView : View {

    var timeRange: ClosedRange<SimpleTime> by invalidateOnChange(
        SimpleTime(7, 0)..SimpleTime(
            19,
            0
        )
    )
    var timeTextInterval: Int by invalidateOnChange(1)

    var barWidth: Float by invalidateOnChange(context.dp2px(12f))
    var barColorAvailable: Int by invalidateOnChange((0xFF0F9648).toInt())
    var barColorNotAvailable: Int by invalidateOnChange((0xFFE42030).toInt())
    var barColorHighlight: Int by invalidateOnChange((0xFFE42030).toInt())
    var availableRanges: List<ClosedRange<SimpleTime>> by invalidateOnChange(listOf())
    var highlightRange: ClosedRange<SimpleTime>? by redrawOnChange(null)

    var fractionPrimaryTextColor: Int by invalidateOnChange((0xFF333333).toInt())
    var fractionSecondaryTextColor: Int by invalidateOnChange((0xFF333333).toInt())
    var fractionTextSize: Float by invalidateOnChange(context.dp2px(12f))
    var fractionLineWidth: Float by invalidateOnChange(context.dp2px(1f))
    var fractionLineLength: Float by invalidateOnChange(context.dp2px(4f))
    var fractionLineColor: Int by invalidateOnChange((0xFF0F9648).toInt())
    var linePickerColor: Int by invalidateOnChange(Color.BLACK)
    var linePickerCircleColor: Int by invalidateOnChange(Color.WHITE)
    var barStrokeColor: Int by invalidateOnChange(0)
    var circleRadius: Float by invalidateOnChange(context.dp2px(4f))

    private var dirty: Boolean = false
    private val drawingItems = mutableListOf<DrawingItem>()
    private var textPaint = TextPaint()
    private var barPaint = Paint()
    private var linePaint = Paint()
    protected val linePickerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val linePickerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastMeasuredWidth: Int = 0
    private var lastMeasuredHeight: Int = 0
    protected lateinit var timeRangeToRect: (ClosedRange<SimpleTime>) -> RectF
    private var maxHourIntervalInView = -1
    protected var totalHourlyXOffset = -1
    protected var scrollable = false

    // If true picker both in bar, else below the bar.
    var newStylePicker: Boolean by invalidateOnChange(true)
    var highlightEnable: Boolean by invalidateOnChange(true)

    private val strokeItems = mutableListOf<DrawingItem.TotalStroke>()

    companion object {
        private val TIME_FORMATTER = SimpleDateFormat("H:mm", Locale.US)
    }

    constructor(context: Context) : super(
        context
    )

    constructor(context: Context, attrs: AttributeSet?) : super(
        context,
        attrs
    )

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        initAttrs(attrs)
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(
        context,
        attrs,
        defStyleAttr,
        defStyleRes
    ) {
        initAttrs(attrs)
    }

    private fun initAttrs(attrs: AttributeSet?) {
        attrs?.let { attributeSet ->
            val typedArray =
                context.obtainStyledAttributes(attributeSet, R.styleable.TimelineView, 0, 0)
            typedArray.getString(R.styleable.TimelineView_timeRange)?.let { setTimeRange(it) }
            timeTextInterval = typedArray.getInt(R.styleable.TimelineView_fractionTextInterval, 1)
            maxHourIntervalInView = typedArray.getInt(
                R.styleable.TimelineView_maxHourIntervalInView,
                maxHourIntervalInView
            )
            barWidth = typedArray.getDimension(R.styleable.TimelineView_barWidth, barWidth)
            barColorAvailable =
                typedArray.getColor(R.styleable.TimelineView_barColorAvailable, barColorAvailable)
            barColorNotAvailable = typedArray.getColor(
                R.styleable.TimelineView_barColorNotAvailable,
                barColorNotAvailable
            )
            barColorHighlight =
                typedArray.getColor(R.styleable.TimelineView_barColorHighlight, barColorHighlight)
            fractionPrimaryTextColor = typedArray.getColor(
                R.styleable.TimelineView_fractionPrimaryTextColor,
                fractionPrimaryTextColor
            )
            fractionSecondaryTextColor = typedArray.getColor(
                R.styleable.TimelineView_fractionSecondaryTextColor,
                fractionSecondaryTextColor
            )
            fractionTextSize =
                typedArray.getDimension(R.styleable.TimelineView_fractionTextSize, fractionTextSize)
            fractionLineWidth = typedArray.getDimension(
                R.styleable.TimelineView_fractionLineWidth,
                fractionLineWidth
            )
            fractionLineLength = typedArray.getDimension(
                R.styleable.TimelineView_fractionLineLength,
                fractionLineLength
            )
            fractionLineColor =
                typedArray.getColor(R.styleable.TimelineView_fractionLineColor, fractionLineColor)
            linePickerColor =
                typedArray.getColor(R.styleable.TimelineView_linePickerColor, linePickerColor)
            linePickerCircleColor =
                typedArray.getColor(
                    R.styleable.TimelineView_linePickerCircleColor,
                    linePickerCircleColor
                )
            barStrokeColor =
                typedArray.getColor(R.styleable.TimelineView_barStrokeColor, barStrokeColor)
            circleRadius =
                typedArray.getDimension(R.styleable.TimelineView_circleRadius, circleRadius)
            highlightEnable =
                typedArray.getBoolean(R.styleable.TimelineView_highlightEnable, highlightEnable)
            newStylePicker =
                typedArray.getBoolean(R.styleable.TimelineView_newStylePicker, newStylePicker)
            typedArray.recycle()
        }
    }

    fun setTimeRange(timeRangeText: String) {
        parseTimeRange(timeRangeText)?.let { timeRange = it }
    }

    open fun setAvailableTimeRange(availableTimeRanges: List<String>) {
        availableRanges = availableTimeRanges.mapNotNull { parseTimeRange(it) }
    }

    open fun setHighlightTimeRange(timeRange: String) {
        highlightRange = parseTimeRange(timeRange)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (lastMeasuredWidth != measuredWidth || lastMeasuredHeight != measuredHeight) {
            val size = measureDrawingItems()
            setMeasuredDimension(size.first, max(size.second, measuredHeight))

            lastMeasuredWidth = measuredWidth
            lastMeasuredHeight = measuredHeight
        }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        if (dirty) {
            measureDrawingItems()
        }

        strokeItems.clear()
        drawingItems.forEach {
            when (it) {
                is DrawingItem.Text -> {
                    textPaint.color = it.color
                    canvas.drawText(it.text, it.x, it.y, textPaint)
                }
                is DrawingItem.Rectangle -> {
                    barPaint.color = it.color
                    canvas.drawRect(it.rect, barPaint)
                }
                is DrawingItem.Line -> {
                    linePaint.color = it.color
                    canvas.drawLine(it.from.x, it.from.y, it.to.x, it.to.y, linePaint)
                }
                is DrawingItem.TotalStroke -> {
                    strokeItems += it
                }
            }
        }

        if (highlightEnable) {
            highlightRange
                ?.let { range -> timeRangeToRect.invoke(range) }
                ?.let { rect ->
                    barPaint.color = barColorHighlight
                    canvas.drawRect(rect, barPaint)
                }
        }

        strokeItems.forEach {
            val minRight = min(width.toFloat() + scrollX, it.rect.right) - 1

            val textBound = measureText("80:08")
            val textWidth2 = textBound.width() / 2f
            val maxLeft = when (scrollX.toFloat() < textWidth2) {
                true -> it.rect.left + 1
                else -> scrollX.toFloat() + 1
            }
            canvas.drawLine(
                maxLeft,
                it.rect.top,
                maxLeft,
                it.rect.bottom,
                barStrokePaint
            )
            canvas.drawLine(
                it.rect.left,
                it.rect.top,
                it.rect.right,
                it.rect.top,
                barStrokePaint
            )
            canvas.drawLine(minRight, it.rect.top, minRight, it.rect.bottom, barStrokePaint)
            canvas.drawLine(
                it.rect.left,
                it.rect.bottom,
                it.rect.right,
                it.rect.bottom,
                barStrokePaint
            )
        }
    }

    private fun measureDrawingItems(): Pair<Int, Int> {
        dirty = false
        refreshPaints()
        val hourCount = (timeRange.endInclusive - timeRange.start).toSeconds() / 3600
        val textBound = measureText("80:08")
        val textWidth2 = textBound.width() / 2f
        val textHeight = textBound.height().toFloat()

//        val hourlyXOffset = (measuredWidth - textBound.width()) / hourCount - 1
        scrollable = maxHourIntervalInView > 0 && (hourCount > maxHourIntervalInView)
        val hourlyXOffset = (measuredWidth - textBound.width()) / when (scrollable) {
            true -> maxHourIntervalInView
            else -> hourCount
        } - 1
        // add textBoundWidth to handle last last time text
        totalHourlyXOffset = hourlyXOffset * hourCount + textBound.width()

        val textXPositions = (0..hourCount)
            .map { index -> (index * hourlyXOffset).toFloat() }
        val textItems = (0..hourCount)
            .filter { index -> index % timeTextInterval == 0 }
            .map { timeRange.start.toSeconds() + (it * 3600) }
            .map { formatTime(it) }
            .mapIndexed { index, text ->
                DrawingItem.Text(
                    text,
                    (index * timeTextInterval * hourlyXOffset).toFloat(),
                    textHeight,
                    if (index % 2 == 0) fractionPrimaryTextColor else fractionSecondaryTextColor
                )
            }

        val mainBarTop = textHeight + fractionLineLength

        val mainBarBottom = mainBarTop + barWidth
        val mainBarLeft = textXPositions.first() + textWidth2
        val mainBarRight = textXPositions.last() + textWidth2
        val mainBarRect = RectF(mainBarLeft, mainBarTop, mainBarRight, mainBarBottom)

        val timeToX: (SimpleTime) -> Float = { time ->
            val k =
                (time.toSeconds() - timeRange.start.toSeconds()) / (timeRange.endInclusive - timeRange.start).toSeconds()
                    .toFloat()
            mainBarLeft + (mainBarRight - mainBarLeft) * k
        }
        timeRangeToRect = { (start, end) ->
            RectF(timeToX(start), mainBarTop, timeToX(end), mainBarBottom)
        }
        val rectItems = listOf(DrawingItem.Rectangle(mainBarRect, barColorNotAvailable))
            .plus(availableRanges
                .map {
                    if (it.endInclusive <= timeRange.endInclusive) {
                        timeRangeToRect.invoke(it)
                    } else {
                        //when rect over time range then cut last rect part
                        timeRangeToRect.invoke(it.start..timeRange.endInclusive)
                    }
                }
                .map { DrawingItem.Rectangle(it, barColorAvailable) }
            )

        val fractionTop = textHeight + 1
        val lineItems = textXPositions
            .map { it + textWidth2 }
            .map {
                DrawingItem.Line(
                    PointF(it, fractionTop),
                    PointF(it, mainBarBottom),
                    fractionLineColor
                )
            }

        val totalBarStrokeItem = DrawingItem.TotalStroke(mainBarRect)

        drawingItems.clear()
        drawingItems += textItems
        drawingItems += lineItems
        drawingItems += rectItems
        drawingItems += totalBarStrokeItem
        return Pair(measuredWidth, (mainBarBottom + 1).toInt())
    }

    private fun invalidateItemsAndDraw() {
        dirty = true
        postInvalidate()
    }

    private fun measureText(text: String): Rect {
        val rect = Rect()
        textPaint.getTextBounds(text, 0, text.length, rect)
        return rect
    }

    @Suppress("DEPRECATION")
    private fun formatTime(timeInSeconds: Int): String {
        var totalSeconds = timeInSeconds
        val date = Date().apply {
            hours = totalSeconds / 3600
            totalSeconds -= hours * 3600
            minutes = totalSeconds / 60
            totalSeconds -= minutes * 60
            seconds = totalSeconds
        }
        return TIME_FORMATTER.format(date)
    }

    private fun refreshPaints() {
        with(textPaint) {
            this.textSize = this@TimelineView.fractionTextSize
            this.color = fractionPrimaryTextColor
        }
        with(barPaint) {
            this.color = barColorAvailable
        }
        with(linePaint) {
            this.color = fractionLineColor
            this.style = Paint.Style.STROKE
            this.strokeWidth = fractionLineWidth
        }
        with(linePickerPaint) {
            this.color = linePickerColor
            this.style = Paint.Style.STROKE
        }
        with(linePickerCirclePaint) {
            this.color = linePickerCircleColor
        }
        with(barStrokePaint) {
            this.color = barStrokeColor
        }
    }

    fun setBarWidth(px: Int) {
        barWidth = px.toFloat()
    }

    protected sealed class DrawingItem {
        data class Text(val text: String, val x: Float, val y: Float, val color: Int) :
            DrawingItem()

        data class Rectangle(val rect: RectF, val color: Int) : DrawingItem()
        data class TotalStroke(val rect: RectF) : DrawingItem()
        data class Line(val from: PointF, val to: PointF, val color: Int) : DrawingItem()
    }

    private inline fun <T> invalidateOnChange(initialValue: T) =
        doOnChange(initialValue) { invalidateItemsAndDraw() }

    private inline fun <T> redrawOnChange(initialValue: T?) =
        doOnChange(initialValue) { postInvalidate() }

    private fun parseTimeRange(timeRangeText: String): ClosedRange<SimpleTime>? {
        return timeRangeText.split("-")
            .map { SimpleTime.from(it.trim()) }
            .run {
                when (this.size) {
                    2 -> this[0]..this[1]
                    else -> null
                }
            }
    }
}