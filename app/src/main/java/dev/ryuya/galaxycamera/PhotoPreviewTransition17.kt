package dev.ryuya.galaxycamera

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader.TileMode
import android.hardware.camera2.CaptureRequest.Key
import android.hardware.camera2.TotalCaptureResult
import android.view.PixelCopy
import android.view.PixelCopy.OnPixelCopyFinishedListener
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.FrameLayout.LayoutParams
import android.widget.ImageView
import android.widget.ImageView.ScaleType
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/*
 * One preview copy per transition; geometric motion is rendered independently of camera cadence.
 */
class PhotoPreviewTransition17(
    runtime: HostRuntime,
    preview: SurfaceView?,
    from: Float,
    to: Float,
    completed: Runnable,
) {
    private val runtime: HostRuntime
    private val preview: SurfaceView
    private val parent: FrameLayout
    private val from: Float
    private val to: Float
    private val completed: Runnable
    private val scaleX: Float
    private val scaleY: Float
    private val pivotX: Float
    private val pivotY: Float
    private val frameRate: Float
    private val originalClip: Rect?
    private val animatedClip: Rect = Rect()
    private val copyTimeout: Runnable
    private val handoffTimeout: Runnable
    private val width: Int
    private val height: Int
    private var failure: Runnable? = null
    private var cover: FrameLayout? = null
    private var snapshot: ImageView? = null
    private var fade: ValueAnimator? = null
    private var copyPending: Boolean = true
    private var ready: Boolean = false
    private var travelFinished: Boolean = false
    private var fadeFinished: Boolean = false
    @Volatile private var copied: Boolean = false
    @Volatile private var closed: Boolean = false
    private var matchingFrames: Int = 0
    private var current: Float
    private var ownsPreview: Boolean = false

    init {
        require(
            !(preview == null ||
                !preview.isAttachedToWindow() ||
                !preview.getHolder().getSurface().isValid() ||
                (preview.getParent() !is FrameLayout) ||
                preview.getWidth() <= 0 ||
                preview.getHeight() <= 0 ||
                !java.lang.Float.isFinite(from) ||
                !java.lang.Float.isFinite(to) ||
                from <= 0 ||
                to <= 0 ||
                from == to)
        ) {
            "Preview cannot be transformed"
        }
        this.runtime = runtime
        this.preview = preview
        this.parent = (preview.getParent() as FrameLayout?)!!
        this.from = from
        this.to = to
        this.current = from
        this.completed = completed
        width = preview.getWidth()
        height = preview.getHeight()
        scaleX = preview.getScaleX()
        scaleY = preview.getScaleY()
        pivotX = preview.getPivotX()
        pivotY = preview.getPivotY()
        originalClip = preview.getClipBounds()
        frameRate = preview.getRequestedFrameRate()
        copyTimeout = Runnable { this.copyFailed() }
        handoffTimeout = Runnable { this.finish() }
    }

    /*
     * Capture before submitting the final lens request. No bitmap is copied in the animation loop.
     */
    fun capture(captured: Runnable, fallback: Runnable?) {
        failure = fallback
        val reduction = min(1f, 1280f / max(width, height))
        val pixels: Bitmap
        try {
            pixels =
                Bitmap.createBitmap(
                    max(1, Math.round(width * reduction)),
                    max(1, Math.round(height * reduction)),
                    Config.ARGB_8888,
                )
        } catch (unavailable: OutOfMemoryError) {
            copyFailed()
            return
        } catch (unavailable: IllegalArgumentException) {
            copyFailed()
            return
        }
        runtime.main.postDelayed(copyTimeout, 250)
        try {
            PixelCopy.request(
                preview,
                pixels,
                OnPixelCopyFinishedListener { result: Int ->
                    if (closed || !copyPending) {
                        pixels.recycle()
                        return@OnPixelCopyFinishedListener
                    }
                    if (result != PixelCopy.SUCCESS || !samePreview()) {
                        pixels.recycle()
                        copyFailed()
                        return@OnPixelCopyFinishedListener
                    }
                    runtime.main.removeCallbacks(copyTimeout)
                    try {
                        cover = FrameLayout(preview.getContext())
                        cover!!.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        cover!!.setClipChildren(true)
                        cover!!.setClipToPadding(true)
                        cover!!.setImportantForAccessibility(
                            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        )
                        if (to < from) {
                            val background = image(pixels)
                            background.setRenderEffect(
                                RenderEffect.createBlurEffect(18f, 18f, TileMode.CLAMP)
                            )
                            cover!!.addView(background, LayoutParams(-1, -1))
                        }
                        snapshot = image(pixels)
                        if (to < from) softenEdges(snapshot!!)
                        cover!!.addView(snapshot, LayoutParams(-1, -1))
                        val position = LayoutParams(width, height)
                        position.leftMargin = preview.getLeft()
                        position.topMargin = preview.getTop()
                        parent.addView(cover, position)
                        cover!!.setRequestedFrameRate(60f)
                        ownsPreview = true
                        preview.setRequestedFrameRate(60f)
                        copyPending = false
                        copied = true
                        runtime.main.postDelayed(handoffTimeout, 2000)
                        ModernHook.log("Photo display handoff: snapshot ready")
                        captured.run()
                    } catch (unavailable: RuntimeException) {
                        // A pause or configuration change can invalidate the captured surface.
                        copyPending = true
                        copyFailed()
                    }
                },
                runtime.main,
            )
        } catch (unavailable: IllegalArgumentException) {
            pixels.recycle()
            copyFailed()
        }
    }

    private fun samePreview(): Boolean {
        return preview.isAttachedToWindow() &&
            preview.getParent() === parent &&
            preview.getHolder().getSurface().isValid() &&
            preview.getWidth() == width &&
            preview.getHeight() == height
    }

    private fun image(pixels: Bitmap?): ImageView {
        val image = ImageView(preview.getContext())
        image.setScaleType(ScaleType.FIT_XY)
        image.setImageBitmap(pixels)
        image.setClickable(false)
        image.setFocusable(false)
        return image
    }

    private fun copyFailed() {
        if (closed || !copyPending) return
        copyPending = false
        close()
        ModernHook.log("Photo display handoff: native fallback")
        failure!!.run()
    }

    fun sample(zoom: Float, last: Boolean) {
        if (closed || !copied) return
        if (!samePreview()) {
            finish()
            return
        }
        current = zoom
        val oldScale = LensPreviewGeometry.snapshotScale(from, current)
        snapshot!!.setPivotX(preview.getWidth() / 2f)
        snapshot!!.setPivotY(preview.getHeight() / 2f)
        snapshot!!.setScaleX(oldScale)
        snapshot!!.setScaleY(oldScale)
        if (ready && to < from) transformPreview()
        if (last) {
            travelFinished = true
            maybeFade()
            if (fadeFinished) finish()
        }
    }

    /*
     * Runs on the camera callback thread; wait for successive frames containing the final request.
     */
    fun observe(result: TotalCaptureResult) {
        if (closed || !copied) return
        val zoom: Float?
        try {
            zoom = result.getRequest().get<Float?>(PhotoPreviewTransition17.ZOOM)
        } catch (unavailable: IllegalArgumentException) {
            return
        }
        if (zoom == null || abs(zoom - to) > 0.001f) {
            matchingFrames = 0
            return
        }
        if (++matchingFrames == 2)
            runtime.main.postDelayed(
                Runnable {
                    if (closed) return@Runnable
                    ready = true
                    if (to < from) transformPreview()
                    maybeFade()
                },
                40,
            )
    }

    private fun transformPreview() {
        val scale = LensPreviewGeometry.incomingScale(to, current)
        preview.setPivotX(preview.getWidth() / 2f)
        preview.setPivotY(preview.getHeight() / 2f)
        preview.setScaleX(scaleX * scale)
        preview.setScaleY(scaleY * scale)
        val x = Math.round(preview.getWidth() * (1f - 1f / scale) / 2f)
        val y = Math.round(preview.getHeight() * (1f - 1f / scale) / 2f)
        animatedClip.set(x, y, preview.getWidth() - x, preview.getHeight() - y)
        if (originalClip != null) animatedClip.intersect(originalClip)
        preview.setClipBounds(animatedClip)
    }

    private fun maybeFade() {
        if (closed || !ready || fade != null || (to > from && !travelFinished)) return
        fade = ValueAnimator.ofFloat(1f, 0f)
        fade!!.setDuration(90)
        fade!!.addUpdateListener(
            AnimatorUpdateListener { animation: ValueAnimator? ->
                cover!!.setAlpha(animation!!.getAnimatedValue() as Float)
            }
        )
        fade!!.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (closed) return
                    fadeFinished = true
                    if (travelFinished) finish()
                }
            }
        )
        fade!!.start()
    }

    private fun finish() {
        if (closed) return
        close()
        ModernHook.log("Photo display handoff: restored; new preview=" + ready)
        completed.run()
    }

    /* A single GPU mask blends the shrinking picture into its blurred surround. */
    private fun softenEdges(picture: ImageView) {
        val mask =
            RuntimeShader(
                """
                uniform shader content;
                uniform float2 size;
                uniform float edge;
                half4 main(float2 point) {
                  float2 distance = min(point, size - point);
                  float alpha = smoothstep(0.0, edge, min(distance.x, distance.y));
                  return content.eval(point) * half(alpha);
                }

                """
                    .trimIndent()
            )
        mask.setFloatUniform("size", width.toFloat(), height.toFloat())
        mask.setFloatUniform("edge", min(width, height) * 0.08f)
        picture.setRenderEffect(RenderEffect.createRuntimeShaderEffect(mask, "content"))
    }

    fun close() {
        if (closed) return
        closed = true
        runtime.main.removeCallbacks(copyTimeout)
        runtime.main.removeCallbacks(handoffTimeout)
        if (fade != null) fade!!.cancel()
        if (ownsPreview) {
            preview.setScaleX(scaleX)
            preview.setScaleY(scaleY)
            preview.setPivotX(pivotX)
            preview.setPivotY(pivotY)
            preview.setClipBounds(originalClip)
            if (preview.getRequestedFrameRate() == 60f) preview.setRequestedFrameRate(frameRate)
        }
        if (cover != null) {
            parent.removeView(cover)
            // Detached display lists may still reference the pixels. Let Bitmap's ownership release
            // them.
            cover!!.removeAllViews()
            cover = null
            snapshot = null
        }
    }

    companion object {
        private val ZOOM: Key<Float?> =
            Key<Float?>("samsung.android.scaler.zoomRatio", Float::class.java)
    }
}
