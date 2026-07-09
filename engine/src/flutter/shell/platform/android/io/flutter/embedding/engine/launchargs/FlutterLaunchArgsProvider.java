package io.flutter.embedding.engine.launchargs;

import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public interface FlutterLaunchArgsProvider {
  @NonNull
  FlutterLaunchArgs getLaunchArgs(@Nullable Intent intent);
}
