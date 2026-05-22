package com.mapbox.maps.mapbox_maps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import com.mapbox.maps.MapView
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import kotlin.math.roundToInt

class MapboxNativeBlurViewFactory : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
  override fun create(context: Context?, viewId: Int, args: Any?): PlatformView {
    if (context == null) {
      throw RuntimeException("Context is null, can't create Mapbox blur view!")
    }

    @Suppress("UNCHECKED_CAST")
    val params = args as? Map<String, Any?> ?: emptyMap()
    return MapboxNativeBlurPlatformView(context, params)
  }
}

private class MapboxNativeBlurPlatformView(
  context: Context,
  params: Map<String, Any?>
) : PlatformView {
  private val blurView = MapboxNativeBlurRegionView(context, params)

  override fun getView(): View = blurView

  override fun dispose() {
    blurView.dispose()
  }
}

private class MapboxNativeBlurRegionView(
  context: Context,
  params: Map<String, Any?>
) : FrameLayout(context), MapboxNativeBlurSnapshotConsumer {
  private val density = resources.displayMetrics.density
  private val imageView = ImageView(context)
  private val sourceMapViewId =
    (params["sourceMapPlatformViewId"] as? Number)?.toInt() ?: -1
  private val blurSigmaX =
    ((params["blurSigmaX"] as? Number)?.toFloat() ?: 0f) * density
  private val blurSigmaY =
    ((params["blurSigmaY"] as? Number)?.toFloat() ?: 0f) * density
  private val borderRadiusPx =
    ((params["borderRadius"] as? Number)?.toFloat() ?: 0f) * density
  private val updateIntervalMs =
    ((params["updateIntervalMs"] as? Number)?.toLong() ?: 48L).coerceAtLeast(16L)

  private var isRegisteredWithCoordinator = false
  private var displayedBitmap: Bitmap? = null
  private var lastLeft = Int.MIN_VALUE
  private var lastTop = Int.MIN_VALUE
  private var lastRight = Int.MIN_VALUE
  private var lastBottom = Int.MIN_VALUE

  init {
    addView(
      imageView,
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    )
    imageView.scaleType = ImageView.ScaleType.FIT_XY
    imageView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      imageView.setRenderEffect(
        RenderEffect.createBlurEffect(blurSigmaX, blurSigmaY, Shader.TileMode.CLAMP)
      )
    }

    if (borderRadiusPx > 0f) {
      clipToOutline = true
      outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
          outline.setRoundRect(0, 0, view.width, view.height, borderRadiusPx)
        }
      }
    }
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    registerWithCoordinator()
    requestSnapshotRefresh()
  }

  override fun onDetachedFromWindow() {
    unregisterFromCoordinator()
    super.onDetachedFromWindow()
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)

    val boundsChanged =
      changed ||
        left != lastLeft ||
        top != lastTop ||
        right != lastRight ||
        bottom != lastBottom

    if (!boundsChanged) {
      return
    }

    lastLeft = left
    lastTop = top
    lastRight = right
    lastBottom = bottom
    invalidateOutline()
    requestSnapshotRefresh()
  }

  override fun onVisibilityAggregated(isVisible: Boolean) {
    super.onVisibilityAggregated(isVisible)
    if (isVisible) {
      requestSnapshotRefresh()
    }
  }

  fun dispose() {
    unregisterFromCoordinator()
    releaseDisplayedBitmap()
  }

  private fun registerWithCoordinator() {
    if (isRegisteredWithCoordinator || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      return
    }
    MapboxNativeBlurSnapshotCoordinatorRegistry.attach(
      sourceMapViewId = sourceMapViewId,
      consumer = this,
      updateIntervalMs = updateIntervalMs,
    )
    isRegisteredWithCoordinator = true
  }

  private fun unregisterFromCoordinator() {
    if (!isRegisteredWithCoordinator) {
      return
    }
    MapboxNativeBlurSnapshotCoordinatorRegistry.detach(
      sourceMapViewId = sourceMapViewId,
      consumer = this,
    )
    isRegisteredWithCoordinator = false
  }

  private fun requestSnapshotRefresh() {
    if (!isRegisteredWithCoordinator) {
      return
    }
    MapboxNativeBlurSnapshotCoordinatorRegistry.requestRefresh(sourceMapViewId)
  }

  override fun isReadyForNativeBlurSnapshot(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
      isAttachedToWindow &&
      visibility == View.VISIBLE &&
      width > 0 &&
      height > 0
  }

  override fun updateFromNativeBlurSnapshot(snapshot: Bitmap, sourceMapView: MapView) {
    val croppedBitmap = cropToOverlayBounds(snapshot, sourceMapView)
    if (croppedBitmap == null) {
      return
    }

    val previousBitmap = displayedBitmap
    displayedBitmap = croppedBitmap
    imageView.setImageBitmap(croppedBitmap)
    if (previousBitmap != null && previousBitmap !== croppedBitmap && !previousBitmap.isRecycled) {
      previousBitmap.recycle()
    }
  }

  private fun cropToOverlayBounds(snapshot: Bitmap, sourceMapView: MapView): Bitmap? {
    val sourceLocation = IntArray(2)
    val overlayLocation = IntArray(2)
    sourceMapView.getLocationOnScreen(sourceLocation)
    getLocationOnScreen(overlayLocation)

    val leftPx = overlayLocation[0] - sourceLocation[0]
    val topPx = overlayLocation[1] - sourceLocation[1]
    val rightPx = leftPx + width
    val bottomPx = topPx + height

    val scaleX = snapshot.width.toFloat() / sourceMapView.width.toFloat()
    val scaleY = snapshot.height.toFloat() / sourceMapView.height.toFloat()

    val cropLeft = (leftPx * scaleX).roundToInt().coerceIn(0, snapshot.width)
    val cropTop = (topPx * scaleY).roundToInt().coerceIn(0, snapshot.height)
    val cropRight = (rightPx * scaleX).roundToInt().coerceIn(0, snapshot.width)
    val cropBottom = (bottomPx * scaleY).roundToInt().coerceIn(0, snapshot.height)

    val cropWidth = cropRight - cropLeft
    val cropHeight = cropBottom - cropTop
    if (cropWidth <= 0 || cropHeight <= 0) {
      return null
    }

    return Bitmap.createBitmap(snapshot, cropLeft, cropTop, cropWidth, cropHeight)
  }

  private fun releaseDisplayedBitmap() {
    val bitmap = displayedBitmap
    displayedBitmap = null
    imageView.setImageDrawable(null)
    if (bitmap != null && !bitmap.isRecycled) {
      bitmap.recycle()
    }
  }
}