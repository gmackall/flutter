package io.flutter.embedding.engine.systemchannels;

import androidx.annotation.NonNull;


// TODO(gmackall) docs, including where these methods are no ops
public interface UnifiedPlatformViewsHandler {
    /**
     * The Flutter application would like to display a new Android {@code View}, i.e., platform
     * view.
     */
    void createPlatformView(@NonNull PlatformViewsChannel3.PlatformViewCreationRequest request);

    /** The Flutter application would like to dispose of an existing Android {@code View}. */
    void dispose(int viewId);

    /**
     * The Flutter application would like to resize an existing Android {@code View}.
     *
     * @param request The request to resize the platform view.
     * @param onComplete Once the resize is completed, this is the handler to notify the size of the
     *     platform view buffer.
     */
    void resize(
            @NonNull PlatformViewsChannel.PlatformViewResizeRequest request, @NonNull PlatformViewsChannel.PlatformViewBufferResized onComplete);

    /**
     * The Flutter application would like to change the offset of an existing Android {@code View}.
     */
    void offset(int viewId, double top, double left);

    /**
     * The user touched a platform view within Flutter.
     *
     * <p>Touch data is reported in {@code touch}.
     */
    void onTouch(@NonNull PlatformViewsChannel3.PlatformViewTouch touch);

    /**
     * The Flutter application would like to change the layout direction of an existing Android
     * {@code View}, i.e., platform view.
     */
    void setDirection(int viewId, int direction);

    /** Clears the focus from the platform view with a give id if it is currently focused. */
    void clearFocus(int viewId);

    /** Whether the SurfaceControl swapchain is enabled. */
    boolean isSurfaceControlEnabled();

    /**
     * Whether the render surface of {@code FlutterView} should be converted to a {@code
     * FlutterImageView} when a {@code PlatformView} is added.
     *
     * <p>This is done to syncronize the rendering of the PlatformView and the FlutterView. Defaults
     * to true.
     */
    void synchronizeToNativeViewHierarchy(boolean yes);
}
