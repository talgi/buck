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

package com.facebook.buck.jvm.java.intellij;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.BuckConstant;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.TimeUnit;

/**
 * Cleans out any unwanted IntelliJ IDEA project files.
 */
public class IJProjectCleaner {

  private static final Logger LOG = Logger.get(IJProjectCleaner.class);

  private static final int EXECUTOR_SHUTDOWN_TIMEOUT = 1;
  private static final TimeUnit EXECUTOR_SHUTDOWN_TIME_UNIT = TimeUnit.MINUTES;

  private static final FilenameFilter IML_FILENAME_FILTER = new FilenameFilter() {
    @Override
    public boolean accept(File dir, String name) {
      return name.endsWith(".iml");
    }
  };

  private static final FilenameFilter XML_FILENAME_FILTER = new FilenameFilter() {
    @Override
    public boolean accept(File dir, String name) {
      return name.endsWith(".xml");
    }
  };

  private static final FileFilter SUBDIRECTORY_FILTER = new FileFilter() {
    @Override
    public boolean accept(File pathname) {
      return pathname.isDirectory();
    }
  };

  private final ProjectFilesystem projectFilesystem;

  private final Set<File> filesToKeep = new HashSet<>();

  public IJProjectCleaner(ProjectFilesystem projectFilesystem) {
    this.projectFilesystem = projectFilesystem;
  }

  /**
   * @param path The path to not include in the cleaning operation.
   */
  public void doNotDelete(Path path) {
    filesToKeep.add(convertPathToFile(path));
  }


  private File convertPathToFile(Path path) {
    if (!path.isAbsolute()) {
      path = projectFilesystem.resolve(path);
    }
    try {
      return path.toRealPath().toFile();
    } catch (IOException e) {
      LOG.warn("Problem resolving " + path, e);
      return path.toAbsolutePath().toFile();
    }
  }

  /**
   * We shouldn't use all the processors available because this can bog down the system,
   * but using n/2 with an upper limit of 4 as the thread limit should give us enough
   * threads to keep SSDs busy whilst not bogging down systems with slow rotating disks.
   */
  private int getParallelismLimit() {
    int limit = Math.max(Runtime.getRuntime().availableProcessors() / 2, 4);
    return limit > 0 ? limit : 1;
  }

  @SuppressWarnings("serial")
  public void clean(final BuckConfig buckConfig, final Path librariesXmlBase) {
    final Set<File> buckDirectories = new HashSet<>();
    buckDirectories.add(
        convertPathToFile(
            projectFilesystem.resolve(BuckConstant.BUCK_OUTPUT_PATH)));
    buckDirectories.add(
        convertPathToFile(
            projectFilesystem.resolve(
                buckConfig.getLocalCacheDirectory())));

    ForkJoinPool cleanExecutor = new ForkJoinPool(getParallelismLimit());
    try {
      cleanExecutor.invoke(new RecursiveAction() {
        @Override
        protected void compute() {
          List<RecursiveAction> topLevelTasks = new ArrayList<>(2);
          topLevelTasks.add(new CandidateFinderWithExclusions(
              convertPathToFile(projectFilesystem.resolve("")),
              IML_FILENAME_FILTER,
              buckDirectories));
          topLevelTasks.add(new CandidateFinder(
              convertPathToFile(librariesXmlBase),
              XML_FILENAME_FILTER));
          invokeAll(topLevelTasks);
        }
      });
    } finally {
      cleanExecutor.shutdown();
      try {
        cleanExecutor.awaitTermination(
            EXECUTOR_SHUTDOWN_TIMEOUT,
            EXECUTOR_SHUTDOWN_TIME_UNIT);
      } catch (InterruptedException e) {
        Logger.get(IJProjectCleaner.class).warn("Timeout during executor shutdown.", e);
      }
    }
  }

  @SuppressWarnings("serial")
  private class DirectoryCleaner extends RecursiveAction {
    private File directory;
    private FilenameFilter filenameFilter;

    DirectoryCleaner(File directory, FilenameFilter filenameFilter) {
      this.directory = directory;
      this.filenameFilter = filenameFilter;
    }

    @Override
    protected void compute() {
      File[] files = directory.listFiles(filenameFilter);
      if (files == null) {
        return;
      }

      for (File file : files) {
        if (filesToKeep.contains(file)) {
          LOG.warn("Skipping " + file);
          continue;
        }

        LOG.warn("Deleting " + file);
        if (!file.delete()) {
          LOG.warn("Unable to delete " + file);
        }
      }
    }
  }

  @SuppressWarnings("serial")
  private class CandidateFinder extends RecursiveAction {
    private File directory;
    private FilenameFilter filenameFilter;

    CandidateFinder(File directory, FilenameFilter filenameFilter) {
      this.directory = directory;
      this.filenameFilter = filenameFilter;
    }

    protected void addCandidateDirectoryFinder(List<RecursiveAction> finders, File subdirectory) {
      finders.add(new CandidateFinder(subdirectory, filenameFilter));
    }

    @Override
    public void compute() {
      File[] subdirectories = directory.listFiles(SUBDIRECTORY_FILTER);
      if (subdirectories == null) {
        return;
      }

      List<RecursiveAction> actions = new ArrayList<>(subdirectories.length + 1);

      actions.add(new DirectoryCleaner(directory, filenameFilter));

      for (File subdirectory : subdirectories) {
        addCandidateDirectoryFinder(actions, subdirectory);
      }

      invokeAll(actions);
    }
  }

  @SuppressWarnings("serial")
  private class CandidateFinderWithExclusions extends CandidateFinder {
    Set<File> exclusions;

    CandidateFinderWithExclusions(
        File directory,
        FilenameFilter filenameFilter,
        Set<File> exclusions) {
      super(directory, filenameFilter);
      this.exclusions = exclusions;
    }

    @Override
    protected void addCandidateDirectoryFinder(List<RecursiveAction> actions, File subdirectory) {
      if (exclusions.contains(subdirectory)) {
        return;
      }

      super.addCandidateDirectoryFinder(actions, subdirectory);
    }
  }
}
