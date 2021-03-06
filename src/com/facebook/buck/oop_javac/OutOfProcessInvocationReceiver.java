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

package com.facebook.buck.oop_javac;

import com.facebook.buck.jvm.java.JarBackedJavac;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacCompilationMode;
import com.facebook.buck.jvm.java.JavacExecutionContext;
import com.facebook.buck.jvm.java.JavacExecutionContextSerializer;
import com.facebook.buck.jvm.java.JavacPluginJsr199Fields;
import com.facebook.buck.jvm.java.JavacPluginJsr199FieldsSerializer;
import com.facebook.buck.jvm.java.JdkProvidedInMemoryJavac;
import com.facebook.buck.jvm.java.OutOfProcessJavacConnectionInterface;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.util.ClassLoaderCache;
import com.facebook.buck.util.Console;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class OutOfProcessInvocationReceiver implements OutOfProcessJavacConnectionInterface {

  /** The command was interrupted */
  public static final int INTERRUPTED_EXIT_CODE = 130;

  private final Console console;

  public OutOfProcessInvocationReceiver(Console console) {
    this.console = console;
  }

  @Override
  public int buildWithClasspath(
      @Nullable String compilerClassNameForJarBackedJavacMode,
      Map<String, Object> serializedJavacExecutionContext,
      String invokingRuleBuildTargetAsString,
      List<String> options,
      List<String> sortedSetOfJavaSourceFilePathsAsStringsAsList,
      String pathToSrcsListAsString,
      @Nullable String workingDirectoryAsString,
      List<Map<String, Object>> pluginFields,
      String javaCompilationModeAsString) {

    PrintStream printStreamForStdErr = new PrintStream(new ByteArrayOutputStream());

    JavacExecutionContext javacExecutionContext;
    try {
      javacExecutionContext =
          JavacExecutionContextSerializer.deserialize(
              serializedJavacExecutionContext,
              new OutOfProcessJavacEventSink(),
              printStreamForStdErr,
              new ClassLoaderCache(),
              console);
    } catch (InterruptedException e) {
      return INTERRUPTED_EXIT_CODE;
    }

    Optional<String> className = Optional.ofNullable(compilerClassNameForJarBackedJavacMode);
    CellPathResolver cellPathResolver = javacExecutionContext.getCellPathResolver();

    BuildTarget invokingRule =
        BuildTargetParser.INSTANCE.parse(
            invokingRuleBuildTargetAsString,
            BuildTargetPatternParser.fullyQualified(),
            cellPathResolver);

    ImmutableSortedSet<Path> javaSourceFilePaths =
        ImmutableSortedSet.copyOf(
            sortedSetOfJavaSourceFilePathsAsStringsAsList
                .stream()
                .map(s -> Paths.get(s))
                .iterator());
    Path pathToSrcsList = Paths.get(pathToSrcsListAsString);
    Optional<Path> workingDirectory = Optional.empty();
    if (workingDirectoryAsString != null) {
      workingDirectory = Optional.of(Paths.get(workingDirectoryAsString));
    }

    List<JavacPluginJsr199Fields> deserializedFields =
        pluginFields
            .stream()
            .map(JavacPluginJsr199FieldsSerializer::deserialize)
            .collect(Collectors.toList());

    Javac javac = createJavac(className);
    try {
      return javac.buildWithClasspath(
          javacExecutionContext,
          invokingRule,
          ImmutableList.copyOf(options),
          ImmutableList.copyOf(deserializedFields),
          javaSourceFilePaths,
          pathToSrcsList,
          workingDirectory,
          JavacCompilationMode.valueOf(javaCompilationModeAsString));
    } catch (InterruptedException e) {
      return INTERRUPTED_EXIT_CODE;
    }
  }

  private Javac createJavac(Optional<String> className) {
    Javac javac;
    if (className.isPresent()) {
      javac = new JarBackedJavac(className.get(), ImmutableList.of());
    } else {
      javac = new JdkProvidedInMemoryJavac();
    }
    return javac;
  }

  @Override
  public int ping(int valueToReturn) {
    return valueToReturn;
  }
}
