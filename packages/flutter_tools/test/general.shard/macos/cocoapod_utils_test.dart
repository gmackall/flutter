// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:file/file.dart';
import 'package:file/memory.dart';
import 'package:file_testing/file_testing.dart';
import 'package:flutter_tools/src/base/logger.dart';
import 'package:flutter_tools/src/build_info.dart';
import 'package:flutter_tools/src/dart/pub.dart';
import 'package:flutter_tools/src/flutter_manifest.dart';
import 'package:flutter_tools/src/macos/cocoapod_utils.dart';
import 'package:flutter_tools/src/macos/cocoapods.dart';
import 'package:flutter_tools/src/project.dart';
import 'package:test/fake.dart';
import 'package:yaml/yaml.dart';

import '../../src/common.dart';
import '../../src/context.dart';
import '../../src/package_config.dart';
import '../../src/throwing_pub.dart';

void main() {
  group('processPodsIfNeeded', () {
    late MemoryFileSystem fs;
    late FakeCocoaPods cocoaPods;
    late BufferLogger logger;

    // Adds basic properties to the flutterProject and its subprojects.
    void setUpProject(
      FakeFlutterProject flutterProject,
      MemoryFileSystem fileSystem, {
      List<String> pluginNames = const <String>[],
    }) {
      flutterProject
        ..manifest = FakeFlutterManifest()
        ..directory = fileSystem.systemTempDirectory.childDirectory('app')
        ..flutterPluginsDependenciesFile = flutterProject.directory.childFile(
          '.flutter-plugins-dependencies',
        )
        ..ios = FakeIosProject(fileSystem: fileSystem, parent: flutterProject)
        ..macos = FakeMacOSProject(fileSystem: fileSystem, parent: flutterProject)
        ..android = FakeAndroidProject()
        ..web = FakeWebProject()
        ..windows = FakeWindowsProject()
        ..linux = FakeLinuxProject()
        ..packageConfig = flutterProject.directory
            .childDirectory('.dart_tool')
            .childFile('package_config.json');

      const pluginYamlTemplate = '''
      flutter:
        plugin:
          platforms:
            ios:
              pluginClass: PLUGIN_CLASS
            macos:
              pluginClass: PLUGIN_CLASS
      ''';

      final Directory fakePubCache = fileSystem.systemTempDirectory.childDirectory('cache');

      writePackageConfigFiles(
        directory: flutterProject.directory,
        mainLibName: 'my_app',
        packages: <String, String>{
          for (final String plugin in pluginNames)
            plugin: fakePubCache.childDirectory(plugin).uri.toString(),
        },
      );

      for (final name in pluginNames) {
        flutterProject.manifest.dependencies.add(name);
        final Directory pluginDirectory = fakePubCache.childDirectory(name);
        pluginDirectory.childFile('pubspec.yaml')
          ..createSync(recursive: true)
          ..writeAsStringSync(pluginYamlTemplate.replaceAll('PLUGIN_CLASS', name));
      }
    }

    setUp(() async {
      fs = MemoryFileSystem.test();
      cocoaPods = FakeCocoaPods();
      logger = BufferLogger.test();
    });

    group('for iOS', () {
      group('using CocoaPods only', () {
        testUsingContext(
          'processes when there are plugins',
          () async {
            final flutterProject = FakeFlutterProject();
            setUpProject(flutterProject, fs, pluginNames: <String>['plugin_one', 'plugin_two']);

            await processPodsIfNeeded(
              flutterProject.ios,
              fs.currentDirectory.childDirectory('build').path,
              BuildMode.debug,
            );
            expect(cocoaPods.processedPods, isTrue);
          },
          overrides: <Type, Generator>{
            FileSystem: () => fs,
            ProcessManager: FakeProcessManager.empty,
            Pub: ThrowingPub.new,
            CocoaPods: () => cocoaPods,
          },
        );

        testUsingContext(
          'processes when no plugins but the project is a module and podfile exists',
          () async {
            final flutterProject = FakeFlutterProject();
            setUpProject(flutterProject, fs);
            flutterProject.isModule = true;
            flutterProject.ios.podfile.createSync(recursive: true);

            await processPodsIfNeeded(
              flutterProject.ios,
              fs.currentDirectory.childDirectory('build').path,
              BuildMode.debug,
            );
            expect(cocoaPods.processedPods, isTrue);
          },
          overrides: <Type, Generator>{
            FileSystem: () => fs,
            ProcessManager: FakeProcessManager.empty,
            Pub: ThrowingPub.new,
            CocoaPods: () => cocoaPods,
          },
        );

        testUsingContext(
          "skips when no plugins and the project is a module but podfile doesn't exist",
          () async {
            final flutterProject = FakeFlutterProject();
            setUpProject(flutterProject, fs);
            flutterProject.isModule = true;

            await processPodsIfNeeded(
              flutterProject.ios,
              fs.currentDirectory.childDirectory('build').path,
              BuildMode.debug,
            );
            expect(cocoaPods.processedPods, isFalse);
          },
          overrides: <Type, Generator>{
            FileSystem: () => fs,
            ProcessManager: FakeProcessManager.empty,
            Pub: ThrowingPub.new,
            CocoaPods: () => cocoaPods,
          },
        );

        testUsingContext(
          'skips when no plugins and project is not a module',
          () async {
            final flutterProject = FakeFlutterProject();
            setUpProject(flutterProject, fs);

            await processPodsIfNeeded(
              flutterProject.ios,
              fs.currentDirectory.childDirectory('build').path,
              BuildMode.debug,
            );
            expect(cocoaPods.processedPods, isFalse);
          },
          overrides: <Type, Generator>{
            FileSystem: () => fs,
            ProcessManager: FakeProcessManager.empty,
            Pub: ThrowingPub.new,
            CocoaPods: () => cocoaPods,
          },
        );
      });

      group('using Swift Package Manager', () {
        testUsingContext(
          'processes if podfile exists',
          () async {
            final flutterProject = FakeFlutterProject();
            setUpProject(flutterProject, fs, pluginNames: <String>['plugin_one', 'plugin_two']);
            flutterProject.ios.usesSwiftPackageManager = true;
            flutterProject.ios.podfile.createSync(recursive: true);

            await processPodsIfNeeded(
              flutterProject.ios,
              fs.currentDirectory.childDirectory('build').path,
              BuildMode.debug,
            );
            expect(cocoaPods.processedPods, isTrue);
          },
          overrides: <Type, Generator>{
            FileSystem: () => fs,
            ProcessManager: FakeProcessManager.empty,
            Pub: ThrowingPub.new,
            CocoaPods: () => cocoaPods,
          },
        );

        testUsingContext(
          'skip if podfile does not exists',
          () async {
            final flutterProject = FakeFlutterProject();
            setUpProject(flutterProject, fs, pluginNames: <String>['plugin_one', 'plugin_two']);
            flutterProject.ios.usesSwiftPackageManager = true;

            await processPodsIfNeeded(
              flutterProject.ios,
              fs.currentDirectory.childDirectory('build').path,
              BuildMode.debug,
            );
            expect(cocoaPods.processedPods, isFalse);
          },
          overrides: <Type, Generator>{
            FileSystem: () => fs,
            ProcessManager: FakeProcessManager.empty,
            CocoaPods: () => cocoaPods,
          },
        );

        testUsingContext(
          'process if podfile does not exists but forceCocoaPodsOnly is true',
          () async {
            final flutterProject = FakeFlutterProject();
            setUpProject(flutterProject, fs, pluginNames: <String>['plugin_one', 'plugin_two']);
            flutterProject.ios.usesSwiftPackageManager = true;
            final File generatedManifestFile = flutterProject.ios.flutterPluginSwiftPackageManifest;
            generatedManifestFile.createSync(recursive: true);

            await processPodsIfNeeded(
              flutterProject.ios,
              fs.currentDirectory.childDirectory('build').path,
              BuildMode.debug,
              forceCocoaPodsOnly: true,
            );
            expect(cocoaPods.processedPods, isTrue);
            expect(cocoaPods.podfileSetup, isTrue);
            expect(
              logger.warningText,
              'Swift Package Manager does not yet support this command. '
              'CocoaPods will be used instead.\n',
            );
            expect(generatedManifestFile, exists);
            const emptyDependencies = 'dependencies: [\n        \n    ],\n';
            expect(generatedManifestFile.readAsStringSync(), contains(emptyDependencies));
          },
          overrides: <Type, Generator>{
            FileSystem: () => fs,
            ProcessManager: FakeProcessManager.empty,
            Pub: ThrowingPub.new,
            CocoaPods: () => cocoaPods,
            Logger: () => logger,
          },
        );
      });
    });

    group('for macOS', () {
      group('using CocoaPods only', () {
        testUsingContext(
          'processes when there are plugins',
          () async {
            final flutterProject = FakeFlutterProject();
            setUpProject(flutterProject, fs, pluginNames: <String>['plugin_one', 'plugin_two']);

            await processPodsIfNeeded(
              flutterProject.macos,
              fs.currentDirectory.childDirectory('build').path,
              BuildMode.debug,
            );
            expect(cocoaPods.processedPods, isTrue);
          },
          overrides: <Type, Generator>{
            FileSystem: () => fs,
            ProcessManager: FakeProcessManager.empty,
            Pub: ThrowingPub.new,
            CocoaPods: () => cocoaPods,
          },
        );

        testUsingContext(
          'processes when no plugins but the project is a module and podfile exists',
          () async {
            final flutterProject = FakeFlutterProject();
            setUpProject(flutterProject, fs);
            flutterProject.isModule = true;
            flutterProject.macos.podfile.createSync(recursive: true);

            await processPodsIfNeeded(
              flutterProject.macos,
              fs.currentDirectory.childDirectory('build').path,
              BuildMode.debug,
            );
            expect(cocoaPods.processedPods, isTrue);
          },
          overrides: <Type, Generator>{
            FileSystem: () => fs,
            ProcessManager: FakeProcessManager.empty,
            Pub: ThrowingPub.new,
            CocoaPods: () => cocoaPods,
          },
        );

        testUsingContext(
          "skips when no plugins and the project is a module but podfile doesn't exist",
          () async {
            final flutterProject = FakeFlutterProject();
            setUpProject(flutterProject, fs);
            flutterProject.isModule = true;

            await processPodsIfNeeded(
              flutterProject.macos,
              fs.currentDirectory.childDirectory('build').path,
              BuildMode.debug,
            );
            expect(cocoaPods.processedPods, isFalse);
          },
          overrides: <Type, Generator>{
            FileSystem: () => fs,
            ProcessManager: FakeProcessManager.empty,
            Pub: ThrowingPub.new,
            CocoaPods: () => cocoaPods,
          },
        );

        testUsingContext(
          'skips when no plugins and project is not a module',
          () async {
            final flutterProject = FakeFlutterProject();
            setUpProject(flutterProject, fs);

            await processPodsIfNeeded(
              flutterProject.macos,
              fs.currentDirectory.childDirectory('build').path,
              BuildMode.debug,
            );
            expect(cocoaPods.processedPods, isFalse);
          },
          overrides: <Type, Generator>{
            FileSystem: () => fs,
            ProcessManager: FakeProcessManager.empty,
            Pub: ThrowingPub.new,
            CocoaPods: () => cocoaPods,
          },
        );
      });

      group('using Swift Package Manager', () {
        testUsingContext(
          'processes if podfile exists',
          () async {
            final flutterProject = FakeFlutterProject();
            setUpProject(flutterProject, fs, pluginNames: <String>['plugin_one', 'plugin_two']);
            flutterProject.macos.usesSwiftPackageManager = true;
            flutterProject.macos.podfile.createSync(recursive: true);

            await processPodsIfNeeded(
              flutterProject.macos,
              fs.currentDirectory.childDirectory('build').path,
              BuildMode.debug,
            );
            expect(cocoaPods.processedPods, isTrue);
          },
          overrides: <Type, Generator>{
            FileSystem: () => fs,
            ProcessManager: FakeProcessManager.empty,
            Pub: ThrowingPub.new,
            CocoaPods: () => cocoaPods,
          },
        );

        testUsingContext(
          'skip if podfile does not exists',
          () async {
            final flutterProject = FakeFlutterProject();
            setUpProject(flutterProject, fs, pluginNames: <String>['plugin_one', 'plugin_two']);
            flutterProject.macos.usesSwiftPackageManager = true;

            await processPodsIfNeeded(
              flutterProject.macos,
              fs.currentDirectory.childDirectory('build').path,
              BuildMode.debug,
            );
            expect(cocoaPods.processedPods, isFalse);
          },
          overrides: <Type, Generator>{
            FileSystem: () => fs,
            ProcessManager: FakeProcessManager.empty,
            CocoaPods: () => cocoaPods,
          },
        );

        testUsingContext(
          'process if podfile does not exists but forceCocoaPodsOnly is true',
          () async {
            final flutterProject = FakeFlutterProject();
            setUpProject(flutterProject, fs, pluginNames: <String>['plugin_one', 'plugin_two']);
            flutterProject.macos.usesSwiftPackageManager = true;
            final File generatedManifestFile =
                flutterProject.macos.flutterPluginSwiftPackageManifest;
            generatedManifestFile.createSync(recursive: true);

            await processPodsIfNeeded(
              flutterProject.macos,
              fs.currentDirectory.childDirectory('build').path,
              BuildMode.debug,
              forceCocoaPodsOnly: true,
            );
            expect(cocoaPods.processedPods, isTrue);
            expect(cocoaPods.podfileSetup, isTrue);
            expect(
              logger.warningText,
              'Swift Package Manager does not yet support this command. '
              'CocoaPods will be used instead.\n',
            );

            expect(generatedManifestFile, exists);
            const emptyDependencies = 'dependencies: [\n        \n    ],\n';
            expect(generatedManifestFile.readAsStringSync(), contains(emptyDependencies));
          },
          overrides: <Type, Generator>{
            FileSystem: () => fs,
            ProcessManager: FakeProcessManager.empty,
            Pub: ThrowingPub.new,
            CocoaPods: () => cocoaPods,
            Logger: () => logger,
          },
        );
      });
    });
  });
}

class FakeFlutterManifest extends Fake implements FlutterManifest {
  @override
  late var dependencies = <String>{};
  @override
  String get appName => 'my_app';
  @override
  YamlMap toYaml() => YamlMap.wrap(<String, String>{});
}

class FakeFlutterProject extends Fake implements FlutterProject {
  @override
  var isModule = false;

  @override
  late FlutterManifest manifest;

  @override
  late Directory directory;

  @override
  late File flutterPluginsDependenciesFile;

  @override
  late FakeIosProject ios;

  @override
  late FakeMacOSProject macos;

  @override
  late AndroidProject android;

  @override
  late WebProject web;

  @override
  late LinuxProject linux;

  @override
  late WindowsProject windows;

  @override
  late File packageConfig;
}

class FakeMacOSProject extends Fake implements MacOSProject {
  FakeMacOSProject({required MemoryFileSystem fileSystem, required this.parent})
    : hostAppRoot = fileSystem.directory('app_name').childDirectory('ios');

  @override
  var pluginConfigKey = 'macos';

  @override
  final FlutterProject parent;

  @override
  Directory hostAppRoot;

  var exists = true;

  @override
  bool existsSync() => exists;

  @override
  File get podfile => hostAppRoot.childFile('Podfile');

  @override
  File get xcodeProjectInfoFile =>
      hostAppRoot.childDirectory('Runner.xcodeproj').childFile('project.pbxproj');

  @override
  Directory get flutterSwiftPackagesDirectory =>
      hostAppRoot.childDirectory('Flutter').childDirectory('ephemeral').childDirectory('Packages');

  @override
  Directory get relativeSwiftPackagesDirectory =>
      flutterSwiftPackagesDirectory.childDirectory('.packages');

  @override
  Directory get flutterPluginSwiftPackageDirectory =>
      flutterSwiftPackagesDirectory.childDirectory('FlutterGeneratedPluginSwiftPackage');

  @override
  File get flutterPluginSwiftPackageManifest =>
      flutterPluginSwiftPackageDirectory.childFile('Package.swift');

  @override
  var usesSwiftPackageManager = false;

  @override
  bool get flutterPluginSwiftPackageInProjectSettings => usesSwiftPackageManager;
}

class FakeIosProject extends Fake implements IosProject {
  FakeIosProject({required MemoryFileSystem fileSystem, required this.parent})
    : hostAppRoot = fileSystem.directory('app_name').childDirectory('ios');

  @override
  var pluginConfigKey = 'ios';

  @override
  final FlutterProject parent;

  @override
  Directory hostAppRoot;

  @override
  var exists = true;

  @override
  bool existsSync() => exists;

  @override
  File get podfile => hostAppRoot.childFile('Podfile');

  @override
  File get xcodeProjectInfoFile =>
      hostAppRoot.childDirectory('Runner.xcodeproj').childFile('project.pbxproj');

  @override
  Directory get flutterSwiftPackagesDirectory =>
      hostAppRoot.childDirectory('Flutter').childDirectory('ephemeral').childDirectory('Packages');

  @override
  Directory get relativeSwiftPackagesDirectory =>
      flutterSwiftPackagesDirectory.childDirectory('.packages');

  @override
  Directory get flutterPluginSwiftPackageDirectory =>
      flutterSwiftPackagesDirectory.childDirectory('FlutterGeneratedPluginSwiftPackage');

  @override
  File get flutterPluginSwiftPackageManifest =>
      flutterPluginSwiftPackageDirectory.childFile('Package.swift');

  @override
  var usesSwiftPackageManager = false;

  @override
  bool get flutterPluginSwiftPackageInProjectSettings => usesSwiftPackageManager;
}

class FakeAndroidProject extends Fake implements AndroidProject {
  @override
  var pluginConfigKey = 'android';

  @override
  bool existsSync() => false;
}

class FakeWebProject extends Fake implements WebProject {
  @override
  var pluginConfigKey = 'web';

  @override
  bool existsSync() => false;
}

class FakeWindowsProject extends Fake implements WindowsProject {
  @override
  var pluginConfigKey = 'windows';

  @override
  bool existsSync() => false;
}

class FakeLinuxProject extends Fake implements LinuxProject {
  @override
  var pluginConfigKey = 'linux';

  @override
  bool existsSync() => false;
}

class FakeCocoaPods extends Fake implements CocoaPods {
  var podfileSetup = false;
  var processedPods = false;

  @override
  Future<bool> processPods({
    required XcodeBasedProject xcodeProject,
    required BuildMode buildMode,
    bool dependenciesChanged = true,
  }) async {
    processedPods = true;
    return true;
  }

  @override
  Future<void> setupPodfile(XcodeBasedProject xcodeProject) async {
    podfileSetup = true;
  }

  @override
  void invalidatePodInstallOutput(XcodeBasedProject xcodeProject) {}
}
