package com.glass.dining.shared.hud

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.glass.dining.shared.R
import kotlin.math.sin

object ReimuHud {
    private val cache = HashMap<TalkPose, Bitmap>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dest = RectF()

    fun resId(pose: TalkPose): Int {
        return when (pose) {
            TalkPose.IDLE -> R.drawable.reimu_idle
            TalkPose.LISTEN -> R.drawable.reimu_listen
            TalkPose.THINK -> R.drawable.reimu_think
            TalkPose.SPEAK -> R.drawable.reimu_speak
            TalkPose.LOOK -> R.drawable.reimu_look
        }
    }

    fun draw(
        canvas: Canvas,
        resources: Resources,
        cx: Float,
        cy: Float,
        size: Float,
        pose: TalkPose,
        tSec: Float,
    ) {
        val bmp = cache.getOrPut(pose) {
            BitmapFactory.decodeResource(resources, resId(pose))
        }
        val bob = TalkLayout.BOB_PX * sin(tSec * 2.4f)
        val h = size
        val w = h * bmp.width / bmp.height
        dest.set(cx - w / 2f, cy - h / 2f + bob, cx + w / 2f, cy + h / 2f + bob)
        canvas.drawBitmap(bmp, null, dest, paint)
    }
}
