package com.mapbox.maps.mapbox_maps

import com.mapbox.maps.MapView
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

internal object MapboxMapViewRegistry {
  private val mapViews = ConcurrentHashMap<Int, WeakReference<MapView>>()

  fun register(viewId: Int, mapView: MapView) {
    mapViews[viewId] = WeakReference(mapView)
  }

  fun unregister(viewId: Int) {
    mapViews.remove(viewId)
  }

  fun get(viewId: Int): MapView? {
    val reference = mapViews[viewId] ?: return null
    val mapView = reference.get()
    if (mapView == null) {
      mapViews.remove(viewId)
    }
    return mapView
  }
}