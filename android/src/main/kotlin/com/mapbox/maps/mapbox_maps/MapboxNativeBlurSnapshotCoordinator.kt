package com.mapbox.maps.mapbox_maps

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.mapbox.common.Cancelable
import com.mapbox.maps.CameraChangedCallback
import com.mapbox.maps.MapIdleCallback
import com.mapbox.maps.MapView
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap

internal interface MapboxNativeBlurSnapshotConsumer {
  fun isReadyForNativeBlurSnapshot(): Boolean

  fun updateFromNativeBlurSnapshot(snapshot: Bitmap, sourceMapView: MapView)
}

internal object MapboxNativeBlurSnapshotCoordinatorRegistry {
  private val coordinators = ConcurrentHashMap<Int, MapboxNativeBlurSnapshotCoordinator>()

  fun attach(
    sourceMapViewId: Int,
    consumer: MapboxNativeBlurSnapshotConsumer,
    updateIntervalMs: Long,
  ) {
    if (sourceMapViewId < 0) {
      return
    }

    coordinators.compute(sourceMapViewId) { _, existingCoordinator ->
      val coordinator = existingCoordinator ?: MapboxNativeBlurSnapshotCoordinator(sourceMapViewId)
      coordinator.attach(consumer, updateIntervalMs)
      coordinator
    }
  }

  fun detach(sourceMapViewId: Int, consumer: MapboxNativeBlurSnapshotConsumer) {
    val coordinator = coordinators[sourceMapViewId] ?: return
    coordinator.detach(consumer)
    if (coordinator.isEmpty()) {
      coordinators.remove(sourceMapViewId, coordinator)
    }
  }

  fun requestRefresh(sourceMapViewId: Int) {
    coordinators[sourceMapViewId]?.requestRefresh()
  }
}

private class MapboxNativeBlurSnapshotCoordinator(
  private val sourceMapViewId: Int,
) {
  private val handler = Handler(Looper.getMainLooper())
  private val consumers = LinkedHashSet<MapboxNativeBlurSnapshotConsumer>()
  private var updateIntervalMs = DEFAULT_UPDATE_INTERVAL_MS
  private var lastCaptureAtUptimeMs = 0L
  private var captureScheduled = false
  private var cameraChangedCancelable: Cancelable? = null
  private var mapIdleCancelable: Cancelable? = null

  private val captureRunnable = Runnable {
    captureScheduled = false

    if (consumers.isEmpty()) {
      return@Runnable
    }

    captureAndDispatch()
  }

  fun attach(consumer: MapboxNativeBlurSnapshotConsumer, requestedIntervalMs: Long) {
    consumers.add(consumer)
    updateIntervalMs = minOf(updateIntervalMs, requestedIntervalMs.coerceAtLeast(MIN_UPDATE_INTERVAL_MS))
    ensureMapSubscriptions()
    requestRefresh()
  }

  fun detach(consumer: MapboxNativeBlurSnapshotConsumer) {
    consumers.remove(consumer)
    if (consumers.isEmpty()) {
      dispose()
    }
  }

  fun isEmpty(): Boolean = consumers.isEmpty()

  fun requestRefresh() {
    ensureMapSubscriptions()
    if (consumers.isEmpty()) {
      return
    }

    val elapsedMs = SystemClock.uptimeMillis() - lastCaptureAtUptimeMs
    val delayMs = if (elapsedMs >= updateIntervalMs) 0L else updateIntervalMs - elapsedMs
    scheduleCapture(delayMs)
  }

  private fun ensureMapSubscriptions() {
    if (cameraChangedCancelable != null || mapIdleCancelable != null) {
      return
    }

    val sourceMapView = MapboxMapViewRegistry.get(sourceMapViewId) ?: return
    val eventProvider = sourceMapView.mapboxMap.styleManager
    cameraChangedCancelable = eventProvider.subscribe(
      CameraChangedCallback {
        requestRefresh()
      },
    )
    mapIdleCancelable = eventProvider.subscribe(
      MapIdleCallback {
        requestRefresh()
      },
    )
  }

  private fun scheduleCapture(delayMs: Long) {
    if (captureScheduled) {
      return
    }

    captureScheduled = true
    handler.postDelayed(captureRunnable, delayMs.coerceAtLeast(0L))
  }

  private fun captureAndDispatch() {
    val sourceMapView = MapboxMapViewRegistry.get(sourceMapViewId) ?: return
    val readyConsumers = consumers.filter { it.isReadyForNativeBlurSnapshot() }
    if (readyConsumers.isEmpty()) {
      return
    }

    val snapshot = sourceMapView.snapshot() ?: return
    lastCaptureAtUptimeMs = SystemClock.uptimeMillis()

    try {
      readyConsumers.forEach { consumer ->
        consumer.updateFromNativeBlurSnapshot(snapshot, sourceMapView)
      }
    } finally {
      if (!snapshot.isRecycled) {
        snapshot.recycle()
      }
    }
  }

  private fun dispose() {
    captureScheduled = false
    handler.removeCallbacks(captureRunnable)
    cameraChangedCancelable?.cancel()
    mapIdleCancelable?.cancel()
    cameraChangedCancelable = null
    mapIdleCancelable = null
  }

  private companion object {
    private const val DEFAULT_UPDATE_INTERVAL_MS = 48L
    private const val MIN_UPDATE_INTERVAL_MS = 16L
  }
}