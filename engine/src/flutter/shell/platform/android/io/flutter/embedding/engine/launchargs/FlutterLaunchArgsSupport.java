package io.flutter.embedding.engine.launchargs;

import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.flutter.embedding.engine.FlutterShellArgs;

public final class FlutterLaunchArgsSupport {
  private FlutterLaunchArgsSupport() {}

  @NonNull
  public static FlutterShellArgs toFlutterShellArgs(@Nullable Intent intent) {
    return new FlutterShellArgs(
        FlutterLaunchArgsProviderImpl.INSTANCE.getLaunchArgs(intent).toArray());
  }
}
