package io.flutter.embedding.engine.launchargs;

import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;

public final class FlutterLaunchArgsProviderImpl implements FlutterLaunchArgsProvider {
  public static final FlutterLaunchArgsProviderImpl INSTANCE =
      new FlutterLaunchArgsProviderImpl();

  private FlutterLaunchArgsProviderImpl() {}

  static final String ARG_KEY_TRACE_STARTUP = "trace-startup";
  static final String ARG_TRACE_STARTUP = "--trace-startup";

  static final String ARG_KEY_START_PAUSED = "start-paused";
  static final String ARG_START_PAUSED = "--start-paused";

  static final String ARG_KEY_DISABLE_SERVICE_AUTH_CODES = "disable-service-auth-codes";
  static final String ARG_DISABLE_SERVICE_AUTH_CODES = "--disable-service-auth-codes";

  static final String ARG_KEY_ENDLESS_TRACE_BUFFER = "endless-trace-buffer";
  static final String ARG_ENDLESS_TRACE_BUFFER = "--endless-trace-buffer";

  static final String ARG_KEY_USE_TEST_FONTS = "use-test-fonts";
  static final String ARG_USE_TEST_FONTS = "--use-test-fonts";

  static final String ARG_KEY_ENABLE_DART_PROFILING = "enable-dart-profiling";
  static final String ARG_ENABLE_DART_PROFILING = "--enable-dart-profiling";

  static final String ARG_KEY_PROFILE_STARTUP = "profile-startup";
  static final String ARG_PROFILE_STARTUP = "--profile-startup";

  static final String ARG_KEY_ENABLE_SOFTWARE_RENDERING = "enable-software-rendering";
  static final String ARG_ENABLE_SOFTWARE_RENDERING = "--enable-software-rendering";

  static final String ARG_KEY_SKIA_DETERMINISTIC_RENDERING = "skia-deterministic-rendering";
  static final String ARG_SKIA_DETERMINISTIC_RENDERING = "--skia-deterministic-rendering";

  static final String ARG_KEY_TRACE_SKIA = "trace-skia";
  static final String ARG_TRACE_SKIA = "--trace-skia";

  static final String ARG_KEY_TRACE_SKIA_ALLOWLIST = "trace-skia-allowlist";
  static final String ARG_TRACE_SKIA_ALLOWLIST = "--trace-skia-allowlist=";

  static final String ARG_KEY_TRACE_SYSTRACE = "trace-systrace";
  static final String ARG_TRACE_SYSTRACE = "--trace-systrace";

  static final String ARG_KEY_TRACE_TO_FILE = "trace-to-file";
  static final String ARG_TRACE_TO_FILE = "--trace-to-file";

  static final String ARG_KEY_PROFILE_MICROTASKS = "profile-microtasks";
  static final String ARG_PROFILE_MICROTASKS = "--profile-microtasks";

  static final String ARG_KEY_TOGGLE_IMPELLER = "enable-impeller";
  static final String ARG_ENABLE_IMPELLER = "--enable-impeller=true";
  static final String ARG_DISABLE_IMPELLER = "--enable-impeller=false";

  static final String ARG_KEY_ENABLE_FLUTTER_GPU = "enable-flutter-gpu";
  static final String ARG_ENABLE_FLUTTER_GPU = "--enable-flutter-gpu";

  static final String ARG_KEY_ENABLE_VULKAN_VALIDATION = "enable-vulkan-validation";
  static final String ARG_ENABLE_VULKAN_VALIDATION = "--enable-vulkan-validation";

  static final String ARG_KEY_ENABLE_HCPP_AND_SURFACE_CONTROL =
      "enable-hcpp-and-surface-control";
  static final String ARG_ENABLE_HCPP_AND_SURFACE_CONTROL =
      "--enable-hcpp-and-surface-control=true";
  static final String ARG_DISABLE_HCPP_AND_SURFACE_CONTROL =
      "--enable-hcpp-and-surface-control=false";

  static final String ARG_KEY_DUMP_SHADER_SKP_ON_SHADER_COMPILATION =
      "dump-skp-on-shader-compilation";
  static final String ARG_DUMP_SHADER_SKP_ON_SHADER_COMPILATION =
      "--dump-skp-on-shader-compilation";

  static final String ARG_KEY_CACHE_SKSL = "cache-sksl";
  static final String ARG_CACHE_SKSL = "--cache-sksl";

  static final String ARG_KEY_PURGE_PERSISTENT_CACHE = "purge-persistent-cache";
  static final String ARG_PURGE_PERSISTENT_CACHE = "--purge-persistent-cache";

  static final String ARG_KEY_VERBOSE_LOGGING = "verbose-logging";
  static final String ARG_VERBOSE_LOGGING = "--verbose-logging";

  static final String ARG_KEY_VM_SERVICE_PORT = "vm-service-port";
  static final String ARG_VM_SERVICE_PORT = "--vm-service-port=";

  static final String ARG_KEY_DART_FLAGS = "dart-flags";
  static final String ARG_DART_FLAGS = "--dart-flags";

  static final String ARG_KEY_TEST_FLAG = "test-flag";
  static final String ARG_TEST_FLAG = "--test-flag";

  @Override
  @NonNull
  public FlutterLaunchArgs getLaunchArgs(@Nullable Intent intent) {
    if (intent == null) {
      return new FlutterLaunchArgs();
    }

    final ArrayList<String> args = new ArrayList<>();

    if (intent.getBooleanExtra(ARG_KEY_TRACE_STARTUP, false)) {
      args.add(ARG_TRACE_STARTUP);
    }
    if (intent.getBooleanExtra(ARG_KEY_START_PAUSED, false)) {
      args.add(ARG_START_PAUSED);
    }
    int vmServicePort = intent.getIntExtra(ARG_KEY_VM_SERVICE_PORT, 0);
    if (vmServicePort > 0) {
      args.add(ARG_VM_SERVICE_PORT + vmServicePort);
    }
    if (intent.getBooleanExtra(ARG_KEY_DISABLE_SERVICE_AUTH_CODES, false)) {
      args.add(ARG_DISABLE_SERVICE_AUTH_CODES);
    }
    if (intent.getBooleanExtra(ARG_KEY_ENDLESS_TRACE_BUFFER, false)) {
      args.add(ARG_ENDLESS_TRACE_BUFFER);
    }
    if (intent.getBooleanExtra(ARG_KEY_USE_TEST_FONTS, false)) {
      args.add(ARG_USE_TEST_FONTS);
    }
    if (intent.getBooleanExtra(ARG_KEY_ENABLE_DART_PROFILING, false)) {
      args.add(ARG_ENABLE_DART_PROFILING);
    }
    if (intent.getBooleanExtra(ARG_KEY_PROFILE_STARTUP, false)) {
      args.add(ARG_PROFILE_STARTUP);
    }
    if (intent.getBooleanExtra(ARG_KEY_ENABLE_SOFTWARE_RENDERING, false)) {
      args.add(ARG_ENABLE_SOFTWARE_RENDERING);
    }
    if (intent.getBooleanExtra(ARG_KEY_SKIA_DETERMINISTIC_RENDERING, false)) {
      args.add(ARG_SKIA_DETERMINISTIC_RENDERING);
    }
    if (intent.getBooleanExtra(ARG_KEY_TRACE_SKIA, false)) {
      args.add(ARG_TRACE_SKIA);
    }
    String traceSkiaAllowlist = intent.getStringExtra(ARG_KEY_TRACE_SKIA_ALLOWLIST);
    if (traceSkiaAllowlist != null) {
      args.add(ARG_TRACE_SKIA_ALLOWLIST + traceSkiaAllowlist);
    }
    if (intent.getBooleanExtra(ARG_KEY_TRACE_SYSTRACE, false)) {
      args.add(ARG_TRACE_SYSTRACE);
    }
    if (intent.hasExtra(ARG_KEY_TRACE_TO_FILE)) {
      args.add(ARG_TRACE_TO_FILE + "=" + intent.getStringExtra(ARG_KEY_TRACE_TO_FILE));
    }
    if (intent.hasExtra(ARG_KEY_PROFILE_MICROTASKS)) {
      args.add(ARG_PROFILE_MICROTASKS);
    }
    if (intent.hasExtra(ARG_KEY_TOGGLE_IMPELLER)) {
      if (intent.getBooleanExtra(ARG_KEY_TOGGLE_IMPELLER, false)) {
        args.add(ARG_ENABLE_IMPELLER);
      } else {
        args.add(ARG_DISABLE_IMPELLER);
      }
    }
    if (intent.getBooleanExtra(ARG_KEY_ENABLE_FLUTTER_GPU, false)) {
      args.add(ARG_ENABLE_FLUTTER_GPU);
    }
    if (intent.getBooleanExtra(ARG_KEY_ENABLE_VULKAN_VALIDATION, false)) {
      args.add(ARG_ENABLE_VULKAN_VALIDATION);
    }
    if (intent.hasExtra(ARG_KEY_ENABLE_HCPP_AND_SURFACE_CONTROL)) {
      if (intent.getBooleanExtra(ARG_KEY_ENABLE_HCPP_AND_SURFACE_CONTROL, false)) {
        args.add(ARG_ENABLE_HCPP_AND_SURFACE_CONTROL);
      } else {
        args.add(ARG_DISABLE_HCPP_AND_SURFACE_CONTROL);
      }
    }

    if (intent.getBooleanExtra(ARG_KEY_DUMP_SHADER_SKP_ON_SHADER_COMPILATION, false)) {
      args.add(ARG_DUMP_SHADER_SKP_ON_SHADER_COMPILATION);
    }
    if (intent.getBooleanExtra(ARG_KEY_CACHE_SKSL, false)) {
      args.add(ARG_CACHE_SKSL);
    }
    if (intent.getBooleanExtra(ARG_KEY_PURGE_PERSISTENT_CACHE, false)) {
      args.add(ARG_PURGE_PERSISTENT_CACHE);
    }
    if (intent.getBooleanExtra(ARG_KEY_VERBOSE_LOGGING, false)) {
      args.add(ARG_VERBOSE_LOGGING);
    }
    if (intent.getBooleanExtra(ARG_KEY_TEST_FLAG, false)) {
      args.add(ARG_TEST_FLAG);
    }

    if (intent.hasExtra(ARG_KEY_DART_FLAGS)) {
      args.add(ARG_DART_FLAGS + "=" + intent.getStringExtra(ARG_KEY_DART_FLAGS));
    }

    return new FlutterLaunchArgs(args);
  }
}
