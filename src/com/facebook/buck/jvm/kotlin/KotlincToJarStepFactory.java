/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.kotlin;

import static com.facebook.buck.jvm.java.JavaPaths.SRC_ZIP;
import static com.google.common.base.Preconditions.checkArgument;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.file.FileExtensionMatcher;
import com.facebook.buck.io.file.GlobPatternMatcher;
import com.facebook.buck.io.file.PathMatcher;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.javacd.model.FilesystemParams;
import com.facebook.buck.javacd.model.ResolvedJavacOptions.JavacPluginJsr199Fields;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.core.BuildTargetValueExtraParams;
import com.facebook.buck.jvm.java.BuildContextAwareExtraParams;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.CompilerOutputPaths;
import com.facebook.buck.jvm.java.CompilerOutputPathsValue;
import com.facebook.buck.jvm.java.CompilerParameters;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.FilesystemParamsUtils;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacPluginParams;
import com.facebook.buck.jvm.java.JavacToJarStepFactory;
import com.facebook.buck.jvm.java.ResolvedJavac;
import com.facebook.buck.jvm.java.ResolvedJavacPluginProperties;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription.AnnotationProcessingTool;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.CopyIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MakeCleanDirectoryIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MkdirIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.ZipIsolatedStep;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Factory that creates Kotlin related compile build steps. */
public class KotlincToJarStepFactory extends CompileToJarStepFactory<BuildContextAwareExtraParams> {

  private static final String PLUGIN = "-P";
  private static final String APT_MODE = "aptMode=";
  private static final String X_PLUGIN_ARG = "-Xplugin=";
  private static final String KAPT3_PLUGIN = "plugin:org.jetbrains.kotlin.kapt3:";
  private static final String AP_CLASSPATH_ARG = KAPT3_PLUGIN + "apclasspath=";
  private static final String AP_PROCESSORS_ARG = KAPT3_PLUGIN + "processors=";
  // output path for generated sources;
  private static final String SOURCES_ARG = KAPT3_PLUGIN + "sources=";
  private static final String CLASSES_ARG = KAPT3_PLUGIN + "classes=";
  // output path for java stubs;
  private static final String STUBS_ARG = KAPT3_PLUGIN + "stubs=";
  private static final String LIGHT_ANALYSIS = KAPT3_PLUGIN + "useLightAnalysis=";
  private static final String CORRECT_ERROR_TYPES = KAPT3_PLUGIN + "correctErrorTypes=";
  private static final String JAVAC_ARG = KAPT3_PLUGIN + "javacArguments=";
  private static final String AP_OPTIONS = KAPT3_PLUGIN + "apoptions=";
  private static final String AP_STATS_REPORT_ARG = KAPT3_PLUGIN + "dumpProcessorTimings=";
  private static final String KAPT_GENERATED = "kapt.kotlin.generated";
  private static final String MODULE_NAME = "-module-name";
  private static final String NO_STDLIB = "-no-stdlib";
  private static final String NO_REFLECT = "-no-reflect";
  private static final String VERBOSE = "-verbose";

  private static final PathMatcher KOTLIN_PATH_MATCHER = FileExtensionMatcher.of("kt");
  private static final PathMatcher SRC_ZIP_MATCHER = GlobPatternMatcher.of("**.src.zip");
  public static final String AP_STATS_REPORT_FILE = "ap_stats.report";

  @AddToRuleKey private final JavacOptions javacOptions;
  @AddToRuleKey private final Kotlinc kotlinc;
  @AddToRuleKey private final ImmutableList<String> extraKotlincArguments;
  @AddToRuleKey private final SourcePath standardLibraryClasspath;
  @AddToRuleKey private final SourcePath annotationProcessingClassPath;

  @AddToRuleKey
  private final ImmutableMap<SourcePath, ImmutableMap<String, String>> kotlinCompilerPlugins;

  @AddToRuleKey private final ImmutableList<SourcePath> friendPaths;
  @AddToRuleKey private final AnnotationProcessingTool annotationProcessingTool;
  @AddToRuleKey private final Optional<String> jvmTarget;
  @AddToRuleKey private final ExtraClasspathProvider extraClasspathProvider;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> kotlinHomeLibraries;
  @AddToRuleKey private final boolean shouldGenerateAnnotationProcessingStats;

  KotlincToJarStepFactory(
      Kotlinc kotlinc,
      ImmutableSortedSet<SourcePath> kotlinHomeLibraries,
      SourcePath standardLibraryClasspath,
      SourcePath annotationProcessingClassPath,
      ImmutableList<String> extraKotlincArguments,
      ImmutableMap<SourcePath, ImmutableMap<String, String>> kotlinCompilerPlugins,
      ImmutableList<SourcePath> friendPaths,
      AnnotationProcessingTool annotationProcessingTool,
      Optional<String> jvmTarget,
      ExtraClasspathProvider extraClasspathProvider,
      JavacOptions javacOptions,
      boolean withDownwardApi,
      boolean shouldGenerateAnnotationProcessingStats) {
    super(CompileToJarStepFactory.hasAnnotationProcessing(javacOptions), withDownwardApi);
    this.javacOptions = javacOptions;
    this.kotlinc = kotlinc;
    this.kotlinHomeLibraries = kotlinHomeLibraries;
    this.standardLibraryClasspath = standardLibraryClasspath;
    this.annotationProcessingClassPath = annotationProcessingClassPath;
    this.extraKotlincArguments = extraKotlincArguments;
    this.kotlinCompilerPlugins = kotlinCompilerPlugins;
    this.friendPaths = friendPaths;
    this.annotationProcessingTool = annotationProcessingTool;
    this.jvmTarget = jvmTarget;
    this.extraClasspathProvider = extraClasspathProvider;
    this.shouldGenerateAnnotationProcessingStats = shouldGenerateAnnotationProcessingStats;
  }

  @Override
  public void createCompileStep(
      FilesystemParams filesystemParams,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      BuildTargetValue invokingRule,
      CompilerOutputPathsValue compilerOutputPathsValue,
      CompilerParameters parameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      ResolvedJavac resolvedJavac,
      BuildContextAwareExtraParams extraParams) {

    BuildTargetValueExtraParams buildTargetValueExtraParams =
        invokingRule
            .getExtraParams()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Kotlin compilation to jar factory has to have build target extra params"));

    AbsPath rootPath = getRootPath(filesystemParams);
    BuckPaths buckPaths = buildTargetValueExtraParams.getBuckPaths();
    ImmutableSet<PathMatcher> ignoredPaths =
        FilesystemParamsUtils.getIgnoredPaths(filesystemParams);

    BuildContext buildContext = extraParams.getBuildContext();
    SourcePathResolverAdapter resolver = buildContext.getSourcePathResolver();

    ImmutableSortedSet<RelPath> declaredClasspathEntries = parameters.getClasspathEntries();
    ImmutableSortedSet<RelPath> sourceFilePaths = parameters.getSourceFilePaths();
    RelPath outputDirectory = parameters.getOutputPaths().getClassesDir();
    Path pathToSrcsList = parameters.getOutputPaths().getPathToSourcesList().getPath();

    boolean generatingCode = !javacOptions.getJavaAnnotationProcessorParams().isEmpty();
    boolean hasKotlinSources =
        sourceFilePaths.stream().anyMatch(KOTLIN_PATH_MATCHER::matches)
            || sourceFilePaths.stream().anyMatch(SRC_ZIP_MATCHER::matches);

    ImmutableSortedSet.Builder<RelPath> sourceBuilder =
        ImmutableSortedSet.orderedBy(RelPath.comparator()).addAll(sourceFilePaths);

    // Only invoke kotlinc if we have kotlin or src zip files.
    if (hasKotlinSources) {
      RelPath stubsOutput = getAnnotationPath(buckPaths, invokingRule, "__%s_stubs__");
      RelPath sourcesOutput = getAnnotationPath(buckPaths, invokingRule, "__%s_sources__");
      RelPath classesOutput = getAnnotationPath(buckPaths, invokingRule, "__%s_classes__");
      RelPath reportsOutput = getAnnotationPath(buckPaths, invokingRule, "__%s_reports__");
      RelPath kaptGeneratedOutput =
          getAnnotationPath(buckPaths, invokingRule, "__%s_kapt_generated__");
      RelPath kotlincPluginGeneratedOutput =
          getAnnotationPath(buckPaths, invokingRule, "__%s_kotlinc_plugin_generated__");
      RelPath annotationGenFolder = getKaptAnnotationGenPath(buckPaths, invokingRule);
      RelPath genOutputFolder = getGenPath(buckPaths, invokingRule, "__%s_gen_sources__");
      RelPath genOutput =
          getGenPath(buckPaths, invokingRule, "__%s_gen_sources__/generated" + SRC_ZIP);

      // Javac requires that the root directory for generated sources already exist.
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(stubsOutput));
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(classesOutput));
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(kaptGeneratedOutput));
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(kotlincPluginGeneratedOutput));
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(sourcesOutput));
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(annotationGenFolder));
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(genOutputFolder));
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(reportsOutput));

      ImmutableSortedSet.Builder<AbsPath> friendAbsPathsBuilder =
          ImmutableSortedSet.orderedBy(Comparator.comparing(AbsPath::getPath));

      // Currently, kotlinc can't handle commas (`,`) in paths when passed to the `-Xfriend-paths`
      // flag, so if we see a comma, we copy the JAR to a new path w/o one.
      RelPath friendPathScratchDir =
          getScratchPath(buckPaths, invokingRule, "__%s_friend_path_jars__");
      friendPathScratchDir =
          friendPathScratchDir
              .getParent()
              .resolveRel(friendPathScratchDir.getFileName().toString().replace(",", "__"));
      Map<AbsPath, AbsPath> remappedClasspathEntries = new HashMap<>();

      for (SourcePath friendPath : friendPaths) {
        AbsPath friendAbsPath = resolver.getAbsolutePath(friendPath);
        // If this path has a comma, copy to a new location that doesn't have one.
        if (friendAbsPath.getPath().toString().contains(",")) {
          if (remappedClasspathEntries.isEmpty()) {
            steps.add(MkdirIsolatedStep.of(friendPathScratchDir));
          }
          AbsPath dest =
              rootPath.resolve(friendPathScratchDir.resolve(friendAbsPath.getFileName()));
          steps.add(
              CopyIsolatedStep.of(friendAbsPath.getPath(), dest.getPath(), CopySourceMode.FILE));
          remappedClasspathEntries.put(friendAbsPath, dest);
          friendAbsPath = dest;
        }
        friendAbsPathsBuilder.add(friendAbsPath);
      }
      ImmutableSortedSet<AbsPath> friendAbsPaths = friendAbsPathsBuilder.build();

      ImmutableSortedSet<AbsPath> allClasspaths =
          ImmutableSortedSet.orderedBy(Comparator.comparing(AbsPath::getPath))
              .addAll(
                  RichStream.from(extraClasspathProvider.getExtraClasspath())
                      .map(p -> remappedClasspathEntries.getOrDefault(p, p))
                      .iterator())
              .addAll(
                  RichStream.from(declaredClasspathEntries)
                      .map(rootPath::resolve)
                      .map(AbsPath::normalize)
                      .map(p -> remappedClasspathEntries.getOrDefault(p, p))
                      .iterator())
              .addAll(
                  RichStream.from(kotlinHomeLibraries)
                      .map(x -> resolver.getAbsolutePath(x))
                      .iterator())
              .build();

      String friendPathsArg = getFriendsPath(friendAbsPaths);
      String moduleName = getModuleName(invokingRule);

      ImmutableList.Builder<String> annotationProcessingOptionsBuilder = ImmutableList.builder();
      Builder<IsolatedStep> postKotlinCompilationSteps = ImmutableList.builder();

      if (generatingCode && annotationProcessingTool.equals(AnnotationProcessingTool.KAPT)) {
        ImmutableList<String> annotationProcessors =
            ImmutableList.copyOf(
                javacOptions.getJavaAnnotationProcessorParams().getPluginProperties().stream()
                    .map(ResolvedJavacPluginProperties::getProcessorNames)
                    .flatMap(Set::stream)
                    .map(name -> AP_PROCESSORS_ARG + name)
                    .collect(Collectors.toList()));

        ImmutableList<String> apClassPaths =
            ImmutableList.copyOf(
                javacOptions.getJavaAnnotationProcessorParams().getPluginProperties().stream()
                    .map(p -> p.getJavacPluginJsr199Fields(rootPath))
                    .map(JavacPluginJsr199Fields::getClasspathList)
                    .flatMap(List::stream)
                    .map(KotlincToJarStepFactory::toURL)
                    .map(url -> AP_CLASSPATH_ARG + urlToFile(url))
                    .collect(Collectors.toList()));

        ImmutableMap.Builder<String, String> apOptions = new ImmutableMap.Builder<>();
        ImmutableSortedSet<String> javacAnnotationProcessorParams =
            javacOptions.getJavaAnnotationProcessorParams().getParameters();
        for (String param : javacAnnotationProcessorParams) {
          String[] splitParam = param.split("=");
          Preconditions.checkState(splitParam.length == 2);
          apOptions.put(splitParam[0], splitParam[1]);
        }

        SourcePathResolverAdapter sourcePathResolver = buildContext.getSourcePathResolver();
        Path annotationProcessorPath =
            sourcePathResolver.getAbsolutePath(annotationProcessingClassPath).getPath();
        Path standardLibraryPath =
            sourcePathResolver.getAbsolutePath(standardLibraryClasspath).getPath();
        Path annotationProcessorsStatsFilePath =
            reportsOutput.getPath().resolve(AP_STATS_REPORT_FILE);

        Builder<String> kaptPluginOptionsBuilder =
            ImmutableList.<String>builder()
                .add(AP_CLASSPATH_ARG + annotationProcessorPath)
                .add(AP_CLASSPATH_ARG + standardLibraryPath)
                .addAll(apClassPaths)
                .addAll(annotationProcessors)
                .add(SOURCES_ARG + rootPath.resolve(sourcesOutput))
                .add(CLASSES_ARG + rootPath.resolve(classesOutput))
                .add(STUBS_ARG + rootPath.resolve(stubsOutput))
                .add(
                    AP_OPTIONS
                        + encodeKaptApOptions(
                            apOptions.build(), rootPath.resolve(kaptGeneratedOutput).toString()))
                .add(JAVAC_ARG + encodeOptions(getJavacArguments()))
                .add(LIGHT_ANALYSIS + "true") // TODO: Provide value as argument
                .add(CORRECT_ERROR_TYPES + "true");

        if (shouldGenerateAnnotationProcessingStats) {
          kaptPluginOptionsBuilder.add(AP_STATS_REPORT_ARG + annotationProcessorsStatsFilePath);
        }

        annotationProcessingOptionsBuilder
            .add(X_PLUGIN_ARG + annotationProcessorPath)
            .add(PLUGIN)
            .add(
                KAPT3_PLUGIN
                    + APT_MODE
                    + "compile,"
                    + Joiner.on(",").join(kaptPluginOptionsBuilder.build()));

        postKotlinCompilationSteps.add(
            CopyIsolatedStep.forDirectory(
                sourcesOutput, annotationGenFolder, CopySourceMode.DIRECTORY_CONTENTS_ONLY));
        postKotlinCompilationSteps.add(
            CopyIsolatedStep.forDirectory(
                classesOutput, annotationGenFolder, CopySourceMode.DIRECTORY_CONTENTS_ONLY));
        postKotlinCompilationSteps.add(
            CopyIsolatedStep.forDirectory(
                kaptGeneratedOutput, annotationGenFolder, CopySourceMode.DIRECTORY_CONTENTS_ONLY));

        if (shouldGenerateAnnotationProcessingStats) {
          postKotlinCompilationSteps.add(
              new KaptStatsReportParseStep(
                  annotationProcessorsStatsFilePath, invokingRule, buildContext.getEventBus()));
        }

        postKotlinCompilationSteps.add(
            ZipIsolatedStep.of(
                rootPath,
                genOutput.getPath(),
                ignoredPaths,
                ImmutableSet.of(),
                false,
                ZipCompressionLevel.DEFAULT,
                annotationGenFolder.getPath()));

        // Generated classes should be part of the output. This way generated files
        // such as META-INF dirs will also be added to the final jar.
        postKotlinCompilationSteps.add(
            CopyIsolatedStep.forDirectory(
                classesOutput.getPath(),
                outputDirectory.getPath(),
                CopySourceMode.DIRECTORY_CONTENTS_ONLY));

        sourceBuilder.add(genOutput);
      }

      ImmutableList.Builder<String> extraArguments =
          ImmutableList.<String>builder()
              .add(friendPathsArg)
              .addAll(
                  getKotlinCompilerPluginsArgs(
                      resolver, rootPath.resolve(kotlincPluginGeneratedOutput).toString()))
              .addAll(annotationProcessingOptionsBuilder.build())
              .add(MODULE_NAME)
              .add(moduleName)
              .add(NO_STDLIB)
              .add(NO_REFLECT);

      jvmTarget.ifPresent(
          target -> {
            extraArguments.add("-jvm-target");
            extraArguments.add(target);
          });

      extraArguments.addAll(extraKotlincArguments);

      steps.add(
          new KotlincStep(
              invokingRule,
              outputDirectory.getPath(),
              sourceFilePaths,
              pathToSrcsList,
              allClasspaths,
              kotlinc,
              extraArguments.build(),
              ImmutableList.of(VERBOSE),
              parameters.getOutputPaths(),
              withDownwardApi,
              parameters.shouldTrackClassUsage(),
              RelPath.get(filesystemParams.getConfiguredBuckOut().getPath()),
              cellToPathMappings));

      steps.addAll(postKotlinCompilationSteps.build());
    }

    final JavacOptions finalJavacOptions;

    switch (annotationProcessingTool) {
      case KAPT:
        // If kapt was never invoked then do annotation processing with javac.
        finalJavacOptions =
            hasKotlinSources
                ? javacOptions.withJavaAnnotationProcessorParams(JavacPluginParams.EMPTY)
                : javacOptions;
        break;

      case JAVAC:
        finalJavacOptions = javacOptions;
        break;

      default:
        throw new IllegalStateException(
            "Unexpected annotationProcessingTool " + annotationProcessingTool);
    }

    // Note that this filters out only .kt files, so this keeps both .java and .src.zip files.
    ImmutableSortedSet<RelPath> javaSourceFiles =
        sourceBuilder.build().stream()
            .filter(input -> !KOTLIN_PATH_MATCHER.matches(input))
            .collect(ImmutableSortedSet.toImmutableSortedSet(RelPath.comparator()));

    CompilerParameters javacParameters =
        CompilerParameters.builder()
            .from(parameters)
            .setClasspathEntries(
                ImmutableSortedSet.orderedBy(RelPath.comparator())
                    .add(outputDirectory)
                    .addAll(
                        RichStream.from(extraClasspathProvider.getExtraClasspath())
                            .map(rootPath::relativize)
                            .iterator())
                    .addAll(declaredClasspathEntries)
                    .build())
            .setSourceFilePaths(javaSourceFiles)
            .build();

    JavacToJarStepFactory javacToJarStepFactory =
        new JavacToJarStepFactory(finalJavacOptions, extraClasspathProvider, withDownwardApi);

    javacToJarStepFactory.createCompileStep(
        filesystemParams,
        cellToPathMappings,
        invokingRule,
        compilerOutputPathsValue,
        javacParameters,
        steps,
        buildableContext,
        resolvedJavac,
        javacToJarStepFactory.createExtraParams(resolver, rootPath));
  }

  @Override
  protected void recordDepFileIfNecessary(
      CompilerOutputPathsValue compilerOutputPathsValue,
      BuildTargetValue buildTargetValue,
      CompilerParameters compilerParameters,
      BuildableContext buildableContext) {
    super.recordDepFileIfNecessary(
        compilerOutputPathsValue, buildTargetValue, compilerParameters, buildableContext);
    if (compilerParameters.shouldTrackClassUsage()) {
      CompilerOutputPaths outputPath =
          compilerOutputPathsValue.getByType(buildTargetValue.getType());
      RelPath depFilePath =
          CompilerOutputPaths.getKotlinDepFilePath(outputPath.getOutputJarDirPath());
      buildableContext.recordArtifact(depFilePath.getPath());
    }
  }

  /**
   * Safely converts a URL to a File path. Use this instead of {@link URL#getFile} to ensure that
   * htmlencoded literals are not present in the file path.
   */
  private static String urlToFile(URL url) {
    try {
      return Paths.get(url.toURI()).toFile().getPath();
    } catch (URISyntaxException e) {
      // In case of error, fall back to the original implementation.
      return url.getFile();
    }
  }

  private static URL toURL(JavacPluginJsr199Fields.URL url) {
    try {
      return new URL(url.getValue());
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected Optional<String> getBootClasspath() {
    return javacOptions.withBootclasspathFromContext(extraClasspathProvider).getBootclasspath();
  }

  @Override
  public ImmutableList<RelPath> getDepFilePaths(
      ProjectFilesystem filesystem, BuildTarget buildTarget) {
    BuckPaths buckPaths = filesystem.getBuckPaths();
    RelPath outputPath = CompilerOutputPaths.of(buildTarget, buckPaths).getOutputJarDirPath();

    // Java dependencies file path is needed for Kotlin modules
    // because some Java code can be generated during the build.
    return ImmutableList.of(
        CompilerOutputPaths.getJavaDepFilePath(outputPath),
        CompilerOutputPaths.getKotlinDepFilePath(outputPath));
  }

  private String encodeKaptApOptions(Map<String, String> kaptApOptions, String kaptGeneratedPath) {
    Map<String, String> kaptApOptionsToEncode = new HashMap<>();
    kaptApOptionsToEncode.put(KAPT_GENERATED, kaptGeneratedPath);
    kaptApOptionsToEncode.putAll(kaptApOptions);

    return encodeOptions(kaptApOptionsToEncode);
  }

  private String encodeOptions(Map<String, String> options) {
    try (ByteArrayOutputStream os = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(os)) {

      oos.writeInt(options.size());
      for (Map.Entry<String, String> entry : options.entrySet()) {
        oos.writeUTF(entry.getKey());
        oos.writeUTF(entry.getValue());
      }

      oos.flush();
      return Base64.getEncoder().encodeToString(os.toByteArray());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private String getFriendsPath(ImmutableCollection<AbsPath> friendPathsSourcePaths) {
    if (friendPathsSourcePaths.isEmpty()) {
      return "";
    }

    // https://youtrack.jetbrains.com/issue/KT-29933
    ImmutableSortedSet<String> absoluteFriendPaths =
        friendPathsSourcePaths.stream()
            .map(AbsPath::toString)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));

    return "-Xfriend-paths="
        + absoluteFriendPaths.stream().reduce("", (path1, path2) -> path1 + "," + path2);
  }

  private ImmutableList<String> getKotlinCompilerPluginsArgs(
      SourcePathResolverAdapter sourcePathResolverAdapter, String outputDir) {
    ImmutableList.Builder<String> pluginArgs = ImmutableList.builder();
    for (SourcePath pluginPath : kotlinCompilerPlugins.keySet()) {
      // Add plugin basic string, e.g. "-Xplugins=<pluginPath>"
      pluginArgs.add(getKotlincPluginBasicString(sourcePathResolverAdapter, pluginPath));

      // If plugin options exist, add plugin option string,
      // e.g. "-P" and "<optionKey>=<optionValue>,<optionKey2>=<optionValue2>,..."
      ImmutableMap<String, String> pluginOptions = kotlinCompilerPlugins.get(pluginPath);
      if (pluginOptions != null && !pluginOptions.isEmpty()) {
        ImmutableList.Builder<String> pluginOptionStrings = ImmutableList.builder();
        for (String pluginOptionKey : pluginOptions.keySet()) {
          String pluginOptionValue = pluginOptions.get(pluginOptionKey);

          // When value is "_codegen_dir_", it means it's asking buck to provide kotlin compiler
          // plugin output dir
          if (pluginOptionValue.equals("__codegen_dir__")) {
            pluginOptionValue = outputDir;
          }

          pluginOptionStrings.add(pluginOptionKey + "=" + pluginOptionValue);
        }
        String pluginOptionString = Joiner.on(",").join(pluginOptionStrings.build());
        pluginArgs.add(PLUGIN).add(pluginOptionString);
      }
    }
    return pluginArgs.build();
  }

  /**
   * Ideally, we would not use getAbsolutePath() here, but getRelativePath() does not appear to work
   * correctly if path is a BuildTargetSourcePath in a different cell than the kotlin_library() rule
   * being defined.
   */
  private String getKotlincPluginBasicString(
      SourcePathResolverAdapter sourcePathResolverAdapter, SourcePath path) {
    return X_PLUGIN_ARG + sourcePathResolverAdapter.getAbsolutePath(path).toString();
  }

  private String getModuleName(BuildTargetValue invokingRule) {
    BuildTargetValueExtraParams extraParams = getBuildTargetValueExtraParams(invokingRule);
    return extraParams.getCellRelativeBasePath().toString().replace('/', '.')
        + "."
        + extraParams.getShortName();
  }

  private Map<String, String> getJavacArguments() {
    Map<String, String> arguments = new HashMap<>();
    if (jvmTarget.isPresent()) {
      arguments.put("-source", jvmTarget.get());
      arguments.put("-target", jvmTarget.get());
    }
    return arguments;
  }

  public static RelPath getKaptAnnotationGenPath(BuckPaths buckPaths, BuildTarget buildTarget) {
    return getKaptAnnotationGenPath(
        buckPaths, BuildTargetValue.withExtraParams(buildTarget, buckPaths));
  }

  private static RelPath getKaptAnnotationGenPath(
      BuckPaths buckPaths, BuildTargetValue buildTargetValue) {
    BuildTargetValueExtraParams extraParams = getBuildTargetValueExtraParams(buildTargetValue);
    String format = extraParams.isFlavored() ? "%s" : "%s__";
    return getGenPath(buckPaths, buildTargetValue, format).resolveRel("__generated__");
  }

  /** Returns annotation path for the given {@code target} and {@code format} */
  public static RelPath getAnnotationPath(
      BuckPaths buckPaths, BuildTargetValue target, String format) {
    checkArgument(!format.startsWith("/"), "format string should not start with a slash");
    return getRelativePath(target, format, buckPaths.getAnnotationDir());
  }

  /** Returns `gen` directory path for the given {@code target} and {@code format} */
  public static RelPath getGenPath(BuckPaths buckPaths, BuildTargetValue target, String format) {
    checkArgument(!format.startsWith("/"), "format string should not start with a slash");
    return getRelativePath(target, format, buckPaths.getGenDir());
  }

  /** Returns `gen` directory path for the given {@code target} and {@code format} */
  public static RelPath getScratchPath(
      BuckPaths buckPaths, BuildTargetValue target, String format) {
    checkArgument(!format.startsWith("/"), "format string should not start with a slash");
    return getRelativePath(target, format, buckPaths.getScratchDir());
  }

  private static RelPath getRelativePath(
      BuildTargetValue target, String format, RelPath directory) {
    return directory.resolve(getBasePath(target, format));
  }

  private static ForwardRelPath getBasePath(BuildTargetValue target, String format) {
    checkArgument(!format.startsWith("/"), "format string should not start with a slash");
    BuildTargetValueExtraParams extraParams = getBuildTargetValueExtraParams(target);
    return extraParams
        .getBasePathForBaseName()
        .resolve(
            BuildTargetPaths.formatLastSegment(format, extraParams.getShortNameAndFlavorPostfix()));
  }

  private static BuildTargetValueExtraParams getBuildTargetValueExtraParams(
      BuildTargetValue invokingRule) {
    return invokingRule
        .getExtraParams()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Kotlin compilation to jar factory has to have build target extra params"));
  }
}
