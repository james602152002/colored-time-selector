package de.ehsun.coloredtimebar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.Scroller
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class TimelinePickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
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

    private var prevX = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val mFling by lazy { Scroller(context, null, false) }
    private var vTracker: VelocityTracker? = null
    private val mMaximumVelocity by lazy { ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat() }
    private val mMinimumVelocity by lazy { ViewConfiguration.get(context).scaledMinimumFlingVelocity }
    private val timeRangeRect = RectF()

    //是否选择时间，选择时间模式停止Fling
    private var isMovingHandle = false

    private var occupiedDown: ModelOccupied? = null
    private var occupiedUp: ModelOccupied? = null

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
                // avoid available range < timeRange, or picker will not have left handler
                handleLeftPos = max(timeRange.start.toMinutes(), start.toMinutes())
                handleRightPos = handleLeftPos + minSelectableTimeRange
                highlightRange =
                    SimpleTime.fromMinutes(handleLeftPos)..SimpleTime.fromMinutes(handleRightPos)

                //滚动模式自动定位到可以选择的部分
                if (scrollable) {
                    //开始时间前三十分钟
                    val thirtyMinBeforeStart = SimpleTime.fromMinutes(start.toMinutes() - 60)
                    //如果比availableRange早则，定位在scrollX = 0
//                    val maxScrollX =
//                        max(timeRangeToRect(thirtyMinBeforeStart..start).left.roundToInt(), 0)
                    val maxScrollX = timeRangeToRect(thirtyMinBeforeStart..start).left.roundToInt()
                    timeRangeRect.set(timeRangeToRect(timeRange))
                    mFling.startScroll(scrollX, 0, maxScrollX, 0, abs(maxScrollX - scrollX))
//                    scrollX = maxScrollX
                }
            } ?: kotlin.run {
            //如果没有可以选择的控件则把highLight左右Handler拿掉
            highlightRange = null
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
        when (newStylePicker) {
            true -> setMeasuredDimension(measuredWidth, measuredHeight)
            else -> setMeasuredDimension(
                measuredWidth,
                measuredHeight + (pickerDrawable?.intrinsicHeight ?: 0)
            )
        }

        xToPosConverter = { x ->
            val mainBarRange = timeRangeToRect.invoke(timeRange)
                .run { left..max(right, totalHourlyXOffset.toFloat()) }
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
                        when (newStylePicker) {
                            true -> {
                                val handle1Left = rect.left.toInt()
                                val handle2Left = rect.right.toInt()

                                listOf(
                                    Rect(
                                        handle1Left,
                                        rect.top.toInt(),
                                        handle1Left,
                                        rect.bottom.toInt()
                                    ),
                                    Rect(
                                        handle2Left,
                                        rect.top.toInt(),
                                        handle2Left,
                                        rect.bottom.toInt()
                                    )
                                )
                            }

                            else -> {
                                val handle1Left =
                                    (rect.left - ((pickerDrawable?.intrinsicWidth
                                        ?: 0) / 2f)).toInt()
                                val handle2Left =
                                    (rect.right - ((pickerDrawable?.intrinsicWidth
                                        ?: 0) / 2f)).toInt()
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
                        }
                    }
                    .forEach {
                        when (newStylePicker) {
                            true -> {
                                val lineX = (it.left + it.right) / 2f
                                val centerY = it.centerY().toFloat()
                                drawLine(
                                    lineX,
                                    it.top.toFloat(),
                                    lineX,
                                    it.bottom.toFloat(),
                                    linePickerPaint
                                )
                                drawCircle(lineX, centerY, circleRadius, linePickerCirclePaint)
                                drawCircle(lineX, centerY, circleRadius, linePickerPaint)
                            }

                            else -> {
                                pickerDrawable?.bounds = it
                                pickerDrawable?.draw(this)
                            }
                        }
                    }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        super.onTouchEvent(event)
//        //fix crash
        if (!scrollable) {
            return false
        }
        val touchX = scrollX + event.x
//        val hourCount = (timeRange.endInclusive - timeRange.start).toSeconds() / 3600
//        val totalWidth = width * hourCount / 4
        if (vTracker == null) {
            vTracker = VelocityTracker.obtain()
        }
        vTracker?.addMovement(event)

        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (!mFling.isFinished) {
                    mFling.abortAnimation()
                }
                isMovingHandle = false

                scrollJob?.cancel()
                prevX = event.x
                val pos = xToPosConverter(touchX)

                occupiedDown = null
                occupiedUp = null

                occupiedDown = occupiedModelRanges.find {
                    val start = it.timeRange?.start?.toMinutes()
                    val end = it.timeRange?.endInclusive?.toMinutes()
                    when {
                        start != null && end != null -> pos in start..end
                        else -> false
                    }
                }

//                occupiedRanges

                // consider touchX is closed to pickerPos then select picker handle to scroll, else decide downX to scroll
                if (highlightRange != null) {
                    val movingHandlerLimit = width / 20f
                    movingHandle = when {
                        abs(handleLeftPos - pos) < abs(handleRightPos - pos) -> {
                            when (abs(handleLeftPos - pos) <= movingHandlerLimit) {
                                true -> TimelineHandle.LEFT
                                else -> {
                                    availableRanges.find { handleRightPos in it.start.toMinutes()..it.endInclusive.toMinutes() }
                                        ?.let { availableRange ->
                                            if (availableRanges.find { pos in it.start.toMinutes()..it.endInclusive.toMinutes() } != null &&
                                                pos !in availableRange.start.toMinutes()..availableRange.endInclusive.toMinutes()) {
                                                setLeftHandle(pos)
                                            }
                                        }
                                    null
                                }
                            }
                        }

                        else -> {
                            when (abs(handleRightPos - pos) <= movingHandlerLimit) {
                                true -> TimelineHandle.RIGHT
                                else -> {
                                    availableRanges.find { handleLeftPos in it.start.toMinutes()..it.endInclusive.toMinutes() }
                                        ?.let { availableRange ->
                                            if (availableRanges.find { pos in it.start.toMinutes()..it.endInclusive.toMinutes() } != null &&
                                                pos !in availableRange.start.toMinutes()..availableRange.endInclusive.toMinutes()) {
                                                setRightHandle(pos)
                                            }
                                        }
                                    null
                                }
                            }
                        }
                    }
                    when (movingHandle) {
                        TimelineHandle.LEFT -> setLeftHandle(pos)
                        TimelineHandle.RIGHT -> setRightHandle(pos)
                        else -> {}
                    }
                }
                true
            }

            MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val pos = xToPosConverter(touchX)
                val moveX = event.x
                when (movingHandle) {
                    TimelineHandle.LEFT -> {
                        setLeftHandle(pos)
                        decideWhetherScrollDir(moveX, this::setLeftHandle)
                    }

                    TimelineHandle.RIGHT -> {
                        setRightHandle(pos)
                        decideWhetherScrollDir(moveX, this::setRightHandle)
                    }

                    else -> {
                        // scrolling
                        if (abs(prevX - moveX) > touchSlop) {
                            val nextScrollX = scrollX + (prevX - moveX).toInt()
                            scrollX = when (nextScrollX) {
                                in 0..(totalHourlyXOffset - width) -> nextScrollX
                                else -> if (nextScrollX < 0) 0 else when (scrollable) {
                                    true -> (totalHourlyXOffset - width)
                                    else -> 0
                                }
                            }
                            prevX = moveX
                        }
                    }
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                vTracker?.apply {
                    computeCurrentVelocity(1000, mMaximumVelocity)
                    val velocityX = xVelocity
                    val currentVelocity = abs(velocityX.roundToInt())
                    if (!isMovingHandle && currentVelocity > mMinimumVelocity) {
                        timeRangeRect.set(timeRangeToRect(timeRange))
                        mFling.fling(scrollX, 0, -velocityX.roundToInt(), 0, 0, width * 2, 0, 0)
                    }
                    recycle()
                    vTracker = null
                }

                if (occupiedDown != null) {
                    val pos = xToPosConverter(touchX)
                    occupiedUp = occupiedModelRanges.find {
                        val start = it.timeRange?.start?.toMinutes()
                        val end = it.timeRange?.endInclusive?.toMinutes()
                        when {
                            start != null && end != null -> pos in start..end
                            else -> false
                        }
                    }
                    if (occupiedDown == occupiedUp) {
                        occupiedDown?.onClick?.invoke()
                    }
                }
                occupiedUp = null
                occupiedDown = null

                //in scrollView when touch out of range then trigger cancel event
                if (event.x.toInt() in 0..width && event.y.toInt() in 0..height) {
                    scrollJob?.cancel()
                    parent.requestDisallowInterceptTouchEvent(false)
                    true
                } else {
                    //out of range
                    event.action = MotionEvent.ACTION_CANCEL
                    return onTouchEvent(event)
                }
            }
            //MotionEvent.ACTION_UP
            else -> {
                scrollJob?.cancel()
                parent.requestDisallowInterceptTouchEvent(false)
                true
            }
        }
    }

    override fun computeScroll() {
        if (mFling.computeScrollOffset()) {
            val rightBound = (timeRangeRect.right - width + timeRangeRect.left).toInt()
            //超越边界停止滚动
            scrollX = when (val currentX = mFling.currX) {
                in 0..rightBound -> currentX
                in Int.MIN_VALUE..0 -> 0
                else -> rightBound
            }
            postInvalidate()
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
        isMovingHandle = true
    }

    private inline fun startScroll(
        eventX: Float,
        dx: Int,
        crossinline scrollImpl: (offset: Int) -> Unit,
    ) {
        scrollJob?.cancel()
        scrollJob = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                if ((dx > 0 && scrollX + dx + width <= totalHourlyXOffset) || (dx < 0 && scrollX + dx >= 0)) {
                    scrollX += dx
                    val posOffset = xToPosConverter(eventX + scrollX)
//                    timeRange.endInclusive.minute
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

    fun invokeSelectedTimeRangeChanged() {
        highlightRange?.let {
            onSelectedTimeRangeChanged?.invoke(it.start, it.endInclusive)
        }
    }

    private fun setLeftHandle(newValue: Int) {
        //fix crash
        if (availableRanges.isEmpty() || !highlightEnable) {
            return
        }
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