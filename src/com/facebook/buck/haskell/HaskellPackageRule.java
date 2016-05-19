/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.haskell;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HaskellPackageRule extends AbstractBuildRule {

  @AddToRuleKey
  private final Tool ghcPkg;

  @AddToRuleKey
  private final HaskellPackageInfo packageInfo;

  @AddToRuleKey
  private final ImmutableSortedMap<String, HaskellPackage> depPackages;

  @AddToRuleKey
  private final ImmutableSortedSet<String> modules;

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> libraries;

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> interfaces;

  public HaskellPackageRule(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      Tool ghcPkg,
      HaskellPackageInfo packageInfo,
      ImmutableSortedMap<String, HaskellPackage> depPackages,
      ImmutableSortedSet<String> modules,
      ImmutableSortedSet<SourcePath> libraries,
      ImmutableSortedSet<SourcePath> interfaces) {
    super(buildRuleParams, resolver);
    this.ghcPkg = ghcPkg;
    this.packageInfo = packageInfo;
    this.depPackages = depPackages;
    this.modules = modules;
    this.libraries = libraries;
    this.interfaces = interfaces;
  }

  public static HaskellPackageRule from(
      BuildTarget target,
      BuildRuleParams baseParams,
      final SourcePathResolver resolver,
      final Tool ghcPkg,
      HaskellPackageInfo packageInfo,
      final ImmutableSortedMap<String, HaskellPackage> depPackages,
      ImmutableSortedSet<String> modules,
      final ImmutableSortedSet<SourcePath> libraries,
      final ImmutableSortedSet<SourcePath> interfaces) {
    return new HaskellPackageRule(
        baseParams.copyWithChanges(
            target,
            Suppliers.memoize(
                new Supplier<ImmutableSortedSet<BuildRule>>() {
                  @Override
                  public ImmutableSortedSet<BuildRule> get() {
                    return ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(ghcPkg.getDeps(resolver))
                        .addAll(
                            FluentIterable.from(depPackages.values())
                                .transformAndConcat(HaskellPackage.getDepsFunction(resolver)))
                        .addAll(
                            resolver.filterBuildRuleInputs(Iterables.concat(libraries, interfaces)))
                        .build();
                  }
                }),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        resolver,
        ghcPkg,
        packageInfo,
        depPackages,
        modules,
        libraries,
        interfaces);
  }

  private Path getPackageDb() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s");
  }

  private WriteFileStep getWriteRegistrationFileStep(Path registrationFile, Path packageDb) {
    Map<String, String> entries = new LinkedHashMap<>();

    entries.put("name", packageInfo.getName());
    entries.put("version", packageInfo.getVersion());
    entries.put("id", packageInfo.getIdentifier());

    entries.put("exposed", "True");
    entries.put("exposed-modules", Joiner.on(' ').join(modules));

    Path pkgRoot = getProjectFilesystem().getRootPath().getFileSystem().getPath("${pkgroot}");

    if (!modules.isEmpty()) {
      List<String> importDirs = new ArrayList<>();
      for (SourcePath interfaceDir : interfaces) {
        Path relInterfaceDir =
            pkgRoot.resolve(
                packageDb.getParent().relativize(getResolver().getRelativePath(interfaceDir)));
        importDirs.add('"' + relInterfaceDir.toString() + '"');
      }
      entries.put("import-dirs", Joiner.on(", ").join(importDirs));
    }

    List<String> libDirs = new ArrayList<>();
    List<String> libs = new ArrayList<>();
    for (SourcePath library : libraries) {
      Path relLibPath =
          pkgRoot.resolve(packageDb.getParent().relativize(getResolver().getRelativePath(library)));
      libDirs.add('"' + relLibPath.toString() + '"');
      libs.add(MorePaths.stripPathPrefixAndExtension(relLibPath.getFileName(), "lib"));
    }
    entries.put("library-dirs", Joiner.on(", ").join(libDirs));
    entries.put("hs-libraries", Joiner.on(", ").join(libs));

    entries.put("depends", Joiner.on(", ").join(depPackages.keySet()));

    return new WriteFileStep(
        getProjectFilesystem(),
        Joiner.on(System.lineSeparator())
            .join(
                FluentIterable.from(entries.entrySet())
                    .transform(
                        new Function<Map.Entry<String, String>, String>() {
                          @Override
                          public String apply(Map.Entry<String, String> input) {
                            return input.getKey() + ": " + input.getValue();
                          }
                        })),
        registrationFile,
        /* executable */ false);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // Setup the scratch dir.
    Path scratchDir = BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s");
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), scratchDir));

    // Setup the package DB directory.
    final Path packageDb = getPackageDb();
    steps.add(new RmStep(getProjectFilesystem(), packageDb, true, true));
    buildableContext.recordArtifact(packageDb);

    // Create the registration file.
    Path registrationFile = scratchDir.resolve("registration-file");
    steps.add(getWriteRegistrationFileStep(registrationFile, packageDb));

    // Build the the package DB.
    steps.add(
        new GhcPkgStep(
            ImmutableList.of("init", packageDb.toString()),
            ImmutableMap.<String, String>of()));
    steps.add(
        new GhcPkgStep(
            ImmutableList.of(
                "-v0",
                "register",
                "--package-conf=" + packageDb,
                "--no-expand-pkgroot",
                registrationFile.toString()),
            ImmutableMap.of(
                "GHC_PACKAGE_PATH",
                Joiner.on(':').join(
                    FluentIterable.from(depPackages.values())
                        .transform(
                            new Function<HaskellPackage, String>() {
                              @Override
                              public String apply(HaskellPackage input) {
                                return getResolver().getAbsolutePath(input.getPackageDb())
                                    .toString();
                              }
                            })))));

    return steps.build();
  }

  @Override
  public Path getPathToOutput() {
    return getPackageDb();
  }

  public HaskellPackage getPackage() {
    return HaskellPackage.builder()
        .setInfo(packageInfo)
        .setPackageDb(new BuildTargetSourcePath(getBuildTarget(), getPackageDb()))
        .addAllLibraries(libraries)
        .addAllInterfaces(interfaces)
        .build();
  }

  private class GhcPkgStep extends ShellStep {

    private final ImmutableList<String> args;
    private final ImmutableMap<String, String> env;

    public GhcPkgStep(
        ImmutableList<String> args,
        ImmutableMap<String, String> env) {
      super(getProjectFilesystem().getRootPath());
      this.args = args;
      this.env = env;
    }

    @Override
    protected final ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
      return ImmutableList.<String>builder()
          .addAll(ghcPkg.getCommandPrefix(getResolver()))
          .addAll(args)
          .build();
    }

    @Override
    public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
      return ImmutableMap.<String, String>builder()
          .putAll(super.getEnvironmentVariables(context))
          .putAll(ghcPkg.getEnvironment(getResolver()))
          .putAll(env)
          .build();
    }

    @Override
    public final String getShortName() {
      return "ghc-pkg";
    }

  }

}