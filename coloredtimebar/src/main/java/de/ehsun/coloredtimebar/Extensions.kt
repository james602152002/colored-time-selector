package de.ehsun.coloredtimebar

import android.content.Context
import android.util.TypedValue
import kotlin.properties.ObservableProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

internal fun Context.dp2px(dp: Float): Float {
    val metrics = this.resources.displayMetrics
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, metrics)
}

//@Suppress("DEPRECATION")
//internal fun Context.getDrawableCompat(index: Int): Drawable {
//    return ContextCompat.getDrawable(this,index)
////    return if (Build.VERSION.SDK_INT >= 21) {
////        ContextCompat.getDrawable(this,index)
////        this.getDrawable(index)
////    } else {
////        this.resources.getDrawable(index)
////    }
//}

internal inline fun <T> doOnChange(initialValue: T, crossinline onChange: () -> Unit):
        ReadWriteProperty<Any?, T> = object : ObservableProperty<T>(initialValue) {
    override fun afterChange(property: KProperty<*>, oldValue: T, newValue: T) {
        if (oldValue != newValue) {
            onChange()
        }
    }
}