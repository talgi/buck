/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.AndroidBinaryBuilder;
import com.facebook.buck.android.AndroidLibraryBuilder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavaTestBuilder;
import com.facebook.buck.jvm.java.KeystoreBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.SortedSet;

public class AuditClasspathCommandTest {

  private TestConsole console;
  private AuditClasspathCommand auditClasspathCommand;
  private CommandRunnerParams params;

  @Before
  public void setUp() throws IOException, InterruptedException {
    console = new TestConsole();
    auditClasspathCommand = new AuditClasspathCommand();
    params = CommandRunnerParamsForTesting.builder()
        .setConsole(console)
        .build();
  }

  @Test
  public void testClassPathOutput() throws Exception {
    // Test that no output is created.
    auditClasspathCommand.printClasspath(
        params,
        TargetGraphFactory.newInstance(ImmutableSet.<TargetNode<?>>of()),
        ImmutableSet.<BuildTarget>of());
    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());

    // Add build rules such that all implementations of HasClasspathEntries are tested.
    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//:test-java-library");
    BuildTarget testJavaTarget = BuildTargetFactory.newInstance("//:project-tests");
    BuildTarget androidLibraryTarget = BuildTargetFactory.newInstance("//:test-android-library");
    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//:keystore");
    BuildTarget testAndroidTarget = BuildTargetFactory.newInstance("//:test-android-binary");

    TargetNode<?> javaLibraryNode = JavaLibraryBuilder
        .createBuilder(javaLibraryTarget)
        .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
        .addTest(testJavaTarget)
        .build();
    TargetNode<?> androidLibraryNode = AndroidLibraryBuilder
        .createBuilder(androidLibraryTarget)
        .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
        .addDep(javaLibraryTarget)
        .build();
    TargetNode<?> keystoreNode = KeystoreBuilder
        .createBuilder(keystoreTarget)
        .setStore(new FakeSourcePath("debug.keystore"))
        .setProperties(new FakeSourcePath("keystore.properties"))
        .build();
    TargetNode<?> testAndroidNode = AndroidBinaryBuilder
        .createBuilder(testAndroidTarget)
        .setManifest(new FakeSourcePath("AndroidManifest.xml"))
        .setKeystore(keystoreTarget)
        .setOriginalDeps(ImmutableSortedSet.of(androidLibraryTarget, javaLibraryTarget))
        .build();
    TargetNode<?> testJavaNode = JavaTestBuilder
        .createBuilder(testJavaTarget)
        .addDep(javaLibraryTarget)
        .addSrc(Paths.get("src/com/facebook/test/ProjectTests.java"))
        .build();

    auditClasspathCommand.printClasspath(
        params,
        TargetGraphFactory.newInstance(
            ImmutableSet.of(
                javaLibraryNode,
                androidLibraryNode,
                keystoreNode,
                testAndroidNode,
                testJavaNode)),
        ImmutableSet.<BuildTarget>of());

    // Still empty.
    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());

    // Request the top build target. This will test the following:
    // - paths don't appear multiple times when dependencies are referenced multiple times.
    // - dependencies are walked
    // - independent targets in the same BUCK file are not included in the output
    auditClasspathCommand.printClasspath(
        params,
        TargetGraphFactory.newInstance(
            ImmutableSet.of(
                javaLibraryNode,
                androidLibraryNode,
                keystoreNode,
                testAndroidNode,
                testJavaNode)),
        ImmutableSet.of(
            testAndroidTarget));

    Path root = javaLibraryTarget.getUnflavoredBuildTarget().getCellPath();
    SortedSet<String> expectedPaths = Sets.newTreeSet(
        Arrays.asList(
            root.resolve(
                BuildTargets
                    .getGenPath(
                        params.getCell().getFilesystem(),
                        androidLibraryTarget,
                        "lib__%s__output")
                    .resolve(androidLibraryTarget.getShortName() + ".jar"))
                .toString(),
            root.resolve(
                BuildTargets
                    .getGenPath(
                        params.getCell().getFilesystem(),
                        javaLibraryTarget,
                        "lib__%s__output")
                    .resolve(javaLibraryTarget.getShortName() + ".jar"))
                .toString()));
    String expectedClasspath = Joiner.on("\n").join(expectedPaths) + "\n";

    assertEquals(expectedClasspath, console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());

    // Add independent test target. This will test:
    // - the union of the classpath is output.
    // - all rules have implemented HasClasspathEntries.
    // Note that the output streams are reset.
    setUp();
    auditClasspathCommand.printClasspath(
        params,
        TargetGraphFactory.newInstance(
            ImmutableSet.of(
                javaLibraryNode,
                androidLibraryNode,
                keystoreNode,
                testAndroidNode,
                testJavaNode)),
        ImmutableSet.of(
            testAndroidTarget,
            javaLibraryTarget,
            androidLibraryTarget,
            testJavaTarget));

    expectedPaths.add(
        root.resolve(
            BuildTargets
                .getGenPath(
                    params.getCell().getFilesystem(),
                    testJavaTarget,
                    "lib__%s__output")
                .resolve(testJavaTarget.getShortName() + ".jar"))
            .toString());
    expectedClasspath = Joiner.on("\n").join(expectedPaths) + "\n";
    assertEquals(expectedClasspath, console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());
  }

  private static final String EXPECTED_JSON = Joiner.on("").join(
      "{",
      "\"//:test-android-library\":",
      "[",
      "%s,",
      "%s",
      "],",
      "\"//:test-java-library\":",
      "[",
      "%s",
      "]",
      "}");

  @Test
  public void testJsonClassPathOutput() throws Exception {
    // Build a DependencyGraph of build rules manually.

    BuildTarget javaTarget = BuildTargetFactory.newInstance("//:test-java-library");
    TargetNode<?> javaNode = JavaLibraryBuilder
        .createBuilder(javaTarget)
        .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
        .build();

    BuildTarget androidTarget = BuildTargetFactory.newInstance("//:test-android-library");
    TargetNode<?> androidNode = AndroidLibraryBuilder
        .createBuilder(androidTarget)
        .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
        .addDep(javaTarget)
        .build();

    auditClasspathCommand.printJsonClasspath(
        params,
        TargetGraphFactory.newInstance(
            ImmutableSet.of(
                androidNode,
                javaNode)),
        ImmutableSet.of(
            androidTarget,
            javaTarget));

    Path root = javaTarget.getCellPath();
    ObjectMapper mapper = ObjectMappers.newDefaultInstance();
    String expected = String.format(EXPECTED_JSON,
        mapper.valueToTree(root.resolve(
            BuildTargets
                .getGenPath(
                    params.getCell().getFilesystem(),
                    javaTarget,
                    "lib__%s__output")
                .resolve(javaTarget.getShortName() + ".jar"))),
        mapper.valueToTree(root.resolve(
            BuildTargets
                .getGenPath(
                    params.getCell().getFilesystem(),
                    androidTarget,
                    "lib__%s__output")
                .resolve(androidTarget.getShortName() + ".jar"))),
        mapper.valueToTree(root.resolve(
            BuildTargets
                .getGenPath(
                    params.getCell().getFilesystem(),
                    javaTarget,
                    "lib__%s__output")
                .resolve(javaTarget.getShortName() + ".jar"))));
    assertEquals(expected, console.getTextWrittenToStdOut());

    assertEquals("", console.getTextWrittenToStdErr());
  }
}
