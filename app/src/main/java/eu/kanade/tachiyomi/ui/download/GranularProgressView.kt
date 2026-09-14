package eu.kanade.tachiyomi.ui.download

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download

class GranularProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.download_progress_background)
    }

    private var download: Download? = null
    private val gap = 4f

    fun bind(download: Download) {
        this.download = download
        progressPaint.color = ContextCompat.getColor(context, R.color.download_progress_foreground)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val download = this.download ?: return
        val w = width.toFloat()
        val h = height.toFloat()

        if (download.engineType == "HLS") {
            val total = download.totalSegments
            if (total <= 0) return
            val segW = (w - (total - 1) * gap) / total
            for (i in 0 until total) {
                val left = i * (segW + gap)
                val isDownloaded = download.segmentProgress[i] ?: false
                canvas.drawRect(left, 0f, left + segW, h, if (isDownloaded) progressPaint else backgroundPaint)
            }
        } else {
            val total = download.activeThreads
            if (total <= 0) return
            val partW = (w - (total - 1) * gap) / total
            for (i in 0 until total) {
                val left = i * (partW + gap)
                val progress = download.partProgress[i] ?: 0f
                // Background
                canvas.drawRect(left, 0f, left + partW, h, backgroundPaint)
                // Foreground
                canvas.drawRect(left, 0f, left + (partW * progress), h, progressPaint)
            }
        }
    }
}
