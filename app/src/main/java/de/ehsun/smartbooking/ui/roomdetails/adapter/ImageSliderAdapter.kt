package de.ehsun.smartbooking.ui.roomdetails.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.viewpager.widget.PagerAdapter
import com.squareup.picasso.Picasso
import de.ehsun.smartbooking.R

class ImageSliderAdapter(val context: Context, val images: List<String>) : PagerAdapter() {
    override fun isViewFromObject(view: View, obj: Any) = view == obj

    override fun getCount() = images.size

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val inflater = LayoutInflater.from(context)
        val viewLayout = inflater.inflate(R.layout.image_slide, container, false)

        with(viewLayout)
        {
            Picasso.Builder(context).build()
                .load(images[position])
                .into(viewLayout.findViewById<ImageView>(R.id.slideImageView))
            container.addView(viewLayout)
        }

        return viewLayout
    }

    override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
        container.removeView(obj as View)
    }
}