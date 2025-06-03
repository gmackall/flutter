package io.flutter.embedding.engine.systemchannels;

import androidx.annotation.NonNull;

/** The state of a touch event in Flutter within a platform view. */
public class PlatformViewTouch {
    /** The ID of the platform view as seen by the Flutter side. */
    public final int viewId;

    /** The amount of time that the touch has been pressed. */
    @NonNull
    public final Number downTime;
    /** TODO(mattcarroll): javadoc */
    @NonNull public final Number eventTime;

    public final int action;
    /** The number of pointers (e.g, fingers) involved in the touch event. */
    public final int pointerCount;
    /**
     * Properties for each pointer, encoded in a raw format. Expected to be formatted as a
     * List[List[Integer]], where each inner list has two items: - An id, at index 0, corresponding
     * to {@link android.view.MotionEvent.PointerProperties#id} - A tool type, at index 1,
     * corresponding to {@link android.view.MotionEvent.PointerProperties#toolType}.
     */
    @NonNull public final Object rawPointerPropertiesList;
    /** Coordinates for each pointer, encoded in a raw format. */
    @NonNull public final Object rawPointerCoords;
    /** TODO(mattcarroll): javadoc */
    public final int metaState;
    /** TODO(mattcarroll): javadoc */
    public final int buttonState;
    /** Coordinate precision along the x-axis. */
    public final float xPrecision;
    /** Coordinate precision along the y-axis. */
    public final float yPrecision;
    /** TODO(mattcarroll): javadoc */
    public final int deviceId;
    /** TODO(mattcarroll): javadoc */
    public final int edgeFlags;
    /** TODO(mattcarroll): javadoc */
    public final int source;
    /** TODO(mattcarroll): javadoc */
    public final int flags;
    /** TODO(iskakaushik): javadoc */
    public final long motionEventId;

    public PlatformViewTouch(
            int viewId,
            @NonNull Number downTime,
            @NonNull Number eventTime,
            int action,
            int pointerCount,
            @NonNull Object rawPointerPropertiesList,
            @NonNull Object rawPointerCoords,
            int metaState,
            int buttonState,
            float xPrecision,
            float yPrecision,
            int deviceId,
            int edgeFlags,
            int source,
            int flags,
            long motionEventId) {
        this.viewId = viewId;
        this.downTime = downTime;
        this.eventTime = eventTime;
        this.action = action;
        this.pointerCount = pointerCount;
        this.rawPointerPropertiesList = rawPointerPropertiesList;
        this.rawPointerCoords = rawPointerCoords;
        this.metaState = metaState;
        this.buttonState = buttonState;
        this.xPrecision = xPrecision;
        this.yPrecision = yPrecision;
        this.deviceId = deviceId;
        this.edgeFlags = edgeFlags;
        this.source = source;
        this.flags = flags;
        this.motionEventId = motionEventId;
    }
}