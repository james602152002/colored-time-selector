package de.ehsun.coloredtimebar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.math.abs

class TimelinePickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : TimelineView(context, attrs, defStyleAttr, defStyleRes) {

    var pickerDrawable: Drawable? =
        ContextCompat.getDrawable(context, R.drawable.ic_navigation_black_24dp)
    var stepSize: Int = 1
    var minSelectableTimeRange: Int = 15
    private var onSelectedTimeRangeChanged: ((from: SimpleTime, to: SimpleTime) -> Unit)? = null
    val selectedTimeRange: ClosedRange<SimpleTime>
        get() = SimpleTime.fromMinutes(handleLeftPos)..SimpleTime.fromMinutes(handleRightPos)

    private var handleLeftPos: Int by doOnChange(0) { postInvalidate() }
    private var handleRightPos: Int by doOnChange(60) { postInvalidate() }
    private lateinit var xToPosConverter: (Float) -> Int
    private var movingHandle: TimelineHandle? = null
    private var scrollJob: Job? = null

    init {
        attrs?.let {
            val typedArray =
                context.obtainStyledAttributes(it, R.styleable.TimelinePickerView, 0, 0)
            pickerDrawable = typedArray.getDrawable(R.styleable.TimelinePickerView_pickerDrawable)
                ?: pickerDrawable
            stepSize = typedArray.getInt(R.styleable.TimelinePickerView_stepSize, stepSize)
            minSelectableTimeRange = stepSize
            typedArray.recycle()
        }
    }

    override fun setAvailableTimeRange(availableTimeRanges: List<String>) {
        super.setAvailableTimeRange(availableTimeRanges)
        availableRanges.firstOrNull { (start, end) -> end.toMinutes() - start.toMinutes() >= minSelectableTimeRange }
            ?.let { (start, _) ->
                handleLeftPos = start.toMinutes()
                handleRightPos = handleLeftPos + minSelectableTimeRange
                highlightRange =
                    SimpleTime.fromMinutes(handleLeftPos)..SimpleTime.fromMinutes(handleRightPos)
            }
    }

    override fun setHighlightTimeRange(timeRange: String) {
        super.setHighlightTimeRange(timeRange)
        highlightRange?.let { onSelectedTimeRangeChanged?.invoke(it.start, it.endInclusive) }
        highlightRange?.start?.toMinutes()?.let { handleLeftPos = it }
        highlightRange?.endInclusive?.toMinutes()?.let { handleRightPos = it }
    }

    fun setOnSelectedTimeRangeChangedListener(callback: (from: SimpleTime, to: SimpleTime) -> Unit) {
        this.onSelectedTimeRangeChanged = callback
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight + (pickerDrawable?.intrinsicHeight ?: 0))

        xToPosConverter = { x ->
            val mainBarRange = timeRangeToRect.invoke(timeRange).run { left..right }
            when {
                x <= mainBarRange.start -> timeRange.start.toMinutes()
                x >= mainBarRange.endInclusive -> timeRange.endInclusive.toMinutes()
                else -> {
                    val k =
                        (x - mainBarRange.start) / (mainBarRange.endInclusive - mainBarRange.start)
                    (timeRange.start.toMinutes() + k * (timeRange.endInclusive - timeRange.start).toMinutes()).toInt()
                }
            }
        }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        canvas.drawHandles(handleLeftPos..handleRightPos)
    }

    private fun Canvas.drawHandles(range: IntRange) {
//        val timeRange =
//            SimpleTime.fromMinutes(range.start)..SimpleTime.fromMinutes(range.endInclusive)
//        val timeRange = (highlightRange?.start
//            ?: SimpleTime.fromMinutes(range.start))..(highlightRange?.endInclusive
//            ?: SimpleTime.fromMinutes(range.endInclusive))
        //drawPicker if has highlight
        if (highlightEnable) {
            highlightRange?.let { highlight ->
                val timeRange = highlight.start..highlight.endInclusive
                timeRangeToRect.invoke(timeRange)
                    .let { rect ->
                        val handle1Left =
                            (rect.left - ((pickerDrawable?.intrinsicWidth ?: 0) / 2f)).toInt()
                        val handle2Left =
                            (rect.right - ((pickerDrawable?.intrinsicWidth ?: 0) / 2f)).toInt()
                        val drawableWidth = pickerDrawable?.intrinsicWidth ?: 0
                        val drawableHeight = pickerDrawable?.intrinsicHeight ?: 0

                        listOf(
                            Rect(
                                handle1Left,
                                rect.bottom.toInt(),
                                handle1Left + drawableWidth,
                                rect.bottom.toInt() + drawableHeight
                            ),
                            Rect(
                                handle2Left,
                                rect.bottom.toInt(),
                                handle2Left + drawableWidth,
                                rect.bottom.toInt() + drawableHeight
                            )
                        )
                    }
                    .forEach {
                        pickerDrawable?.bounds = it
                        pickerDrawable?.draw(this)
                    }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        super.onTouchEvent(event)
        //fix crash
        if (availableRanges.isEmpty() || !highlightEnable) {
            return false
        }
        val touchX = scrollX + event.x
//        val hourCount = (timeRange.endInclusive - timeRange.start).toSeconds() / 3600
//        val totalWidth = width * hourCount / 4
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val pos = xToPosConverter(touchX)
                movingHandle = when {
                    abs(handleLeftPos - pos) < abs(handleRightPos - pos) -> TimelineHandle.LEFT
                    else -> TimelineHandle.RIGHT
                }
                when (movingHandle) {
                    TimelineHandle.LEFT -> setLeftHandle(pos)
                    TimelineHandle.RIGHT -> setRightHandle(pos)
                    else -> {}
                }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val pos = xToPosConverter(touchX)
                when (movingHandle) {
                    TimelineHandle.LEFT -> {
                        setLeftHandle(pos)
                        decideWhetherScrollDir(event.x, this::setLeftHandle)
                    }
                    TimelineHandle.RIGHT -> {
                        setRightHandle(pos)
                        decideWhetherScrollDir(event.x, this::setRightHandle)
                    }
                    else -> {}
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                parent.requestDisallowInterceptTouchEvent(false)
                true
            }
            else -> false
        }
    }

    private fun decideWhetherScrollDir(eventX: Float, scrollImpl: (offset: Int) -> Unit) {
        val scrollableBlock = width / 10
        val dx = width / 200
        when (eventX.toInt()) {
            in 0..scrollableBlock -> startScroll(eventX, -dx) { posOffset ->
                scrollImpl(posOffset)
            }
            in (width - scrollableBlock)..width -> startScroll(eventX, dx) { posOffset ->
                scrollImpl(posOffset)
            }
            else -> scrollJob?.cancel()
        }
    }

    private fun startScroll(eventX: Float, dx: Int, scrollImpl: (offset: Int) -> Unit) {
        scrollJob?.cancel()
        scrollJob = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                if ((dx > 0 && scrollX + dx + width <= totalHourlyXOffset) || (dx < 0 && scrollX + dx >= 0)) {
                    scrollX += dx
                    val posOffset = xToPosConverter(eventX + scrollX)
                    timeRange.endInclusive.minute
                    withContext(Dispatchers.Main) {
                        scrollImpl(posOffset)
                    }
                    delay(1)
                } else {
                    break
                }
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                scrollJob?.cancel()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun setLeftHandle(newValue: Int) {
        var correctValue = roundPosByStep(newValue)
        if (handleRightPos - correctValue < minSelectableTimeRange) {
            correctValue = handleRightPos - minSelectableTimeRange
        }

        val leftAvailableRange = availableRanges.find { it.contains(correctValue) }
        if (leftAvailableRange != null) {
            if (!leftAvailableRange.contains(handleRightPos)) {
                if (leftAvailableRange.endInclusive.toMinutes() - correctValue >= minSelectableTimeRange) {
                    handleRightPos = leftAvailableRange.endInclusive.toMinutes()
                } else {
                    correctValue =
                        availableRanges.find { it.contains(handleRightPos) }!!.start.toMinutes()
                }
            }
        } else {
            correctValue = availableRanges.find { it.contains(handleRightPos) }!!.start.toMinutes()
        }

        handleLeftPos = correctValue
        highlightRange =
            SimpleTime.fromMinutes(handleLeftPos)..SimpleTime.fromMinutes(handleRightPos)
        highlightRange?.let { onSelectedTimeRangeChanged?.invoke(it.start, it.endInclusive) }
    }

    private fun setRightHandle(newValue: Int) {
        var correctValue = roundPosByStep(newValue)
        if (correctValue - handleLeftPos < minSelectableTimeRange) {
            correctValue = handleLeftPos + minSelectableTimeRange
        }

        val rightAvailableRange = availableRanges.find { it.contains(correctValue) }
        if (rightAvailableRange != null) {
            if (!rightAvailableRange.contains(handleLeftPos)) {
                if (correctValue - rightAvailableRange.start.toMinutes() >= minSelectableTimeRange) {
                    handleLeftPos = rightAvailableRange.start.toMinutes()
                } else {
                    correctValue =
                        availableRanges.find { it.contains(handleLeftPos) }!!.endInclusive.toMinutes()
                }
            }
        } else {
            // crash fix
            correctValue =
                availableRanges.find { it.contains(handleLeftPos) }?.endInclusive?.toMinutes()
                    ?: return
        }

        handleRightPos = correctValue
        highlightRange =
            SimpleTime.fromMinutes(handleLeftPos)..SimpleTime.fromMinutes(handleRightPos)
        highlightRange?.let { onSelectedTimeRangeChanged?.invoke(it.start, it.endInclusive) }
    }

    private fun roundPosByStep(pos: Int) = (pos / stepSize) * stepSize

    private fun ClosedRange<SimpleTime>.contains(value: Int) =
        start.toMinutes() <= value && value <= endInclusive.toMinutes()

    private enum class TimelineHandle { LEFT, RIGHT }
}