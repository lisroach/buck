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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.impl.DefaultCxxPlatforms;
import com.facebook.buck.cxx.toolchain.impl.StaticUnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.cxx.toolchain.linker.impl.DefaultLinkerProvider;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;

public class CxxPlatformUtils {

  private CxxPlatformUtils() {}

  public static final BuckConfig DEFAULT_BUCK_CONFIG = FakeBuckConfig.empty();

  public static final CxxBuckConfig DEFAULT_CONFIG = new CxxBuckConfig(DEFAULT_BUCK_CONFIG);
  public static final DownwardApiConfig DEFAULT_DOWNWARD_API_CONFIG =
      DownwardApiConfig.of(DEFAULT_BUCK_CONFIG);
  public static final BuildBuckConfig DEFAULT_EXTERNAL_ACTIONS_CONFIG =
      BuildBuckConfig.of(DEFAULT_BUCK_CONFIG);

  public static final Tool DEFAULT_TOOL = new CommandTool.Builder().build();

  private static PreprocessorProvider defaultPreprocessorProvider(ToolType toolType) {
    return new PreprocessorProvider(
        new ConstantToolProvider(DEFAULT_TOOL), CxxToolProvider.Type.GCC, toolType);
  }

  private static CompilerProvider defaultCompilerProvider(ToolType toolType) {
    return new CompilerProvider(
        new ConstantToolProvider(DEFAULT_TOOL), CxxToolProvider.Type.GCC, toolType, false);
  }

  public static LinkerProvider defaultLinkerProvider(LinkerProvider.Type linkerType, Tool tool) {
    return new DefaultLinkerProvider(linkerType, new ConstantToolProvider(tool), true, true);
  }

  public static final DebugPathSanitizer DEFAULT_COMPILER_DEBUG_PATH_SANITIZER =
      new PrefixMapDebugPathSanitizer(".", ImmutableBiMap.of());

  public static final InternalFlavor DEFAULT_PLATFORM_FLAVOR = InternalFlavor.of("default");
  public static final CxxPlatform DEFAULT_PLATFORM =
      CxxPlatform.builder()
          .setFlavor(DEFAULT_PLATFORM_FLAVOR)
          .setAs(defaultCompilerProvider(ToolType.AS))
          .setAspp(defaultPreprocessorProvider(ToolType.ASPP))
          .setCc(defaultCompilerProvider(ToolType.CC))
          .setCpp(defaultPreprocessorProvider(ToolType.CPP))
          .setCxx(defaultCompilerProvider(ToolType.CXX))
          .setCxxpp(defaultPreprocessorProvider(ToolType.CXXPP))
          .setCuda(defaultCompilerProvider(ToolType.CUDA))
          .setCudapp(defaultPreprocessorProvider(ToolType.CUDAPP))
          .setAsm(defaultCompilerProvider(ToolType.ASM))
          .setAsmpp(defaultPreprocessorProvider(ToolType.ASMPP))
          .setLd(defaultLinkerProvider(LinkerProvider.Type.GNU, DEFAULT_TOOL))
          .setStrip(new ConstantToolProvider(DEFAULT_TOOL))
          .setAr(ArchiverProvider.from(new GnuArchiver(DEFAULT_TOOL)))
          .setArchiveContents(ArchiveContents.NORMAL)
          .setRanlib(new ConstantToolProvider(DEFAULT_TOOL))
          .setSymbolNameTool(
              new PosixNmSymbolNameTool(new ConstantToolProvider(DEFAULT_TOOL), false))
          .setSharedLibraryExtension("so")
          .setSharedLibraryVersionedExtensionFormat("so.%s")
          .setStaticLibraryExtension("a")
          .setObjectFileExtension("o")
          .setCompilerDebugPathSanitizer(DEFAULT_COMPILER_DEBUG_PATH_SANITIZER)
          .setHeaderVerification(DEFAULT_CONFIG.getHeaderVerificationOrIgnore())
          .setPublicHeadersSymlinksEnabled(true)
          .setPrivateHeadersSymlinksEnabled(true)
          .build();
  public static final SourcePathResolverAdapter DEFAULT_PATH_RESOLVER =
      new SourcePathResolverAdapter(DefaultSourcePathResolver.from(new TestActionGraphBuilder()));

  public static CxxPlatform buildPlatformWithLdArgs(ImmutableList<String> ldArgs) {
    CommandTool.Builder commandToolBuilder = new CommandTool.Builder();
    for (String ldArg : ldArgs) {
      commandToolBuilder.addArg(ldArg);
    }

    return CxxPlatform.builder()
        .setFlavor(DEFAULT_PLATFORM_FLAVOR)
        .setAs(defaultCompilerProvider(ToolType.AS))
        .setAspp(defaultPreprocessorProvider(ToolType.ASPP))
        .setCc(defaultCompilerProvider(ToolType.CC))
        .setCpp(defaultPreprocessorProvider(ToolType.CPP))
        .setCxx(defaultCompilerProvider(ToolType.CXX))
        .setCxxpp(defaultPreprocessorProvider(ToolType.CXXPP))
        .setCuda(defaultCompilerProvider(ToolType.CUDA))
        .setCudapp(defaultPreprocessorProvider(ToolType.CUDAPP))
        .setAsm(defaultCompilerProvider(ToolType.ASM))
        .setAsmpp(defaultPreprocessorProvider(ToolType.ASMPP))
        .setLd(defaultLinkerProvider(LinkerProvider.Type.GNU, commandToolBuilder.build()))
        .setStrip(new ConstantToolProvider(DEFAULT_TOOL))
        .setAr(ArchiverProvider.from(new GnuArchiver(DEFAULT_TOOL)))
        .setArchiveContents(ArchiveContents.NORMAL)
        .setRanlib(new ConstantToolProvider(DEFAULT_TOOL))
        .setSymbolNameTool(new PosixNmSymbolNameTool(new ConstantToolProvider(DEFAULT_TOOL), false))
        .setSharedLibraryExtension("so")
        .setSharedLibraryVersionedExtensionFormat("so.%s")
        .setStaticLibraryExtension("a")
        .setObjectFileExtension("o")
        .setCompilerDebugPathSanitizer(DEFAULT_COMPILER_DEBUG_PATH_SANITIZER)
        .setHeaderVerification(DEFAULT_CONFIG.getHeaderVerificationOrIgnore())
        .setPublicHeadersSymlinksEnabled(true)
        .setPrivateHeadersSymlinksEnabled(true)
        .build();
  }

  public static final UnresolvedCxxPlatform DEFAULT_UNRESOLVED_PLATFORM =
      new StaticUnresolvedCxxPlatform(DEFAULT_PLATFORM);

  public static final FlavorDomain<UnresolvedCxxPlatform> DEFAULT_PLATFORMS =
      FlavorDomain.of("C/C++ Platform", DEFAULT_UNRESOLVED_PLATFORM);

  public static CxxPlatform build(
      CxxBuckConfig cxxBuckConfig, DownwardApiConfig downwardApiConfig) {
    return DefaultCxxPlatforms.build(Platform.detect(), cxxBuckConfig, downwardApiConfig);
  }

  private static CxxPlatform getDefaultPlatform(Path root) throws IOException {
    Config rawConfig = Configs.createDefaultConfig(root);
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(rawConfig.getRawConfig())
            .setFilesystem(TestProjectFilesystems.createProjectFilesystem(root))
            .setEnvironment(ImmutableMap.of())
            .build();
    DownwardApiConfig downwardApiConfig = DownwardApiConfig.of(buckConfig);
    return DefaultCxxPlatforms.build(
        Platform.detect(), new CxxBuckConfig(buckConfig), downwardApiConfig);
  }

  public static HeaderMode getHeaderModeForDefaultPlatform(Path root) throws IOException {
    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();
    CxxPlatform defaultPlatform = getDefaultPlatform(root);
    return defaultPlatform
            .getCpp()
            .resolve(ruleResolver, UnconfiguredTargetConfiguration.INSTANCE)
            .supportsHeaderMaps()
        ? HeaderMode.SYMLINK_TREE_WITH_HEADER_MAP
        : HeaderMode.SYMLINK_TREE_ONLY;
  }

  public static HeaderMode getHeaderModeForDefaultPlatform(AbsPath root) throws IOException {
    return getHeaderModeForDefaultPlatform(root.getPath());
  }
}
