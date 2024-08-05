package com.flutter.gradle;

import com.android.build.FilterData;
import com.android.build.OutputFile;
import com.android.build.VariantOutput;
import com.android.build.api.dsl.ApplicationExtension;
import com.android.build.api.dsl.BuildType;
import com.android.build.api.variant.ApplicationVariant;
import com.android.build.gradle.AppExtension;
import com.android.build.gradle.BaseExtension;
import com.android.build.gradle.api.ApkVariant;
import com.android.build.gradle.api.ApkVariantOutput;
import com.android.build.gradle.api.BaseVariant;
import com.android.build.gradle.api.BaseVariantOutput;
import com.android.build.gradle.internal.dsl.AbiSplitOptions;
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension;
import com.android.build.gradle.tasks.ProcessAndroidResources;

import org.apache.tools.ant.taskdefs.condition.Os;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.JavaVersion;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.Transformer;
import org.gradle.api.UnknownDomainObjectException;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.internal.impldep.com.fasterxml.jackson.databind.ObjectMapper;
import org.gradle.internal.impldep.com.fasterxml.jackson.databind.SerializationFeature;
import org.gradle.internal.os.OperatingSystem;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import groovy.lang.Closure;
import groovy.lang.Tuple2;

public class FlutterPlugin implements Plugin<Project> {
    private static final String DEFAULT_MAVEN_HOST = "https://storage.googleapis.com";

    /** The platforms that can be passed to the `--Ptarget-platform` flag. */
    private static final String PLATFORM_ARM32  = "android-arm";
    private static final String PLATFORM_ARM64  = "android-arm64";
    private static final String PLATFORM_X86    = "android-x86";
    private static final String PLATFORM_X86_64 = "android-x64";

    /** The ABI architectures supported by Flutter. */
    private static final String ARCH_ARM32      = "armeabi-v7a";
    private static final String ARCH_ARM64      = "arm64-v8a";
    private static final String ARCH_X86        = "x86";
    private static final String ARCH_X86_64     = "x86_64";

    private static final String INTERMEDIATES_DIR = "intermediates";

    /** Maps platforms to ABI architectures. */
    public static final Map<String, String> PLATFORM_ARCH_MAP = Map.of(
            PLATFORM_ARM32, ARCH_ARM32,
            PLATFORM_ARM64, ARCH_ARM64,
            PLATFORM_X86, ARCH_X86,
            PLATFORM_X86_64, ARCH_X86_64
    );

    /**
     * The version code that gives each ABI a value.
     * For each APK variant, use the following versions to override the version of the Universal APK.
     * Otherwise, the Play Store will complain that the APK variants have the same version.
     */
    public static final Map<String, Integer> ABI_VERSION = Map.of(
            ARCH_ARM32, 1,
            ARCH_ARM64, 2,
            ARCH_X86, 3,
            ARCH_X86_64, 4
    );

    /** When split is enabled, multiple APKs are generated per each ABI. */
    private static final List DEFAULT_PLATFORMS = List.of(
            PLATFORM_ARM32,
            PLATFORM_ARM64,
            PLATFORM_X86_64
    );

    private final static String propLocalEngineRepo = "local-engine-repo";
    private final static String propProcessResourcesProvider = "processResourcesProvider";

    /**
     * The name prefix for flutter builds. This is used to identify gradle tasks
     * where we expect the flutter tool to provide any error output, and skip the
     * standard Gradle error output in the FlutterEventLogger. If you change this,
     * be sure to change any instances of this string in symbols in the code below
     * to match.
     */
    static final String FLUTTER_BUILD_PREFIX = "flutterBuild";

    private Project project;
    private File flutterRoot;
    private File flutterExecutable;
    private String localEngine;
    private String localEngineHost;
    private String localEngineSrcPath;
    private Properties localProperties;
    private String engineVersion;
    private String engineRealm;
    private List<Map<String, Object>> pluginList;
    private List<Map<String, Object>> pluginDependencies;

    /**
     * Flutter Docs Website URLs for help messages.
     */
    private final String kWebsiteDeploymentAndroidBuildConfig = "https://flutter.dev/to/review-gradle-config";

    @Override
    public void apply(Project project) {
        this.project = project;

        Project rootProject = project.getRootProject();
        if (isFlutterAppProject()) {
            rootProject.getTasks().register("generateLockfiles", task -> {
                for (Project subproject : rootProject.getSubprojects()) {
                    String gradlew = OperatingSystem.current().isWindows()
                            ? rootProject.getProjectDir() + "/gradlew.bat"
                            : rootProject.getProjectDir() + "/gradlew";
                    rootProject.exec(execSpec -> {
                        execSpec.setWorkingDir(rootProject.getProjectDir());
                        execSpec.setExecutable(gradlew);
                        execSpec.args(":" + subproject.getName() + ":dependencies", "--write-locks");
                    });
                }
            });
        }

        // TODO(gmackall): Is this what the groovy code is doing?
        String flutterRootPath = resolveProperty("flutter.sdk", System.getenv().get("FLUTTER_ROOT"));
        if (flutterRootPath == null) {
            throw new GradleException("Flutter SDK not found. Define location with flutter.sdk in the local.properties file or with a FLUTTER_ROOT environment variable.");
        }
        flutterRoot = project.file(flutterRootPath);
        if (!flutterRoot.isDirectory()) {
            throw new GradleException("flutter.sdk must point to the Flutter SDK directory");
        }

        if (useLocalEngine()) {
            engineVersion = "+"; // Match any version if using local engine
        } else {
            Path engineVersionFilePath = Paths.get(flutterRoot.getAbsolutePath(), "bin", "internal", "engine.version");
            try {
                String versionText = Files.readString(engineVersionFilePath).trim();
                engineVersion = "1.0.0-" + versionText;
            } catch (IOException e) {
                throw new GradleException("Error reading engine.version file", e);
            }
        }

        Path engineRealmFilePath = Paths.get(flutterRoot.getAbsolutePath(), "bin", "internal", "engine.realm");
        try {
            String realmText = Files.readString(engineRealmFilePath).trim();
            engineRealm = realmText + "/";
        } catch (IOException e) {
            throw new GradleException("Error reading engine.realm file", e);
        }

        // Configure the Maven repository.
        String hostedRepository = System.getenv("FLUTTER_STORAGE_BASE_URL");
        if (hostedRepository == null) {
            hostedRepository = DEFAULT_MAVEN_HOST;
        }

        String repository = useLocalEngine()
                ? (String) project.property(propLocalEngineRepo)
                : hostedRepository + "/" + engineRealm + "download.flutter.io";

        rootProject.allprojects(
                project1 -> project1.getRepositories().maven(
                        mavenRepo -> {
                            assert repository != null;
                            mavenRepo.setUrl(repository);
                        }
                )
        );

        // Load shared gradle functions
        // TODO(gmackall): Can Gemini be trusted? Find out soon.
        Map<String, String> options = new HashMap<>();
        options.put("from", Paths.get(flutterRoot.getAbsolutePath(), "packages", "flutter_tools", "gradle", "src", "main", "groovy", "native_plugin_loader.groovy").toString());
        project.apply(options);

        FlutterExtension extension = project.getExtensions().create("flutter", FlutterExtension.class);
        File localPropertiesFile = rootProject.file("local.properties");
        Properties localProperties = readPropertiesIfExist(localPropertiesFile);

        Object flutterVersionCode = localProperties.getProperty("flutter.versionCode");
        if (flutterVersionCode == null) {
            flutterVersionCode = "1";
        }
        extension.flutterVersionCode = (String) flutterVersionCode;

        Object flutterVersionName = localProperties.getProperty("flutter.versionName");
        if (flutterVersionName == null) {
            flutterVersionName = "1.0";
        }
        extension.flutterVersionName = (String) flutterVersionName;

        this.addFlutterTasks(project);

        // By default, assembling APKs generates fat APKs if multiple platforms are passed.
        // Configuring split per ABI allows to generate separate APKs for each abi.
        // This is a noop when building a bundle.
        if (shouldSplitPerAbi()) {
            project.getExtensions().configure("android", new Action<BaseExtension>() {
                @Override
                public void execute(BaseExtension androidExtension) {
                    AbiSplitOptions abiSplitOptions = androidExtension.getSplits().getAbi();
                    abiSplitOptions.setEnable(true);
                    abiSplitOptions.reset();
                    abiSplitOptions.setUniversalApk(false);
                }
            });
        }

        final String propDeferredComponentNames = "deferred-component-names";
        if (project.hasProperty(propDeferredComponentNames)) {
            Set<String> componentNames = Arrays.stream(((String) Objects.requireNonNull(project.property(propDeferredComponentNames)))
                    .split(","))
                    .map(it -> ":" + it)
                    .collect(Collectors.toSet());

            // TODO(gmackall): this class is marked as internal. There isn't a way to do what we
            //                 doing in the Groovy version of the FGP without accessing this
            //                 internal class, but reconsider if we need to do this.
            BaseAppModuleExtension androidExtension = project.getExtensions().getByType(BaseAppModuleExtension.class);
            androidExtension.setDynamicFeatures(componentNames);
        }


        List<String> targetPlatforms = getTargetPlatforms();
        for (String targetArch : targetPlatforms) {
            String abiValue = PLATFORM_ARCH_MAP.get(targetArch);
            if (shouldSplitPerAbi()) {
                // Access the Android extension and configure ABI splits
                BaseExtension androidExtension = (BaseExtension) project.getExtensions().getByName("android");
                androidExtension.getSplits().getAbi().include(abiValue);
            }
        }

        String flutterExecutableName = Os.isFamily(Os.FAMILY_WINDOWS) ? "flutter.bat" : "flutter";
        flutterExecutable = Paths.get(flutterRoot.getAbsolutePath(), "bin", flutterExecutableName).toFile();

        // Validate that the provided Gradle, Java, AGP, and KGP versions are all within our
        // supported range.
        // TODO(gmackall) Dependency version checking is currently implemented as an additional
        // Gradle plugin because we can't import it from Groovy code. As part of the Groovy
        // -> Kotlin migration, we should remove this complexity and perform the checks inside
        // of the main Flutter Gradle Plugin.
        // See https://github.com/flutter/flutter/issues/121541#issuecomment-1920363687.
        final Boolean shouldSkipDependencyChecks = project.hasProperty("skipDependencyChecks") &&
                ((Boolean) project.property("skipDependencyChecks"));
        if (!shouldSkipDependencyChecks) {
            try {
                final String dependencyCheckerPluginPath = Paths.get(flutterRoot.getAbsolutePath(),
                        "packages", "flutter_tools", "gradle", "src", "main", "kotlin",
                        "dependency_version_checker.gradle.kts").toString();
                Map<String, String> dependencyCheckerOptions = new HashMap<>();
                options.put("from", dependencyCheckerPluginPath);
                project.apply(dependencyCheckerOptions);
            } catch (Exception e) {
                if (!((Boolean) project.property("usesUnsupportedDependencyVersions"))) {
                    // Possible bug in dependency checking code - warn and do not block build.
                    project.getLogger().error("Warning: Flutter was unable to detect project Gradle, Java, " +
                            "AGP, and KGP versions. Skipping dependency version checking. Error was: "
                            + e);
                }
                else {
                    // If usesUnsupportedDependencyVersions is set, the exception was thrown by us
                    // in the dependency version checker plugin so re-throw it here.
                    throw e;
                }
            }
        }

        // Use Kotlin DSL to handle baseApplicationName logic due to Groovy dynamic dispatch bug.
        // TODO(gmackall): the above comment makes no sense after conversion of the main Flutter Gradle plugin. Fix it.
        Map<String, String> baseApplicationNamePlugin = new HashMap<>();
        String baseApplicationNameScriptPath = Paths.get(flutterRoot.getAbsolutePath(), "packages", "flutter_tools", "gradle", "src", "main", "kotlin", "flutter.gradle.kts").toString();
        baseApplicationNamePlugin.put("from", baseApplicationNameScriptPath);
        project.apply(baseApplicationNamePlugin);

        // Access the Android extension (assuming it's of type BaseExtension, adjust if needed)
        BaseExtension androidExtension = (BaseExtension) project.getExtensions().getByName("android");

        Action<? super NamedDomainObjectContainer<com.android.build.gradle.internal.dsl.BuildType>> action = new Action<>() {
            @Override
            public void execute(NamedDomainObjectContainer<com.android.build.gradle.internal.dsl.BuildType> buildTypes) {
                // Add 'profile' build type
                BuildType profileBuildType = buildTypes.create("profile");
                profileBuildType.initWith(buildTypes.getByName("debug"));
                profileBuildType.setMatchingFallbacks(new ArrayList<>(Arrays.asList("debug", "release")));

                // Configure 'release' build type if resource shrinking is enabled
                if (shouldShrinkResources(project)) {
                    BuildType releaseBuildType = buildTypes.getByName("release");
                    releaseBuildType.setMinifyEnabled(true); // Enable code shrinking, obfuscation, and optimization

                    // Enable resource shrinking only for app projects
                    releaseBuildType.setShrinkResources(isBuiltAsApp(project));

                    // Set Proguard files (adjust paths if necessary)
                    String flutterProguardRulesPath = Paths.get(flutterRoot.getAbsolutePath(), "packages", "flutter_tools", "gradle", "flutter_proguard_rules.pro").toString();
                    BaseExtension androidExtension = (BaseExtension) project.getExtensions().getByName("android");
                    releaseBuildType.setProguardFiles(Arrays.asList(
                            androidExtension.getDefaultProguardFile("proguard-android.txt"),
                            project.file(flutterProguardRulesPath),
                            project.file("proguard-rules.pro")
                    ));
                }
            }
        };

        // Configure build types
        androidExtension.buildTypes(action);

        if (useLocalEngine()) {
            // Get local engine paths from project properties
            String engineOutPath = (String) project.property("local-engine-out");
            File engineOut = project.file(engineOutPath);

            // Validate engine out path
            if (!engineOut.isDirectory()) {
                throw new GradleException("local-engine-out must point to a local engine build");
            }
            localEngine = engineOut.getName();
            localEngineSrcPath = engineOut.getParentFile().getParent();

            // Get and validate engine host out path
            String engineHostOutPath = (String) project.property("local-engine-host-out");
            File engineHostOut = project.file(engineHostOutPath);
            if (!engineHostOut.isDirectory()) {
                throw new GradleException("local-engine-host-out must point to a local engine host build");
            }
            localEngineHost = engineHostOut.getName();
        }

        // Apply 'addFlutterDependencies' to all build types
        androidExtension.getBuildTypes().all((Action<BuildType>) this::addFlutterDependencies);

    }

    private static Boolean shouldShrinkResources(Project project) {
        final String propShrink = "shrink";
        if (project.hasProperty(propShrink)) {
            return (Boolean) project.property(propShrink);
        }
        return true;
    }

    // TODO(gmackall): is this a good implementation?
    private static String toCamelCase(List<String> parts) {
        if (parts.isEmpty()) {
            return "";
        }
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            stringBuilder.append(capitalizeWord(parts.get(i)));
        }
        return stringBuilder.toString();
    }

    // Capitalizes an individual word. Returns null for null input.
    private static String capitalizeWord(String word) {
        if (word == null) {
            return null;
        } else if (word.isEmpty() || word.length() == 1) {
            return word.toUpperCase();
        } else {
            return word.substring(0,1).toUpperCase() + word.substring(1).toLowerCase();
        }
    }

    private static Properties readPropertiesIfExist(File propertiesFile) {
        Properties properties = new Properties();
        if (propertiesFile.exists()) {
            try (BufferedReader reader = Files.newBufferedReader(propertiesFile.toPath(), StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException e) {
                throw new GradleException("Error reading properties file at " + propertiesFile.getAbsolutePath(), e);
            }
        }
        return properties;
    }

    private static Boolean isBuiltAsApp(Project project) {
        // Projects are built as applications when the they use the `com.android.application`
        // plugin.
        return project.getPlugins().hasPlugin("com.android.application");
    }

    private static void addApiDependencies(Project project, String variantName, Object dependency, Closure config) {
        String configuration;
        // `compile` dependencies are now `api` dependencies.
        if (project.getConfigurations().findByName("api") != null) {
            configuration = variantName + "Api";
        } else {
            configuration = variantName + "Compile";
        }
        //TODO(gmackall) We were passing Null to @Nonnull before, so keep doing it after converting
        //               from Groovy to Java. But this should def be fixed?
        project.getDependencies().add(configuration, dependency, config);
    }

    private static void addApiDependencies(Project project, String variantName, Object dependency) {
        addApiDependencies(project, variantName, dependency, null);
    }

    // Add a task that can be called on flutter projects that prints the Java version used in Gradle.
    //
    // Format of the output of this task can be used in debugging what version of Java Gradle is using.
    // Not recommended for use in time sensitive commands like `flutter run` or `flutter build` as
    // Gradle is slower than we want. Particularly in light of https://github.com/flutter/flutter/issues/119196.
    private static void addTaskForJavaVersion(Project project) {
        project.getTasks().register("javaVersion", task -> {
            task.setDescription("Print the current java version used by gradle. " +
                    "see: https://docs.gradle.org/current/javadoc/org/gradle/api/JavaVersion.html");
            task.doLast(task1 -> System.out.println(JavaVersion.current()));
        });
    }

    // Add a task that can be called on Flutter projects that prints the available build variants
    // in Gradle.
    //
    // This task prints variants in this format:
    //
    // BuildVariant: debug
    // BuildVariant: release
    // BuildVariant: profile
    //
    // Format of the output of this task is used by `AndroidProject.getBuildVariants`.
    private static void addTaskForPrintBuildVariants(Project project) {
        project.getTasks().register("printBuildVariants", task -> {
            task.setDescription("Prints out all build variants for this Android project");
            task.doLast(task1 -> {
                // Access the Android extension (assuming it's of type ApplicationExtension, as this method is guarded by isFlutterAppProject())
                AppExtension androidExtension = (AppExtension) project.getExtensions().getByName("android");

                androidExtension.getApplicationVariants().all(new Closure<Void>(project) {
                    @Override
                    public Void call(Object variant) {
                        System.out.println("BuildVariant: " + ((ApplicationVariant) variant).getName());
                        return null;
                    }
                });
            });
        });
    }

    // TODO(gmackall): Evaulaute how gemeni did here :) 
    // Add a task that can be called on Flutter projects that outputs app link related project
    // settings into a json file.
    //
    // See https://developer.android.com/training/app-links/ for more information about app link.
    //
    // The json will be saved in path stored in outputPath parameter.
    //
    // An example json:
    // {
    //   applicationId: "com.example.app",
    //   deeplinks: [
    //     {"scheme":"http", "host":"example.com", "path":".*"},
    //     {"scheme":"https","host":"example.com","path":".*"}
    //   ]
    // }
    //
    private static void addTasksForOutputsAppLinkSettings(Project project) {
        AppExtension androidExtension = (AppExtension) project.getExtensions().getByName("android");
        androidExtension.getApplicationVariants().all(variant -> {
            // Warning: The name of this task is used by AndroidBuilder.outputsAppLinkSettings
            project.getTasks().register("output" + capitalizeWord(variant.getName()) + "AppLinkSettings", task -> {
                task.setDescription("stores app links settings for the given build variant of this Android project into a json file.");

                variant.getOutputs().all(output -> {
                    // Deeplinks are defined in AndroidManifest.xml and is only available after
                    // `processResourcesProvider`.
                    task.dependsOn(output.getProcessResourcesProvider().get());
                });

                task.doLast(taskAction -> {
                    AppLinkSettings appLinkSettings = new AppLinkSettings();
                    appLinkSettings.setApplicationId(variant.getApplicationId());
                    appLinkSettings.setDeeplinks(new HashSet<>());

                    variant.getOutputs().all(output -> {
                        ProcessAndroidResources processResources = output.getProcessResourcesProvider().get();

                        try {
                            // Parse the Manifest file
                            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                            DocumentBuilder builder = factory.newDocumentBuilder();
                            Document manifest = builder.parse(new File(processResources.getManifestFile().getAbsolutePath()));
                            // Get the application element
                            Element application = (Element) manifest.getElementsByTagName("application").item(0);
                            // Iterate over activity elements
                            NodeList activityList = application.getElementsByTagName("activity");
                            for (int i = 0; i < activityList.getLength(); i++) {
                                Element activity = (Element) activityList.item(i);

                                // Process metadata elements
                                NodeList metadataList = activity.getElementsByTagName("meta-data");
                                for (int j = 0; j < metadataList.getLength(); j++) {
                                    Element metadata = (Element) metadataList.item(j);
                                    NamedNodeMap attributes = metadata.getAttributes();

                                    boolean nameAttribute = attributes.getNamedItem("android:name").getNodeValue().equals("flutter_deeplinking_enabled");
                                    boolean valueAttribute = attributes.getNamedItem("android:value").getNodeValue().equals("true");

                                    if (nameAttribute && valueAttribute) {
                                        appLinkSettings.setDeeplinkingFlagEnabled(true);
                                    }
                                }

                                NodeList intentFilterList = activity.getElementsByTagName("intent-filter");
                                for (int k = 0; k < intentFilterList.getLength(); k++) {
                                    Element appLinkIntent = (Element) intentFilterList.item(k);

                                    Set<String> schemes = new HashSet<>();
                                    Set<String> hosts = new HashSet<>();
                                    Set<String> paths = new HashSet<>();
                                    IntentFilterCheck intentFilterCheck = new IntentFilterCheck();

                                    if (appLinkIntent.getAttributes().getNamedItem("android:autoVerify") != null &&
                                            appLinkIntent.getAttributes().getNamedItem("android:autoVerify").getNodeValue().equals("true")) {
                                        intentFilterCheck.setHasAutoVerify(true);
                                    }

                                    NodeList actionList = appLinkIntent.getElementsByTagName("action");
                                    for (int l = 0; l < actionList.getLength(); l++) {
                                        Element action = (Element) actionList.item(l);
                                        if (action.getAttributes().getNamedItem("android:name").getNodeValue().equals("android.intent.action.VIEW")) {
                                            intentFilterCheck.setHasActionView(true);
                                        }
                                    }

                                    NodeList categoryList = appLinkIntent.getElementsByTagName("category");
                                    for (int m = 0; m < categoryList.getLength(); m++) {
                                        Element category = (Element) categoryList.item(m);
                                        String categoryName = category.getAttributes().getNamedItem("android:name").getNodeValue();
                                        if (categoryName.equals("android.intent.category.DEFAULT")) {
                                            intentFilterCheck.setHasDefaultCategory(true);
                                        } else if (categoryName.equals("android.intent.category.BROWSABLE")) {
                                            intentFilterCheck.setHasBrowsableCategory(true);
                                        }
                                    }

                                    NodeList dataList = appLinkIntent.getElementsByTagName("data");
                                    for (int n = 0; n < dataList.getLength(); n++) {
                                        Element data = (Element) dataList.item(n);
                                        NamedNodeMap dataAttributes = data.getAttributes();
                                        for (int p = 0; p < dataAttributes.getLength(); p++) {
                                            Node entry = dataAttributes.item(p);
                                            if (entry.getNodeName().startsWith("android:")) {
                                                String localPart = entry.getNodeName().substring("android:".length());
                                                switch (localPart) {
                                                    case "scheme":
                                                        schemes.add(entry.getNodeValue());
                                                        break;
                                                    case "host":
                                                        hosts.add(entry.getNodeValue());
                                                        break;
                                                    case "pathAdvancedPattern":
                                                    case "pathPattern":
                                                    case "path":
                                                        paths.add(entry.getNodeValue());
                                                        break;
                                                    case "pathPrefix":
                                                        paths.add(entry.getNodeValue() + ".*");
                                                        break;
                                                    case "pathSuffix":
                                                        paths.add(".*" + entry.getNodeValue());
                                                        break;
                                                }
                                            }
                                        }
                                    }

                                    if (!hosts.isEmpty() || !paths.isEmpty()) {
                                        if (schemes.isEmpty()) {
                                            schemes.add(null);
                                        }
                                        if (hosts.isEmpty()) {
                                            hosts.add(null);
                                        }
                                        if (paths.isEmpty()) {
                                            paths.add(".*");
                                        }

                                        for (String scheme : schemes) {
                                            for (String host : hosts) {
                                                for (String path : paths) {
                                                    appLinkSettings.getDeeplinks().add(new Deeplink(scheme, host, path, intentFilterCheck));
                                                }
                                            }
                                        }
                                    }
                                }
                                // Generate JSON output
                                // TODO(gmackall): Do we need to serialize as a string and then write?
                                //                 Can we just write the json?
                                ObjectMapper objectMapper = new ObjectMapper();
                                objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
                                String jsonOutput = objectMapper.writeValueAsString(appLinkSettings);
                                // TODO(gmackall): provide a default here, or force non null.
                                File outputFile = new File((String) project.property("outputPath"));
                                outputFile.setWritable(true);
                                Files.writeString(outputFile.toPath(), jsonOutput);
                            }
                        } catch (Exception e) {
                            throw new GradleException("Error parsing AndroidManifest.xml", e);
                        }
                    });
                });
            });
        });
    }

    /**
     * Returns a Flutter build mode suitable for the specified Android buildType.
     *
     * The BuildType DSL type is not public, and is therefore omitted from the signature.
     *
     * @return "debug", "profile", or "release" (fall-back).
     */
    private static String buildModeFor(BuildType buildType) {
        if (buildType.getName().equals("profile")) {
            return "profile";
        } else if (buildType.isJniDebuggable()) { //TODO(gmackall): this is probably a wrong replacement, find what we can do here.
            return "debug";
        }
        return "release";
    }

    private static String buildModeFor(com.android.builder.model.BuildType buildType) {
        if (buildType.getName().equals("profile")) {
            return "profile";
        } else if (buildType.isJniDebuggable()) { //TODO(gmackall): this is probably a wrong replacement, find what we can do here.
            return "debug";
        }
        return "release";
    }

    /**
     * Adds the dependencies required by the Flutter project.
     * This includes:
     *    1. The embedding
     *    2. libflutter.so
     */
    void addFlutterDependencies(BuildType buildType) {
        String flutterBuildMode = buildModeFor(buildType);
        if (!supportsBuildMode(flutterBuildMode)) {
            return;
        }
        // The embedding is set as an API dependency in a Flutter plugin.
        // Therefore, don't make the app project depend on the embedding if there are Flutter
        // plugins.
        // This prevents duplicated classes when using custom build types. That is, a custom build
        // type like profile is used, and the plugin and app projects have API dependencies on the
        // embedding.
        if (!isFlutterAppProject() || getPluginList(project).isEmpty()) {
            addApiDependencies(project, buildType.getName(),
                    "io.flutter:flutter_embedding_" + flutterBuildMode + ":" + engineVersion);
        }
        List<String> platforms = getTargetPlatforms();
        // Debug mode includes x86 and x64, which are commonly used in emulators.
        if (flutterBuildMode == "debug" && !useLocalEngine()) {
            platforms.add("android-x86");
            platforms.add("android-x64");
        }
        platforms.forEach(platform -> {
            String arch = PLATFORM_ARCH_MAP.get(platform).replace("-", "_");
            // Add the `libflutter.so` dependency.
            addApiDependencies(project, buildType.getName(),
                    "io.flutter:" + arch + "_" + flutterBuildMode + ":" + engineVersion);
        });
    }

    /**
     * Configures the Flutter plugin dependencies.
     *
     * The plugins are added to pubspec.yaml. Then, upon running `flutter pub get`,
     * the tool generates a `.flutter-plugins-dependencies` file, which contains a map to each plugin location.
     * Finally, the project's `settings.gradle` loads each plugin's android directory as a subproject.
     */
    private void configurePlugins(Project project) {
        configureLegacyPluginEachProjects(project);
        List<Map<String, Object>> pluginList = getPluginList(project);
        for (Map<String, Object> plugin : pluginList) {
            configurePluginProject(plugin);
            configurePluginDependencies(plugin);
        }
    }

    // TODO(54566, 48918): Can remove once the issues are resolved.
    //  This means all references to `.flutter-plugins` are then removed and
    //  apps only depend exclusively on the `plugins` property in `.flutter-plugins-dependencies`.
    /**
     * Workaround to load non-native plugins for developers who may still use an
     * old `settings.gradle` which includes all the plugins from the
     * `.flutter-plugins` file, even if not made for Android.
     * The settings.gradle then:
     *     1) tries to add the android plugin implementation, which does not
     *        exist at all, but is also not included successfully
     *        (which does not throw an error and therefore isn't a problem), or
     *     2) includes the plugin successfully as a valid android plugin
     *        directory exists, even if the surrounding flutter package does not
     *        support the android platform (see e.g. apple_maps_flutter: 1.0.1).
     *        So as it's included successfully it expects to be added as API.
     *        This is only possible by taking all plugins into account, which
     *        only appear on the `dependencyGraph` and in the `.flutter-plugins` file.
     * So in summary the plugins are currently selected from the `dependencyGraph`
     * and filtered then with the [doesSupportAndroidPlatform] method instead of
     * just using the `plugins.android` list.
     */
    private void configureLegacyPluginEachProjects(Project project) {
        try {
            // Read the content of settings.gradle
            String settingsGradleContent = new String(Files.readAllBytes(settingsGradleFile(project).toPath()), StandardCharsets.UTF_8);

            if (!(settingsGradleContent.contains("'.flutter-plugins'"))) {
                return;
            }
        } catch (FileNotFoundException e) {
            throw new GradleException("settings.gradle/settings.gradle.kts does not exist: " + settingsGradleFile(project).getAbsolutePath(), e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        List<Map<String, Object>> deps = getPluginDependencies(project);
        List<String> plugins = getPluginList(project).stream()
                .map(plugin -> (String) plugin.get("name"))
                .collect(Collectors.toList());
        // Remove dependencies already included in 'plugins'
        deps.removeIf(dep -> plugins.contains(dep.get("name")));
        for (Map<String, Object> dep : deps) {
            Project pluginProject = project.getRootProject().findProject(":" + dep.get("name"));
            if (pluginProject == null) {
                project.getLogger().error("Plugin project :" + dep.get("name") + " listed, but not found. Please fix your settings.gradle/settings.gradle.kts.");
            } else if (pluginSupportsAndroidPlatform(pluginProject)) {
                configurePluginProject(dep);
            } else {
                // No action needed for plugins without Android support
            }
        }
    }

    // TODO(54566): Can remove this function and its call sites once resolved.
    /**
     * Returns `true` if the given project is a plugin project having an `android` directory
     * containing a `build.gradle` or `build.gradle.kts` file.
     */
    private Boolean pluginSupportsAndroidPlatform(Project project) {
        File buildGradle = new File(project.getProjectDir().getParentFile(), "android" + File.separator + "build.gradle");
        File buildGradleKts = new File(project.getProjectDir().getParentFile(), "android" + File.separator + "build.gradle.kts");
        return buildGradle.exists() || buildGradleKts.exists();
    }

    /**
     * Returns the Gradle build script for the build. When both Groovy and
     * Kotlin variants exist, then Groovy (build.gradle) is preferred over
     * Kotlin (build.gradle.kts). This is the same behavior as Gradle 8.5.
     */
    private File buildGradleFile(Project project) {
        File buildGradle = new File(project.getProjectDir().getParentFile(), "app" + File.separator + "build.gradle");
        File buildGradleKts = new File(project.getProjectDir().getParentFile(), "app" + File.separator + "build.gradle.kts");
        if (buildGradle.exists() && buildGradleKts.exists()) {
            project.getLogger().error(
                    "Both build.gradle and build.gradle.kts exist, so " +
                            "build.gradle.kts is ignored. This is likely a mistake."
            );
        }

        return buildGradle.exists() ? buildGradle : buildGradleKts;
    }

    /**
     * Returns the Gradle settings script for the build. When both Groovy and
     * Kotlin variants exist, then Groovy (settings.gradle) is preferred over
     * Kotlin (settings.gradle.kts). This is the same behavior as Gradle 8.5.
     */
    private File settingsGradleFile(Project project) {
        File settingsGradle = new File(project.getProjectDir().getParentFile(), "settings.gradle");
        File settingsGradleKts = new File(project.getProjectDir().getParentFile(), "settings.gradle.kts");
        if (settingsGradle.exists() && settingsGradleKts.exists()) {
            project.getLogger().error(
                    "Both settings.gradle and settings.gradle.kts exist, so " +
                            "settings.gradle.kts is ignored. This is likely a mistake."
            );
        }

        return settingsGradle.exists() ? settingsGradle : settingsGradleKts;
    }

    /** Adds the plugin project dependency to the app project. */
    private void configurePluginProject(Map<String, Object> pluginObject) {
        assert pluginObject.get("name") instanceof String;
        Project pluginProject = project.getRootProject().findProject(":" + pluginObject.get("name"));
        if (pluginProject == null) {
            return;
        }

        // Add plugin dependency to the app project
        project.getDependencies().add("api", pluginProject);

        // Define the Action/Lambda to add embedding dependency to the plugin
        Action<BuildType> addEmbeddingDependencyToPlugin = buildType -> {
            String flutterBuildMode = buildModeFor(buildType);

            // Check if the build mode is supported
            if (!supportsBuildMode(flutterBuildMode)) {
                return;
            }

            // Check if the plugin project has the 'android' property
            if (!pluginProject.hasProperty("android")) {
                return;
            }

            // Access the Android extension in the plugin project (assuming BaseExtension, adjust if needed)
            BaseExtension pluginAndroidExtension = (BaseExtension) pluginProject.getExtensions().getByName("android");

            Action<? super NamedDomainObjectContainer<com.android.build.gradle.internal.dsl.BuildType>> action = new Action<>() {
                @Override
                public void execute(NamedDomainObjectContainer<com.android.build.gradle.internal.dsl.BuildType> buildTypes) {
                    buildTypes.create(buildType.getName()); // Create a build type with the same name
                }
            };

            // Copy build types from the app to the plugin
            pluginAndroidExtension.buildTypes(action);

            // Add the Flutter embedding dependency to the plugin
            addApiDependencies(
                    pluginProject,
                    buildType.getName(),
                    "io.flutter:flutter_embedding_" + flutterBuildMode + ":" + engineVersion
            );
        };

        BaseExtension projectAndroidExtension = (BaseExtension) project.getExtensions().getByName("android");
        assert projectAndroidExtension.getCompileSdkVersion() != null; // TODO(gmackall): should we handle?
        Integer projectCompileSdk = Integer.parseInt(projectAndroidExtension.getCompileSdkVersion());

        // Wait until the Android plugin is loaded in the plugin project
        pluginProject.afterEvaluate(project -> {
            // Access the Android extension in the plugin project
            BaseExtension pluginAndroidExtension = (BaseExtension) pluginProject.getExtensions().getByName("android");

            // Check compileSdkVersion mismatch and log a warning if necessary
            if (pluginAndroidExtension.getCompileSdkVersion() != null &&
                    (Integer.parseInt(pluginAndroidExtension.getCompileSdkVersion()) > projectCompileSdk)) {

                project.getLogger().quiet("Warning: The plugin " + pluginObject.get("name") + " requires Android SDK version " +
                        getCompileSdkFromProject(pluginProject) + " or higher.");
                project.getLogger().quiet("For more information about build configuration, see " + kWebsiteDeploymentAndroidBuildConfig + ".");
            }

            // Apply 'addEmbeddingDependencyToPlugin' to all build types in the main project
            projectAndroidExtension.getBuildTypes().all(addEmbeddingDependencyToPlugin);
        });
    }


    /**
     * Compares semantic versions ignoring labels.
     *
     * If the versions are equal (ignoring labels), returns one of the two strings arbitrarily.
     *
     * If minor or patch are omitted (non-conformant to semantic versioning), they are considered zero.
     * If the provided versions in both are equal, the longest version string is returned.
     * For example, "2.8.0" vs "2.8" will always consider "2.8.0" to be the most recent version.
     * TODO: Remove this or compareVersionStrings. This does not handle strings like "8.6-rc-2".
     */
    static String mostRecentSemanticVersion(String version1, String version2) {
        String[] version1Tokens = version1.split("\\.");
        String[] version2Tokens = version2.split("\\.");
        int version1NumTokens = version1Tokens.length;
        int version2NumTokens = version2Tokens.length;
        int minNumTokens = Math.min(version1NumTokens, version2NumTokens);

        for (int i = 0; i < minNumTokens; i++) {
            int num1 = Integer.parseInt(version1Tokens[i]);
            int num2 = Integer.parseInt(version2Tokens[i]);
            if (num1 > num2) {
                return version1;
            } else if (num2 > num1) {
                return version2;
            }
        }

        // If numeric parts are equal, return the longer version string
        return (version1NumTokens > version2NumTokens) ? version1 : version2;
    }

    /** Prints error message and fix for any plugin compileSdkVersion or ndkVersion that are higher than the project. */
    //TODO(gmackall): check gemini's work here
    private void detectLowCompileSdkVersionOrNdkVersion() {
        project.afterEvaluate(project -> {
            // Default to Integer.MAX_VALUE if using a preview version to skip the SDK check
            int projectCompileSdkVersion = Integer.MAX_VALUE;

            // Check if compileSdkVersion is an integer (stable versions) or a string (legacy preview)
            if (getCompileSdkFromProject(project).matches("\\d+")) { // Check if it consists only of digits
                projectCompileSdkVersion = Integer.parseInt(getCompileSdkFromProject(project));
            }


            final int[] maxPluginCompileSdkVersion = {projectCompileSdkVersion};
            String ndkVersionIfUnspecified = "21.1.6352462"; // Default for AGP 4.1.0 used in old templates
            BaseExtension projectAndroidExtension = (BaseExtension) project.getExtensions().getByName("android");
            // TODO(gmackall): this default is suspect now that AGP is much higher in the templates.
            String projectNdkVersion = (projectAndroidExtension.getCompileSdkVersion() != null) ? projectAndroidExtension.getCompileSdkVersion() : ndkVersionIfUnspecified;
            final String[] maxPluginNdkVersion = {projectNdkVersion};
            final int[] numProcessedPlugins = {getPluginList(project).size()};

            // Use ArrayList for dynamic resizing
            List<Tuple2<String, String>> pluginsWithHigherSdkVersion = new ArrayList<>();
            List<Tuple2<String, String>> pluginsWithDifferentNdkVersion = new ArrayList<>();

            for (Map<String, Object> pluginObject : getPluginList(project)) {
                assert pluginObject.get("name") instanceof String;
                Project pluginProject = project.getRootProject().findProject(":" + pluginObject.get("name"));
                if (pluginProject == null) {
                    continue; // Skip if plugin project is not found
                }

                int finalProjectCompileSdkVersion = projectCompileSdkVersion;
                pluginProject.afterEvaluate(new Action<Project>() {
                    @Override
                    public void execute(Project pluginProject) {
                        // Default to Integer.MIN_VALUE if using a preview version
                        int pluginCompileSdkVersion = Integer.MIN_VALUE;

                        // Check if compileSdkVersion is an integer
                        if (getCompileSdkFromProject(pluginProject).matches("\\d+")) {
                            pluginCompileSdkVersion = Integer.parseInt(getCompileSdkFromProject(pluginProject));
                        }

                        maxPluginCompileSdkVersion[0] = Math.max(pluginCompileSdkVersion, maxPluginCompileSdkVersion[0]);
                        if (pluginCompileSdkVersion > finalProjectCompileSdkVersion) {
                            pluginsWithHigherSdkVersion.add(new Tuple2<>(pluginProject.getName(), String.valueOf(pluginCompileSdkVersion)));
                        }

                        String pluginNdkVersion = (projectAndroidExtension.getNdkVersion() != null) ? projectAndroidExtension.getNdkVersion() : ndkVersionIfUnspecified;
                        maxPluginNdkVersion[0] = mostRecentSemanticVersion(pluginNdkVersion, maxPluginNdkVersion[0]);
                        if (!pluginNdkVersion.equals(projectNdkVersion)) {
                            pluginsWithDifferentNdkVersion.add(new Tuple2<>(pluginProject.getName(), pluginNdkVersion));
                        }

                        numProcessedPlugins[0]--;
                        if (numProcessedPlugins[0] == 0) {
                            if (maxPluginCompileSdkVersion[0] > finalProjectCompileSdkVersion) {
                                project.getLogger().error("Your project is configured to compile against Android SDK " + finalProjectCompileSdkVersion + ", but the following plugin(s) require to be compiled against a higher Android SDK version:");
                                for (Tuple2<String, String> pluginToCompileSdkVersion : pluginsWithHigherSdkVersion) {
                                    project.getLogger().error("- " + pluginToCompileSdkVersion.getFirst() + " compiles against Android SDK " + pluginToCompileSdkVersion.getSecond());
                                }
                                project.getLogger().error("\nFix this issue by compiling against the highest Android SDK version (they are backward compatible).\nAdd the following to " + buildGradleFile(project).getPath() + ":\n\n" +
                                        "    android {\n" +
                                        "        compileSdk = " + maxPluginCompileSdkVersion[0] + "\n" +
                                        "        ...\n" +
                                        "    }\n");
                            }
                            if (!maxPluginNdkVersion[0].equals(projectNdkVersion)) {
                                project.getLogger().error("Your project is configured with Android NDK " + projectNdkVersion + ", but the following plugin(s) depend on a different Android NDK version:");
                                for (Tuple2<String, String> pluginToNdkVersion : pluginsWithDifferentNdkVersion) {
                                    project.getLogger().error("- " + pluginToNdkVersion.getFirst() + " requires Android NDK " + pluginToNdkVersion.getSecond());
                                }
                                project.getLogger().error("\nFix this issue by using the highest Android NDK version (they are backward compatible).\nAdd the following to " + buildGradleFile(project).getPath() + ":\n\n" +
                                        "    android {\n" +
                                        "        ndkVersion = \"" + maxPluginNdkVersion[0] + "\"\n" +
                                        "        ...\n" +
                                        "    }\n");
                            }
                        }
                    }
                });
            }
        });
    }


    /**
     * Returns the portion of the compileSdkVersion string that corresponds to either the numeric
     * or string version.
     */
    private String getCompileSdkFromProject(Project gradleProject) {
        BaseExtension projectAndroidExtension = (BaseExtension) gradleProject.getExtensions().getByName("android");
        return projectAndroidExtension.getCompileSdkVersion();
    }

    /**
     * Add the dependencies on other plugin projects to the plugin project.
     * A plugin A can depend on plugin B. As a result, this dependency must be surfaced by
     * making the Gradle plugin project A depend on the Gradle plugin project B.
     */
    private void configurePluginDependencies(Map<String, Object> pluginObject) {
        assert pluginObject.get("name") instanceof String;
        Project pluginProject = project.getRootProject().findProject(":" + pluginObject.get("name"));
        if (pluginProject == null) {
            return;
        }

        // Access dependencies and ensure it's a list of strings
        Object dependenciesObj = pluginObject.get("dependencies");
        assert dependenciesObj instanceof List;
        List<String> dependencies = (List<String>) dependenciesObj;

        for (String pluginDependencyName : dependencies) {
            if (pluginDependencyName.isEmpty()) {
                continue; // Skip empty dependency names
            }

            Project dependencyProject = project.getRootProject().findProject(":" + pluginDependencyName);
            if (dependencyProject == null) {
                continue; // Skip if dependency project is not found
            }

            // Wait for the Android plugin to load and add the dependency
            pluginProject.afterEvaluate(project -> {
                // Access the DependencyHandler and add the 'implementation' dependency
                DependencyHandler dependencyHandler = pluginProject.getDependencies();
                dependencyHandler.add("implementation", dependencyProject);
            });
        }
    }


    /**
     * Gets the list of plugins (as map) that support the Android platform.
     *
     * The map value contains either the plugins `name` (String),
     * its `path` (String), or its `dependencies` (List<String>).
     * See [NativePluginLoader#getPlugins] in packages/flutter_tools/gradle/src/main/groovy/native_plugin_loader.groovy
     */
    private List<Map<String, Object>> getPluginList(Project project) {
        if (pluginList == null) {
            // Access the 'nativePluginLoader' extension and call 'getPlugins'
            NativePluginLoader nativePluginLoader = (NativePluginLoader) project.getGradle().getExtensions().getByType(ExtraPropertiesExtension.class).get("nativePluginLoader");
            pluginList = nativePluginLoader.getPlugins(getFlutterSourceDirectory());
        }
        return pluginList;
    }

    // TODO(54566, 48918): Remove in favor of [getPluginList] only, see also
    //  https://github.com/flutter/flutter/blob/1c90ed8b64d9ed8ce2431afad8bc6e6d9acc4556/packages/flutter_tools/lib/src/flutter_plugins.dart#L212
    /** Gets the plugins dependencies from `.flutter-plugins-dependencies`. */
    private List<Map<String, Object>> getPluginDependencies(Project project) {
        if (pluginDependencies == null) {
            // Access the 'nativePluginLoader' extension and call 'getDependenciesMetadata'
            NativePluginLoader nativePluginLoader = (NativePluginLoader) project.getExtensions().getByName("nativePluginLoader");
            Map<String, Object> meta = nativePluginLoader.getDependenciesMetadata(getFlutterSourceDirectory());

            if (meta == null) {
                pluginDependencies = new ArrayList<>();
            } else {
                // Access the 'dependencyGraph' from 'meta' and ensure it's a list of maps
                assert meta.get("dependencyGraph") instanceof List;
                pluginDependencies = (List<Map<String, Object>>) meta.get("dependencyGraph");
            }
        }
        return pluginDependencies;
    }

    private String resolveProperty(String name, String defaultValue) {
        if (localProperties == null) {
            localProperties = readPropertiesIfExist(new File(project.getProjectDir().getParentFile(), "local.properties"));
        }
        String result = null;
        if (project.hasProperty(name)) {
            result = (String) project.property("name");
        }
        if (result == null) {
            result = localProperties.getProperty(name);
        }
        if (result == null) {
            result = defaultValue;
        }
        return result;
    }

    private List<String> getTargetPlatforms() {
        final String propTargetPlatform = "target-platform";
        if (!project.hasProperty(propTargetPlatform)) {
            return DEFAULT_PLATFORMS;
        }

        String targetPlatformValue = (String) project.property(propTargetPlatform);
        assert targetPlatformValue != null;
        String[] platforms = targetPlatformValue.split(",");

        // Use Stream to process and validate platforms
        return Arrays.stream(platforms)
                .filter(platform -> {
                    if (!PLATFORM_ARCH_MAP.containsKey(platform)) {
                        throw new GradleException("Invalid platform: " + platform);
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    private Boolean getPropertyBoolean(String propertyString) {
        Object propertyValue = project.findProperty(propertyString);
        if (propertyValue != null) {
            // Check if the property value can be converted to a boolean
            // TODO(gmackall): do we ever actually need to /want to convert from string.
            if (propertyValue instanceof Boolean) {
                return (Boolean) propertyValue;
            } else if (propertyValue instanceof String) {
                return Boolean.parseBoolean((String) propertyValue);
            }
        }
        return false; // Default to false if the property doesn't exist or can't be converted
    }

    private Boolean shouldSplitPerAbi() {
        return getPropertyBoolean("split-per-abi");
    }

    private Boolean useLocalEngine() {
        return project.hasProperty(propLocalEngineRepo);
    }

    private Boolean isVerbose() {
        return getPropertyBoolean("verbose");
    }

    /** Whether to build the debug app in "fast-start" mode. */
    private Boolean isFastStart() {
        return getPropertyBoolean("fast-start");
    }

    /**
     * Returns true if the build mode is supported by the current call to Gradle.
     * This only relevant when using a local engine. Because the engine
     * is built for a specific mode, the call to Gradle must match that mode.
     */
    private Boolean supportsBuildMode(String flutterBuildMode) {
        if (!useLocalEngine()) {
            return true;
        }
        final String propLocalEngineBuildMode = "local-engine-build-mode";
        assert(project.hasProperty(propLocalEngineBuildMode));
        // Don't configure dependencies for a build mode that the local engine
        // doesn't support.
        return project.property(propLocalEngineBuildMode) == flutterBuildMode;
    }

    /**
     * Gets the directory that contains the Flutter source code.
     * This is the directory containing the `android/` directory.
     */
    private File getFlutterSourceDirectory() {
        // Access the 'flutter' extension and its 'source' property
        FlutterExtension flutterExtension = (FlutterExtension) project.getExtensions().getByType(FlutterExtension.class);
        Object flutterSource = flutterExtension.getSource();

        if (flutterSource == null) {
            throw new GradleException("Must provide Flutter source directory");
        }

        // Convert 'flutterSource' to File (assuming it's a String representing the path)
        return project.file(flutterSource);
    }

    /**
     * Gets the target file. This is typically `lib/main.dart`.
     */
    private String getFlutterTarget() {
        FlutterExtension flutterExtension = (FlutterExtension) project.getExtensions().getByType(FlutterExtension.class);
        String target = flutterExtension.getTarget();
        if (target == null) {
            target = "lib/main.dart";
        }
        final String propTarget = "target";
        if (project.hasProperty(propTarget)) {
            assert project.property(propTarget) instanceof String;
            target = (String) project.property(propTarget);
        }
        return target;
    }

    // TODO: Remove this AGP hack. https://github.com/flutter/flutter/issues/109560
    /**
     * In AGP 4.0, the Android linter task depends on the JAR tasks that generate `libapp.so`.
     * When building APKs, this causes an issue where building release requires the debug JAR,
     * but Gradle won't build debug.
     *
     * To workaround this issue, only configure the JAR task that is required given the task
     * from the command line.
     *
     * The AGP team said that this issue is fixed in Gradle 7.0, which isn't released at the
     * time of adding this code. Once released, this can be removed. However, after updating to
     * AGP/Gradle 7.2.0/7.5, removing this hack still causes build failures. Further
     * investigation necessary to remove this.
     *
     * Tested cases:
     * * `./gradlew assembleRelease`
     * * `./gradlew app:assembleRelease.`
     * * `./gradlew assemble{flavorName}Release`
     * * `./gradlew app:assemble{flavorName}Release`
     * * `./gradlew assemble.`
     * * `./gradlew app:assemble.`
     * * `./gradlew bundle.`
     * * `./gradlew bundleRelease.`
     * * `./gradlew app:bundleRelease.`
     *
     * Related issues:
     * https://issuetracker.google.com/issues/158060799
     * https://issuetracker.google.com/issues/158753935
     */
    private boolean shouldConfigureFlutterTask(Task assembleTask) {
        List<String> cliTasksNames = project.getGradle().getStartParameter().getTaskNames();
        if (cliTasksNames.size() != 1 || !cliTasksNames.get(0).contains("assemble")) {
            return true;
        }
        String[] splitTaskName = cliTasksNames.get(0).split(":");
        String taskName = splitTaskName[splitTaskName.length - 1];
        if (taskName.equals("assemble")) {
            return true;
        }
        if (taskName.equals(assembleTask.getName())) {
            return true;
        }
        if (taskName.endsWith("Release") && assembleTask.getName().endsWith("Release")) {
            return true;
        }
        if (taskName.endsWith("Debug") && assembleTask.getName().endsWith("Debug")) {
            return true;
        }
        if (taskName.endsWith("Profile") && assembleTask.getName().endsWith("Profile")) {
            return true;
        }
        return false;
    }

    private Task getAssembleTask(BaseVariant variant) {
        // `assemble` became `assembleProvider` in AGP 3.3.0.
        return variant.getAssembleProvider().get();
    }

    private boolean isFlutterAppProject() {
        try {
            project.getExtensions().getByType(ApplicationExtension.class);
        } catch (UnknownDomainObjectException e) {
            // not an app
            return false;
        }
        return true;
    }

    private void addFlutterTasks(Project project) {
        if (project.getState().getFailure() != null) {
            return;
        }
        String[] fileSystemRootsValue;
        final String propFileSystemRoots = "filesystem-roots";
        if (project.hasProperty(propFileSystemRoots)) {
            fileSystemRootsValue = ((String) project.property(propFileSystemRoots)).split("\\|");
        } else {
            fileSystemRootsValue = null;
        }
        String fileSystemSchemeValue;
        final String propFileSystemScheme = "filesystem-scheme";
        if (project.hasProperty(propFileSystemScheme)) {
            fileSystemSchemeValue = (String) project.property(propFileSystemScheme);
        } else {
            fileSystemSchemeValue = null;
        }
        Boolean trackWidgetCreationValue;
        final String propTrackWidgetCreation = "track-widget-creation";
        if (project.hasProperty(propTrackWidgetCreation)) {

            trackWidgetCreationValue = getPropertyBoolean(propTrackWidgetCreation);
        } else {
            trackWidgetCreationValue = true;
        }
        String frontendServerStarterPathValue;
        final String propFrontendServerStarterPath = "frontend-server-starter-path";
        if (project.hasProperty(propFrontendServerStarterPath)) {
            frontendServerStarterPathValue = (String) project.property(propFrontendServerStarterPath);
        } else {
            frontendServerStarterPathValue = null;
        }
        String extraFrontEndOptionsValue;
        final String propExtraFrontEndOptions = "extra-front-end-options";
        if (project.hasProperty(propExtraFrontEndOptions)) {
            extraFrontEndOptionsValue = (String) project.property(propExtraFrontEndOptions);
        } else {
            extraFrontEndOptionsValue = null;
        }
        String extraGenSnapshotOptionsValue;
        final String propExtraGenSnapshotOptions = "extra-gen-snapshot-options";
        if (project.hasProperty(propExtraGenSnapshotOptions)) {
            extraGenSnapshotOptionsValue = (String) project.property(propExtraGenSnapshotOptions);
        } else {
            extraGenSnapshotOptionsValue = null;
        }
        String splitDebugInfoValue;
        final String propSplitDebugInfo = "split-debug-info";
        if (project.hasProperty(propSplitDebugInfo)) {
            splitDebugInfoValue = (String) project.property(propSplitDebugInfo);
        } else {
            splitDebugInfoValue = null;
        }
        Boolean dartObfuscationValue;
        final String propDartObfuscation = "dart-obfuscation";
        if (project.hasProperty(propDartObfuscation)) {
            dartObfuscationValue = getPropertyBoolean(propDartObfuscation);
        } else {
            dartObfuscationValue = false;
        }
        Boolean treeShakeIconsOptionsValue;
        final String propTreeShakeIcons = "tree-shake-icons";
        if (project.hasProperty(propTreeShakeIcons)) {
            treeShakeIconsOptionsValue = getPropertyBoolean(propTreeShakeIcons);
        } else {
            treeShakeIconsOptionsValue = false;
        }
        String dartDefinesValue;
        final String propDartDefines = "dart-defines";
        if (project.hasProperty(propDartDefines)) {
            dartDefinesValue = (String) project.property(propDartDefines);
        } else {
            dartDefinesValue = null;
        }
        String bundleSkSLPathValue;
        final String propBundleSkslPath = "bundle-sksl-path";
        if (project.hasProperty(propBundleSkslPath)) {
            bundleSkSLPathValue = (String) project.property(propBundleSkslPath);
        } else {
            bundleSkSLPathValue = null;
        }
        String performanceMeasurementFileValue;
        final String propPerformanceMeasurementFile = "performance-measurement-file";
        if (project.hasProperty(propPerformanceMeasurementFile)) {
            performanceMeasurementFileValue = (String) project.property(propPerformanceMeasurementFile);
        } else {
            performanceMeasurementFileValue = null;
        }
        String codeSizeDirectoryValue;
        final String propCodeSizeDirectory = "code-size-directory";
        if (project.hasProperty(propCodeSizeDirectory)) {
            codeSizeDirectoryValue = (String) project.property(propCodeSizeDirectory);
        } else {
            codeSizeDirectoryValue = null;
        }
        Boolean deferredComponentsValue;
        final String propDeferredComponents = "deferred-components";
        if (project.hasProperty(propDeferredComponents)) {
            deferredComponentsValue = getPropertyBoolean(propDeferredComponents);
        } else {
            deferredComponentsValue = false;
        }
        Boolean validateDeferredComponentsValue;
        final String propValidateDeferredComponents = "validate-deferred-components";
        if (project.hasProperty(propValidateDeferredComponents)) {
            validateDeferredComponentsValue = getPropertyBoolean(propValidateDeferredComponents);
        } else {
            validateDeferredComponentsValue = true;
        }
        addTaskForJavaVersion(project);
        if (isFlutterAppProject()) {
            addTaskForPrintBuildVariants(project);
            addTasksForOutputsAppLinkSettings(project);
        }
        List<String> targetPlatforms = getTargetPlatforms();

        Function<BaseVariant, Task> addFlutterDeps = (baseVariant) -> {
            // Handle ABI split version code overrides

            if (shouldSplitPerAbi()) {
                baseVariant.getOutputs().forEach((Consumer<? super BaseVariantOutput>) output -> {
                    if (!(output instanceof ApkVariantOutput)) {
                        return;
                    }
                    Integer abiVersionCode = ABI_VERSION.get(((ApkVariantOutput) output).getFilter(VariantOutput.FilterType.valueOf(OutputFile.ABI)));
                    if (abiVersionCode != null && (baseVariant instanceof ApkVariant)) {
                        ((ApkVariantOutput) output).setVersionCodeOverride(abiVersionCode * 1000 + ((ApkVariant) baseVariant).getVersionCode());
                    }
                });
            }

            // Check if building an AAR
            boolean isBuildingAar = project.hasProperty("is-plugin");

            // Check if Flutter module is used as a subproject
            Task packageAssets = project.getTasks().findByPath(":flutter:package" + capitalizeWord(baseVariant.getName()) + "Assets");
            Task cleanPackageAssets = project.getTasks().findByPath(":flutter:cleanPackage" + capitalizeWord(baseVariant.getName()) + "Assets");
            boolean isUsedAsSubproject = (packageAssets != null && cleanPackageAssets != null && !isBuildingAar);

            String variantBuildMode = buildModeFor(baseVariant.getBuildType());
            String flavorValue = baseVariant.getFlavorName();
            String taskName = toCamelCase(Arrays.asList("compile", FLUTTER_BUILD_PREFIX, baseVariant.getName()));

            // Create the FlutterTask
            FlutterTask compileTask = project.getTasks().create(taskName, FlutterTask.class);
            compileTask.setFlutterRoot(flutterRoot);
            compileTask.setFlutterExecutable(flutterExecutable);
            compileTask.setBuildMode(variantBuildMode);
            compileTask.setMinSdkVersion(baseVariant.getMergedFlavor().getMinSdkVersion().getApiLevel());
            compileTask.setLocalEngine(localEngine);
            compileTask.setLocalEngineHost(localEngineHost);
            compileTask.setLocalEngineSrcPath(localEngineSrcPath);
            compileTask.setTargetPath(getFlutterTarget());
            compileTask.setVerbose(isVerbose());
            compileTask.setFastStart(isFastStart());
            compileTask.setFileSystemRoots(fileSystemRootsValue);
            compileTask.setFileSystemScheme(fileSystemSchemeValue);
            compileTask.setTrackWidgetCreation(trackWidgetCreationValue);
            compileTask.setTargetPlatformValues(targetPlatforms);
            compileTask.setSourceDir(getFlutterSourceDirectory());

            // Construct intermediateDir path using String formatting
            String intermediateDirPath = String.format("%s/%s/flutter/%s/", project.getBuildDir(), INTERMEDIATES_DIR, baseVariant.getName());
            compileTask.setIntermediateDir(project.file(intermediateDirPath));

            compileTask.setFrontendServerStarterPath(frontendServerStarterPathValue);
            compileTask.setExtraFrontEndOptions(extraFrontEndOptionsValue);
            compileTask.setExtraGenSnapshotOptions(extraGenSnapshotOptionsValue);
            compileTask.setSplitDebugInfo(splitDebugInfoValue);
            compileTask.setTreeShakeIcons(treeShakeIconsOptionsValue);
            compileTask.setDartObfuscation(dartObfuscationValue);
            compileTask.setDartDefines(dartDefinesValue);
            compileTask.setBundleSkSLPath(bundleSkSLPathValue);
            compileTask.setPerformanceMeasurementFile(performanceMeasurementFileValue);
            compileTask.setCodeSizeDirectory(codeSizeDirectoryValue);
            compileTask.setDeferredComponents(deferredComponentsValue);
            compileTask.setValidateDeferredComponents(validateDeferredComponentsValue);
            compileTask.setFlavor(flavorValue);

            // Create libJar File object
            String libJarPath = String.format("%s/%s/flutter/%s/libs.jar", project.getBuildDir(), INTERMEDIATES_DIR, baseVariant.getName());
            File libJar = project.file(libJarPath);

            // Create packJniLibsTask
            Jar packJniLibsTask = project.getTasks().create("packJniLibs" + FLUTTER_BUILD_PREFIX + baseVariant.getName().substring(0, 1).toUpperCase() + baseVariant.getName().substring(1), Jar.class);
            //TODO(gmackall): fix?
            packJniLibsTask.getDestinationDirectory().set(libJar.getParentFile());
            packJniLibsTask.getArchiveFileName().set(libJar.getName());
            packJniLibsTask.dependsOn(compileTask);

            for (String targetPlatform : targetPlatforms) {
                String abi = PLATFORM_ARCH_MAP.get(targetPlatform);
                Transformer<String, String> transformer = (input) -> {
                    return "lib/" + abi + "/" + input;
                };
                ConfigurableFileTree abiFiles = project.fileTree(compileTask.getIntermediateDir() + "/" + abi);
                abiFiles.include("*.so");
                packJniLibsTask.from(abiFiles, copySpec -> copySpec.rename(transformer));

                // Copy native assets
                String buildDir = getFlutterSourceDirectory() + "/build";
                String nativeAssetsDir = buildDir + "/native_assets/android/jniLibs/lib";
                ConfigurableFileTree nativeAssetsAbiFiles = project.fileTree(nativeAssetsDir + "/" + abi);
                nativeAssetsAbiFiles.include("*.so");
                packJniLibsTask.from(nativeAssetsAbiFiles, copySpec -> copySpec.rename(transformer));
            }

            // Add API dependencies
            addApiDependencies(project, baseVariant.getName(), project.files(packJniLibsTask));

            // Create copyFlutterAssetsTask
            Copy copyFlutterAssetsTask = project.getTasks().create("copyFlutterAssets" + capitalizeWord(baseVariant.getName()), Copy.class);
            copyFlutterAssetsTask.dependsOn(compileTask);
            copyFlutterAssetsTask.with(compileTask.getAssets());
            copyFlutterAssetsTask.setDestinationDir(libJar.getParentFile());

            // Set file permissions based on Gradle version
            String currentGradleVersion = project.getGradle().getGradleVersion();
            if (compareVersionStrings(currentGradleVersion, "8.3") >= 0) {
                //copyFlutterAssetsTask.getDestinationDir().setReadable(true);
                //copyFlutterAssetsTask.getDestinationDir().setWritable(true);
            } else {
                copyFlutterAssetsTask.setFileMode(0644);
            }

            if (isUsedAsSubproject) {
                copyFlutterAssetsTask.dependsOn(packageAssets);
                copyFlutterAssetsTask.dependsOn(cleanPackageAssets);
                copyFlutterAssetsTask.into(packageAssets.getPath());
                return copyFlutterAssetsTask;
            }

            // Handle 'mergeAssets' or 'mergeAssetsProvider' based on AGP version
            Task mergeAssets = baseVariant.getMergeAssetsProvider().get();
            copyFlutterAssetsTask.dependsOn(mergeAssets);

            if (!isUsedAsSubproject) {
                BaseVariantOutput variantOutput = baseVariant.getOutputs().stream().findFirst().get();
                variantOutput.getProcessResourcesProvider().get().dependsOn(copyFlutterAssetsTask);
            }
            // The following tasks use the output of copyFlutterAssetsTask,
            // so it's necessary to declare it as an dependency since Gradle 8.
            // See https://docs.gradle.org/8.1/userguide/validation_problems.html#implicit_dependency.
            Task compressAssetsTask = project.getTasks().findByName("compress" + capitalizeWord(baseVariant.getName()) + "Assets");
            if (compressAssetsTask != null) {
                compressAssetsTask.dependsOn(copyFlutterAssetsTask);
            }

            Task bundleAarTask = project.getTasks().findByName("bundle" + capitalizeWord(baseVariant.getName()) + "Aar");
            if (bundleAarTask != null) {
                bundleAarTask.dependsOn(copyFlutterAssetsTask);
            }

            Task bundleAarTaskWithLint = project.getTasks().findByName("bundle" + capitalizeWord(baseVariant.getName()) + "LocalLintAar");
            if (bundleAarTaskWithLint != null) {
                bundleAarTaskWithLint.dependsOn(copyFlutterAssetsTask);
            }

            Task collectDependencies = project.getTasks().findByName("collect" + capitalizeWord(baseVariant.getName()) + "Dependencies");
            if (collectDependencies != null) {
                collectDependencies.dependsOn(copyFlutterAssetsTask);
            }

            return copyFlutterAssetsTask;
        };

        System.out.println("HI GRAY BEFOR EPLACE1 ");
        if (isFlutterAppProject()) {
            AppExtension androidExtension = project.getExtensions().getByType(AppExtension.class);
            System.out.println("HI GRAY BEFOR EPLACE2 " + androidExtension.getApplicationVariants().size());
            androidExtension.getApplicationVariants().all(variant -> {
                System.out.println("HI GRAY BEFOR EPLACE3");
                Task assembleTask = getAssembleTask(variant);
                if (!shouldConfigureFlutterTask(assembleTask)) {
                    System.out.println("HI GRAY early case");
                    return;
                }
                System.out.println("HI GRAY BEFOR EPLACE4 ");
                Task copyFlutterAssetsTask = addFlutterDeps.apply(variant);
                Optional<BaseVariantOutput> variantOutput = variant.getOutputs().stream().findFirst();
                // ------------------------------------------------------------
                ProcessAndroidResources processResources = variantOutput.get().getProcessResources();
                processResources.dependsOn(copyFlutterAssetsTask);

                // Copy the output APKs into a known location, so `flutter run` or `flutter build apk`
                // can discover them. By default, this is `<app-dir>/build/app/outputs/flutter-apk/<filename>.apk`.
                //
                // The filename consists of `app<-abi>?<-flavor-name>?-<build-mode>.apk`.
                // Where:
                //   * `abi` can be `armeabi-v7a|arm64-v8a|x86|x86_64` only if the flag `split-per-abi` is set.
                //   * `flavor-name` is the flavor used to build the app in lower case if the assemble task is called.
                //   * `build-mode` can be `release|debug|profile`.
                System.out.println("HI GRAY getting closer");
                variant.getOutputs().all( output -> {
                    assembleTask.doLast( task1 -> {
                        // `packageApplication` became `packageApplicationProvider` in AGP 3.3.0.
                        Directory outputDirectory = variant.getPackageApplicationProvider()
                                .get()
                                .getOutputDirectory()
                                .get();
                        //  `outputDirectory` is a `DirectoryProperty` in AGP 4.1.
//                        String outputDirectoryStr = outputDirectory.metaClass.respondsTo(outputDirectory, "get")
//                                ? outputDirectory.get()
//                                : outputDirectory;
                        String outputDirectoryStr = outputDirectory.toString();
                        String filename = "app";
                        List<FilterData> filterDataList = output.getFilters().stream().filter(filterData -> {
                            return filterData.getFilterType().equals(OutputFile.ABI);
                        }).toList();
                        String abi = filterDataList.isEmpty() ? null : filterDataList.get(0).getIdentifier();
                        //String abi = output.getFilter(OutputFile.ABI);
                        if (abi != null && !abi.isEmpty()) {
                            filename += "-" + abi;
                        }
                        if (variant.getFlavorName() != null && !variant.getFlavorName().isEmpty()) {
                            filename += "-" + variant.getFlavorName().toLowerCase();
                        }
                        // TODO(gmackall): fix this suspect cast
                        filename += "-" + buildModeFor(variant.getBuildType());
                        String finalFilename = filename;
                        project.copy(copySpec ->  {
                            copySpec.from(new File(outputDirectoryStr + "/" + output.getOutputFile().getName()));
                            copySpec.into(new File(project.getBuildDir() + "/outputs/flutter-apk"));
                            copySpec.rename( ignored -> finalFilename + ".apk");
                        });
                    });
                });
                // Copy the native assets created by build.dart and placed here by flutter assemble.
                String nativeAssetsDir = project.getBuildDir() + "/../native_assets/android/jniLibs/lib/";
                androidExtension.getSourceSets().getByName("main").getJniLibs().srcDir(nativeAssetsDir);
                //project.android.sourceSets.main.jniLibs.srcDir(nativeAssetsDir)
                // ------------------------------------------------------------
            });
            configurePlugins(project);
            detectLowCompileSdkVersionOrNdkVersion();
            return;
        }
    }

    // compareTo implementation of version strings in the format of ints and periods
    // Requires non null objects.
    // Will not crash on RC candidate strings but considers all RC candidates the same version.
    static int compareVersionStrings(String firstString, String secondString) {
        String[] firstVersion = firstString.split("\\.");
        String[] secondVersion = secondString.split("\\.");

        int commonIndices = Math.min(firstVersion.length, secondVersion.length);

        for (int i = 0; i < commonIndices; i++) {
            String firstAtIndex = firstVersion[i];
            String secondAtIndex = secondVersion[i];
            int firstInt = 0;
            int secondInt = 0;
            try {
                if (firstAtIndex.contains("-")) {
                    firstAtIndex = firstAtIndex.substring(0, firstAtIndex.indexOf('-'));
                }
                firstInt = Integer.parseInt(firstAtIndex);
            } catch (NumberFormatException nfe) {
                System.out.println(nfe); // Or use a logger for better error handling
            }
            try {
                if (secondAtIndex.contains("-")) {
                    secondAtIndex = secondAtIndex.substring(0, secondAtIndex.indexOf('-'));
                }
                secondInt = Integer.parseInt(secondAtIndex);
            } catch (NumberFormatException nfe) {
                System.out.println(nfe); // Or use a logger
            }

            if (firstInt != secondInt) {
                return Integer.compare(firstInt, secondInt);
            }
        }

        // If we got this far then all the common indices are identical, so whichever version is longer must be more recent
        return Integer.compare(firstVersion.length, secondVersion.length);
    }

}
