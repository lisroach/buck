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

package com.facebook.buck.cxx;

import static com.facebook.buck.util.environment.Platform.WINDOWS;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.impl.DependencyAggregation;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.GccCompiler;
import com.facebook.buck.cxx.toolchain.GccPreprocessor;
import com.facebook.buck.cxx.toolchain.InferBuckConfig;
import com.facebook.buck.cxx.toolchain.ToolType;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.args.AddsToRuleKeyFunction;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class CxxCollectAndLogInferDependenciesStepTest {

  private static ProjectFilesystem createFakeFilesystem(
      CanonicalCellName cellName, String fakeRoot) {
    Path fakeRootPath = Paths.get(fakeRoot);
    Preconditions.checkArgument(fakeRootPath.isAbsolute(), "fakeRoot must be an absolute path");
    return new FakeProjectFilesystem(cellName, fakeRootPath);
  }

  private CxxInferCapture createCaptureRule(
      BuildTarget buildTarget, ProjectFilesystem filesystem, InferBuckConfig inferBuckConfig) {
    class FrameworkPathFunction implements AddsToRuleKeyFunction<FrameworkPath, Optional<Path>> {

      @Override
      public Optional<Path> apply(FrameworkPath input) {
        return Optional.of(Paths.get("test", "framework", "path", input.toString()));
      }
    }
    AddsToRuleKeyFunction<FrameworkPath, Optional<Path>> defaultFrameworkPathSearchPathFunction =
        new FrameworkPathFunction();

    SourcePath preprocessor = FakeSourcePath.of(filesystem, "preprocessor");
    Tool preprocessorTool = new CommandTool.Builder().addInput(preprocessor).build();

    PreprocessorDelegate preprocessorDelegate =
        new PreprocessorDelegate(
            CxxPlatformUtils.DEFAULT_PLATFORM.getHeaderVerification(),
            FakeSourcePath.of("whatever"),
            new GccPreprocessor(preprocessorTool),
            PreprocessorFlags.builder().build(),
            defaultFrameworkPathSearchPathFunction,
            /* leadingIncludePaths */ Optional.empty(),
            ImmutableList.of(
                new DependencyAggregation(
                    buildTarget.withFlavors(InternalFlavor.of("deps")),
                    filesystem,
                    ImmutableList.of())),
            ImmutableSortedSet.of());

    CompilerDelegate cd =
        new CompilerDelegate(
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
            new GccCompiler(
                new HashedFileTool(PathSourcePath.of(filesystem, Paths.get("compiler"))),
                ToolType.CXX,
                false),
            CxxToolFlags.of(),
            Optional.empty());

    return new CxxInferCapture(
        buildTarget,
        filesystem,
        new TestActionGraphBuilder(),
        ImmutableSortedSet.of(),
        cd,
        CxxToolFlags.of(),
        CxxToolFlags.of(),
        FakeSourcePath.of("src.c"),
        CxxSource.Type.C,
        Optional.empty(),
        "src.o",
        preprocessorDelegate,
        inferBuckConfig,
        false);
  }

  @Test
  public void testStepWritesNoCellTokenInFileWhenCellIsAbsent() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    ProjectFilesystem filesystem =
        createFakeFilesystem(CanonicalCellName.rootCell(), "/Users/user/src");

    BuildTarget testBuildTarget =
        BuildTargetFactory.newInstance("//target:short")
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.getFlavor());

    InferBuckConfig inferBuckConfig = new InferBuckConfig(FakeBuckConfig.empty());

    CxxInferCapture captureRule = createCaptureRule(testBuildTarget, filesystem, inferBuckConfig);
    CxxInferCaptureTransitive captureTransRule =
        new CxxInferCaptureTransitive(testBuildTarget, filesystem, ImmutableSet.of(captureRule));

    Path outputFile = Paths.get("infer-deps.txt");
    CxxCollectAndLogInferDependenciesStep step =
        CxxCollectAndLogInferDependenciesStep.fromCaptureTransitiveRule(
            captureTransRule, filesystem, outputFile);

    StepExecutionContext executionContext = TestExecutionContext.newInstance();
    int exitCode = step.execute(executionContext).getExitCode();
    assertThat(exitCode, is(StepExecutionResults.SUCCESS.getExitCode()));

    String expectedOutput =
        InferLogLine.fromBuildTarget(
                testBuildTarget, captureRule.getAbsolutePathToOutput().getPath())
            .toString();

    assertEquals(expectedOutput + "\n", filesystem.readFileIfItExists(outputFile).get());
  }

  @Test
  public void testStepWritesSingleCellTokenInFile() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    ProjectFilesystem filesystem =
        createFakeFilesystem(
            CanonicalCellName.unsafeOf(Optional.of("cellname")), "/Users/user/src");

    BuildTarget testBuildTarget =
        BuildTargetFactory.newInstance("cellname//target:short")
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.getFlavor());

    InferBuckConfig inferBuckConfig = new InferBuckConfig(FakeBuckConfig.empty());

    CxxInferCapture captureRule = createCaptureRule(testBuildTarget, filesystem, inferBuckConfig);
    CxxInferCaptureTransitive captureTransRule =
        new CxxInferCaptureTransitive(testBuildTarget, filesystem, ImmutableSet.of(captureRule));

    Path outputFile = Paths.get("infer-deps.txt");
    CxxCollectAndLogInferDependenciesStep step =
        CxxCollectAndLogInferDependenciesStep.fromCaptureTransitiveRule(
            captureTransRule, filesystem, outputFile);

    StepExecutionContext executionContext = TestExecutionContext.newInstance();
    int exitCode = step.execute(executionContext).getExitCode();
    assertThat(exitCode, is(StepExecutionResults.SUCCESS.getExitCode()));

    String expectedOutput =
        InferLogLine.fromBuildTarget(
                testBuildTarget, captureRule.getAbsolutePathToOutput().getPath())
            .toString();

    assertEquals(expectedOutput + "\n", filesystem.readFileIfItExists(outputFile).get());
  }

  @Test
  public void testStepWritesTwoCellTokensInFile() throws Exception {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    // filesystem, buildTarget and buildRuleParams for first cell (analysis)
    ProjectFilesystem filesystem1 =
        createFakeFilesystem(
            CanonicalCellName.unsafeOf(Optional.of("cell1")), "/Users/user/cell_one");
    BuildTarget buildTarget1 =
        BuildTargetFactory.newInstance("cell1//target/in_cell_one:short")
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.getFlavor());

    // filesystem, buildTarget and buildRuleParams for second cell (capture)
    ProjectFilesystem filesystem2 =
        createFakeFilesystem(
            CanonicalCellName.unsafeOf(Optional.of("cell2")), "/Users/user/cell_two");
    BuildTarget buildTarget2 =
        BuildTargetFactory.newInstance("cell2//target/in_cell_two:short2")
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_NO_DEPS.getFlavor());

    InferBuckConfig inferBuckConfig = new InferBuckConfig(FakeBuckConfig.empty());

    CxxInferCapture captureRule2 = createCaptureRule(buildTarget2, filesystem2, inferBuckConfig);

    CxxInferCapture captureRule1 = createCaptureRule(buildTarget1, filesystem1, inferBuckConfig);
    CxxInferCaptureTransitive captureTransRule =
        new CxxInferCaptureTransitive(
            buildTarget1, filesystem1, ImmutableSet.of(captureRule1, captureRule2));

    Path outputFile = Paths.get("infer-deps.txt");
    CxxCollectAndLogInferDependenciesStep step =
        CxxCollectAndLogInferDependenciesStep.fromCaptureTransitiveRule(
            captureTransRule, filesystem1, outputFile);

    StepExecutionContext executionContext = TestExecutionContext.newInstance();
    int exitCode = step.execute(executionContext).getExitCode();
    assertThat(exitCode, is(StepExecutionResults.SUCCESS.getExitCode()));

    String expectedOutput =
        InferLogLine.fromBuildTarget(buildTarget1, captureRule1.getAbsolutePathToOutput().getPath())
            + "\n"
            + InferLogLine.fromBuildTarget(
                buildTarget2, captureRule2.getAbsolutePathToOutput().getPath());

    assertEquals(expectedOutput + "\n", filesystem1.readFileIfItExists(outputFile).get());
  }

  @Test
  public void testStepWritesOneCellTokenInFileWhenOneCellIsAbsent() throws Exception {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    // filesystem, buildTarget and buildRuleParams for first, unnamed cell (analysis)
    ProjectFilesystem filesystem1 =
        createFakeFilesystem(CanonicalCellName.rootCell(), "/Users/user/default_cell");
    BuildTarget buildTarget1 =
        BuildTargetFactory.newInstance("//target/in_default_cell:short")
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.getFlavor());

    // filesystem, buildTarget and buildRuleParams for second cell (capture)
    ProjectFilesystem filesystem2 =
        createFakeFilesystem(
            CanonicalCellName.unsafeOf(Optional.of("cell2")), "/Users/user/cell_two");
    BuildTarget buildTarget2 =
        BuildTargetFactory.newInstance("cell2//target/in_cell_two:short2")
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_NO_DEPS.getFlavor());

    InferBuckConfig inferBuckConfig = new InferBuckConfig(FakeBuckConfig.empty());

    CxxInferCapture captureRule2 = createCaptureRule(buildTarget2, filesystem2, inferBuckConfig);

    CxxInferCapture captureRule1 = createCaptureRule(buildTarget1, filesystem1, inferBuckConfig);
    CxxInferCaptureTransitive captureTransRule =
        new CxxInferCaptureTransitive(
            buildTarget1, filesystem1, ImmutableSet.of(captureRule1, captureRule2));

    Path outputFile = Paths.get("infer-deps.txt");
    CxxCollectAndLogInferDependenciesStep step =
        CxxCollectAndLogInferDependenciesStep.fromCaptureTransitiveRule(
            captureTransRule, filesystem1, outputFile);

    StepExecutionContext executionContext = TestExecutionContext.newInstance();
    int exitCode = step.execute(executionContext).getExitCode();
    assertThat(exitCode, is(StepExecutionResults.SUCCESS.getExitCode()));

    String expectedOutput =
        InferLogLine.fromBuildTarget(buildTarget1, captureRule1.getAbsolutePathToOutput().getPath())
            + "\n"
            + InferLogLine.fromBuildTarget(
                buildTarget2, captureRule2.getAbsolutePathToOutput().getPath());

    assertEquals(expectedOutput + "\n", filesystem1.readFileIfItExists(outputFile).get());
  }
}
