part of mapbox_maps_flutter;

/// Android-only platform view that renders a native blurred crop of a source
/// Mapbox map view.
class MapboxNativeBlurView extends StatelessWidget {
  const MapboxNativeBlurView({
    super.key,
    required this.sourceMapPlatformViewId,
    required this.blurSigmaX,
    required this.blurSigmaY,
    this.borderRadius = 0,
    this.updateInterval = const Duration(milliseconds: 48),
  });

  /// Flutter platform-view id of the source [MapWidget].
  final int sourceMapPlatformViewId;

  /// Horizontal blur sigma in logical pixels.
  final double blurSigmaX;

  /// Vertical blur sigma in logical pixels.
  final double blurSigmaY;

  /// Corner radius in logical pixels.
  final double borderRadius;

  /// Interval between native map snapshot refreshes.
  final Duration updateInterval;

  @override
  Widget build(BuildContext context) {
    if (defaultTargetPlatform != TargetPlatform.android) {
      return const SizedBox.shrink();
    }

    final Map<String, dynamic> creationParams = <String, dynamic>{
      'sourceMapPlatformViewId': sourceMapPlatformViewId,
      'blurSigmaX': blurSigmaX,
      'blurSigmaY': blurSigmaY,
      'borderRadius': borderRadius,
      'updateIntervalMs': updateInterval.inMilliseconds,
    };

    return PlatformViewLink(
      viewType: 'plugins.flutter.io/mapbox_maps_blur_view',
      surfaceFactory: (BuildContext context, PlatformViewController controller) {
        return AndroidViewSurface(
          controller: controller as AndroidViewController,
          hitTestBehavior: PlatformViewHitTestBehavior.transparent,
          gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
        );
      },
      onCreatePlatformView: (PlatformViewCreationParams params) {
        final AndroidViewController controller =
            PlatformViewsService.initSurfaceAndroidView(
              id: params.id,
              viewType: 'plugins.flutter.io/mapbox_maps_blur_view',
              layoutDirection: TextDirection.ltr,
              creationParams: creationParams,
              creationParamsCodec: const StandardMessageCodec(),
              onFocus: () => params.onFocusChanged(true),
            );

        controller.addOnPlatformViewCreatedListener(
          params.onPlatformViewCreated,
        );
        controller.create();
        return controller;
      },
    );
  }
}