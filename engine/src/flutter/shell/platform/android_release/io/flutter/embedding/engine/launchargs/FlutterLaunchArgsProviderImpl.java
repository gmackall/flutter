package io.flutter.embedding.engine.launchargs;

import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class FlutterLaunchArgsProviderImpl implements FlutterLaunchArgsProvider {
  public static final FlutterLaunchArgsProviderImpl INSTANCE =
      new FlutterLaunchArgsProviderImpl();

  private FlutterLaunchArgsProviderImpl() {}

  @Override
  @NonNull
  public FlutterLaunchArgs getLaunchArgs(@Nullable Intent intent) {
    // Release builds do not support engine flag configuration from Intent.
    return new FlutterLaunchArgs();
  }
}
