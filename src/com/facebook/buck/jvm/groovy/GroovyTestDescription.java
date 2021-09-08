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

package com.facebook.buck.jvm.groovy;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.DefaultJavaLibraryRules;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaCDBuckConfig;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.test.config.TestBuckConfig;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.Optional;
import java.util.function.Function;

/** Description for groovy_test. */
public class GroovyTestDescription
    implements DescriptionWithTargetGraph<GroovyTestDescriptionArg>,
        ImplicitDepsInferringDescription<GroovyTestDescriptionArg> {

  private final ToolchainProvider toolchainProvider;
  private final GroovyBuckConfig groovyBuckConfig;
  private final JavaBuckConfig javaBuckConfig;
  private final JavaCDBuckConfig javaCDBuckConfig;
  private final DownwardApiConfig downwardApiConfig;
  private final JavacFactory javacFactory;
  private final Function<TargetConfiguration, JavaOptions> javaOptionsForTests;
  private final Function<TargetConfiguration, JavaOptions> java11OptionsForTests;

  public GroovyTestDescription(
      ToolchainProvider toolchainProvider,
      GroovyBuckConfig groovyBuckConfig,
      JavaBuckConfig javaBuckConfig,
      JavaCDBuckConfig javaCDBuckConfig,
      DownwardApiConfig downwardApiConfig) {
    this.toolchainProvider = toolchainProvider;
    this.groovyBuckConfig = groovyBuckConfig;
    this.javaBuckConfig = javaBuckConfig;
    this.javaCDBuckConfig = javaCDBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
    this.javaOptionsForTests = JavaOptionsProvider.getDefaultJavaOptionsForTests(toolchainProvider);
    this.java11OptionsForTests =
        JavaOptionsProvider.getDefaultJava11OptionsForTests(toolchainProvider);
    this.javacFactory = JavacFactory.getDefault(toolchainProvider);
  }

  @Override
  public Class<GroovyTestDescriptionArg> getConstructorArgType() {
    return GroovyTestDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      GroovyTestDescriptionArg args) {
    BuildTarget testsLibraryBuildTarget =
        buildTarget.withAppendedFlavors(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR);
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    CellPathResolver cellRoots = context.getCellPathResolver();

    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    JavacOptions javacOptions =
        JavacOptionsFactory.create(
            toolchainProvider
                .getByName(
                    JavacOptionsProvider.DEFAULT_NAME,
                    buildTarget.getTargetConfiguration(),
                    JavacOptionsProvider.class)
                .getJavacOptions(),
            buildTarget,
            graphBuilder,
            projectFilesystem.getRootPath(),
            args);

    DefaultJavaLibraryRules defaultJavaLibraryRules =
        new DefaultJavaLibraryRules.Builder(
                testsLibraryBuildTarget,
                projectFilesystem,
                context.getToolchainProvider(),
                params,
                graphBuilder,
                new GroovyConfiguredCompilerFactory(
                    groovyBuckConfig, downwardApiConfig, javacFactory),
                javaBuckConfig,
                javaCDBuckConfig,
                downwardApiConfig,
                args,
                context.getCellPathResolver())
            .setJavacOptions(javacOptions)
            .build();

    if (JavaAbis.isAbiTarget(buildTarget)) {
      return defaultJavaLibraryRules.buildAbi();
    }

    JavaLibrary testsLibrary = graphBuilder.addToIndex(defaultJavaLibraryRules.buildLibrary());

    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(
            buildTarget,
            cellRoots.getCellNameResolver(),
            graphBuilder,
            JavaTestDescription.MACRO_EXPANDERS);

    Function<TargetConfiguration, JavaOptions> javaRuntimeConfig =
        javacOptions.getLanguageLevelOptions().getTargetLevel().equals("11")
            ? java11OptionsForTests
            : javaOptionsForTests;
    JavaOptions javaOptions = javaRuntimeConfig.apply(buildTarget.getTargetConfiguration());

    return new JavaTest(
        buildTarget,
        projectFilesystem,
        params.withDeclaredDeps(ImmutableSortedSet.of(testsLibrary)).withoutExtraDeps(),
        testsLibrary,
        Optional.empty(),
        args.getLabels(),
        args.getContacts(),
        args.getTestType().orElse(TestType.JUNIT),
        javacOptions.getLanguageLevelOptions().getTargetLevel(),
        javaOptions.getJavaRuntime(),
        javaOptions.getJavaRuntimeVersion(),
        Lists.transform(args.getVmArgs(), macrosConverter::convert),
        /* nativeLibsEnvironment */ ImmutableMap.of(),
        /* nativeLibsRequiredPaths */ ImmutableSet.of(),
        args.getTestRuleTimeoutMs()
            .map(Optional::of)
            .orElse(
                groovyBuckConfig
                    .getDelegate()
                    .getView(TestBuckConfig.class)
                    .getDefaultTestRuleTimeoutMs()),
        args.getTestCaseTimeoutMs(),
        ImmutableMap.copyOf(Maps.transformValues(args.getEnv(), macrosConverter::convert)),
        args.getRunTestSeparately(),
        args.getForkMode(),
        args.getStdOutLogLevel(),
        args.getStdErrLogLevel(),
        args.getResources(),
        javaBuckConfig
            .getDelegate()
            .getView(TestBuckConfig.class)
            .useRelativePathsInClasspathFile(),
        downwardApiConfig.isEnabledForTests(),
        args.getUseDependencyOrderClasspath()
            .orElse(javaBuckConfig.useDependencyOrderClasspathForTests()));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      GroovyTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {}

  @RuleArg
  interface AbstractGroovyTestDescriptionArg
      extends GroovyLibraryDescription.CoreArg, JavaTestDescription.CoreArg {
    @Override
    default GroovyTestDescriptionArg withDepsQuery(Query query) {
      if (getDepsQuery().equals(Optional.of(query))) {
        return (GroovyTestDescriptionArg) this;
      }
      return GroovyTestDescriptionArg.builder().from(this).setDepsQuery(query).build();
    }
  }
}
