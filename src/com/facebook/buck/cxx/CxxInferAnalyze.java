/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.SymCopyStep;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;

public class CxxInferAnalyze extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  private final Path resultsDir;
  private final Path reportFile;
  private final Path specsDir;
  private final Path specsPathList;

  @AddToRuleKey private final InferBuckConfig inferConfig;
  private final ImmutableSet<CxxInferCapture> captureRules;
  private final ImmutableSet<CxxInferAnalyze> transitiveAnalyzeRules;

  CxxInferAnalyze(
      BuildRuleParams buildRuleParams,
      InferBuckConfig inferConfig,
      ImmutableSet<CxxInferCapture> captureRules,
      ImmutableSet<CxxInferAnalyze> transitiveAnalyzeRules) {
    super(buildRuleParams);
    this.resultsDir =
        BuildTargets.getGenPath(getProjectFilesystem(), this.getBuildTarget(), "infer-analysis-%s");
    this.reportFile = this.resultsDir.resolve("report.json");
    this.specsDir = this.resultsDir.resolve("specs");
    this.specsPathList = this.resultsDir.resolve("specs_path_list.txt");
    this.inferConfig = inferConfig;
    this.captureRules = captureRules;
    this.transitiveAnalyzeRules = transitiveAnalyzeRules;
  }

  private ImmutableSortedSet<SourcePath> getSpecsOfAllDeps() {
    return transitiveAnalyzeRules
        .stream()
        .map(rule -> new ExplicitBuildTargetSourcePath(rule.getBuildTarget(), rule.getSpecsDir()))
        .collect(MoreCollectors.toImmutableSortedSet());
  }

  public Path getSpecsDir() {
    return specsDir;
  }

  public Path getAbsolutePathToResultsDir() {
    return getProjectFilesystem().resolve(resultsDir);
  }

  public ImmutableSet<CxxInferCapture> getCaptureRules() {
    return captureRules;
  }

  public ImmutableSet<CxxInferAnalyze> getTransitiveAnalyzeRules() {
    return transitiveAnalyzeRules;
  }

  private ImmutableList<String> getAnalyzeCommand() {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    commandBuilder
        .add(inferConfig.getInferTopLevel().toString())
        .add("--project_root", getProjectFilesystem().getRootPath().toString())
        .add("--out", resultsDir.toString())
        .add("--specs-dir-list-file", getProjectFilesystem().resolve(specsPathList).toString());
    commandBuilder.add("--", "analyze");

    return commandBuilder.build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(specsDir);
    buildableContext.recordArtifact(
        context.getSourcePathResolver().getRelativePath(getSourcePathToOutput()));
    return ImmutableList.<Step>builder()
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), specsDir)))
        .add(
            new SymCopyStep(
                getProjectFilesystem(),
                captureRules
                    .stream()
                    .map(CxxInferCapture::getSourcePathToOutput)
                    .map(context.getSourcePathResolver()::getRelativePath)
                    .collect(MoreCollectors.toImmutableList()),
                resultsDir))
        .add(
            new AbstractExecutionStep("write_specs_path_list") {
              @Override
              public StepExecutionResult execute(ExecutionContext executionContext)
                  throws IOException {
                try {
                  ImmutableList<String> specsDirsWithAbsolutePath =
                      getSpecsOfAllDeps()
                          .stream()
                          .map(
                              input ->
                                  context.getSourcePathResolver().getAbsolutePath(input).toString())
                          .collect(MoreCollectors.toImmutableList());
                  getProjectFilesystem().writeLinesToPath(specsDirsWithAbsolutePath, specsPathList);
                } catch (IOException e) {
                  executionContext.logError(
                      e, "Error while writing specs path list file for the analyzer");
                  return StepExecutionResult.ERROR;
                }
                return StepExecutionResult.SUCCESS;
              }
            })
        .add(
            new DefaultShellStep(
                getProjectFilesystem().getRootPath(), getAnalyzeCommand(), ImmutableMap.of()))
        .build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), reportFile);
  }
}
