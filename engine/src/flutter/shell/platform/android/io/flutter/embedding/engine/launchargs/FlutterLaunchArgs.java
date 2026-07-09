package io.flutter.embedding.engine.launchargs;

import androidx.annotation.NonNull;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class FlutterLaunchArgs {
  @NonNull private final Set<String> args;

  public FlutterLaunchArgs() {
    this.args = new HashSet<>();
  }

  public FlutterLaunchArgs(@NonNull String[] args) {
    this.args = new HashSet<>(Arrays.asList(args));
  }

  public FlutterLaunchArgs(@NonNull List<String> args) {
    this.args = new HashSet<>(args);
  }

  public void add(@NonNull String arg) {
    args.add(arg);
  }

  public void remove(@NonNull String arg) {
    args.remove(arg);
  }

  @NonNull
  public String[] toArray() {
    return args.toArray(new String[0]);
  }

  public boolean isEmpty() {
    return args.isEmpty();
  }
}
