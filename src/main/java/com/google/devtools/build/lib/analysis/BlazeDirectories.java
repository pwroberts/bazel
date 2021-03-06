// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.analysis;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.util.StringCanonicalizer;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;

/**
 * Encapsulates the directories related to a workspace.
 *
 * <p>The <code>workspace</code> is the top-level directory in the user's client (possibly
 * read-only). The <code>execRoot</code> is the working directory for all spawned tools, which is
 * generally below the <code>outputBase</code>.
 *
 * <p>Care must be taken to avoid multiple Bazel instances trying to write to the same output
 * directory. At this time, this is enforced by requiring a 1:1 correspondence between a running
 * Bazel instance and an output base directory, though this requirement may be softened in the
 * future.
 *
 * <p>If the user does not qualify an output base directory, the startup code will derive it
 * deterministically from the workspace. Note also that while the Bazel server process runs with the
 * workspace directory as its working directory, the client process may have a different working
 * directory, typically a subdirectory.
 *
 * <p>Do not put shortcuts to specific files here!
 */
@Immutable
public final class BlazeDirectories {

  // Include directory name, relative to execRoot/blaze-out/configuration.
  public static final String RELATIVE_INCLUDE_DIR = StringCanonicalizer.intern("include");
  @VisibleForTesting
  static final String DEFAULT_EXEC_ROOT = "default-exec-root";

  private final ServerDirectories serverDirectories;
  /** Workspace root and server CWD. */
  private final Path workspace;
  /** The root of all build actions. */
  private final Path execRoot;

  // These two are kept to avoid creating new objects every time they are accessed. This showed up
  // in a profiler.
  private final Path outputPath;
  private final Path localOutputPath;
  private final String productName;

  public BlazeDirectories(
      ServerDirectories serverDirectories,
      Path workspace,
      boolean deepExecRoot,
      String productName) {
    this.serverDirectories = serverDirectories;
    this.workspace = workspace;
    this.productName = productName;
    Path outputBase = serverDirectories.getOutputBase();
    Path execRootBase = deepExecRoot ? outputBase.getChild("execroot") : outputBase;
    boolean useDefaultExecRootName = this.workspace == null || this.workspace.isRootDirectory();
    if (useDefaultExecRootName) {
      // TODO(bazel-team): if workspace is null execRoot should be null, but at the moment there is
      // a lot of code that depends on it being non-null.
      this.execRoot = execRootBase.getChild(DEFAULT_EXEC_ROOT);
    } else {
      this.execRoot = execRootBase.getChild(workspace.getBaseName());
    }
    String relativeOutputPath = getRelativeOutputPath(productName);
    this.outputPath = execRoot.getRelative(getRelativeOutputPath());
    this.localOutputPath = outputBase.getRelative(relativeOutputPath);
  }

  @VisibleForTesting
  public BlazeDirectories(ServerDirectories serverDirectories, Path workspace, String productName) {
    this(serverDirectories, workspace, false, productName);
  }

  @VisibleForTesting
  public BlazeDirectories(Path installBase, Path outputBase, Path workspace, String productName) {
    this(new ServerDirectories(installBase, outputBase), workspace, false, productName);
  }

  /**
   * Returns the Filesystem that all of our directories belong to. Handy for
   * resolving absolute paths.
   */
  public FileSystem getFileSystem() {
    return serverDirectories.getFileSystem();
  }

  public ServerDirectories getServerDirectories() {
    return serverDirectories;
  }

  /**
   * Returns the base of the output tree, which hosts all build and scratch
   * output for a user and workspace.
   */
  public Path getInstallBase() {
    return serverDirectories.getInstallBase();
  }

  /**
   * Returns the workspace directory, which is also the working dir of the server.
   */
  public Path getWorkspace() {
    return workspace;
  }

  /**
   * Returns if the workspace directory is a valid workspace.
   */
  public boolean inWorkspace() {
    return this.workspace != null;
  }

  /**
   * Returns the base of the output tree, which hosts all build and scratch
   * output for a user and workspace.
   */
  public Path getOutputBase() {
    return serverDirectories.getOutputBase();
  }

  /**
   * Returns the execution root for the main package. This is created before the workspace file
   * has been read, so it has an incorrect path.  Use {@link #getExecRoot(String)} instead.
   */
  @Deprecated
  public Path getExecRoot() {
    return execRoot;
  }

  /**
   * Returns the execution root for a particular repository. This is the directory underneath which
   * Blaze builds the source symlink forest, to represent the merged view of different workspaces
   * specified with --package_path.
   */
  public Path getExecRoot(String workspaceName) {
    return execRoot.getParentDirectory().getRelative(workspaceName);
  }

  /**
   * Returns the output path for the main repository using the workspace's directory name. Use
   * {@link #getOutputPath(String)}, instead.
   */
  @Deprecated
  public Path getOutputPath() {
    return outputPath;
  }

  /**
   * Returns the output path used by this Blaze instance.
   */
  public Path getOutputPath(String workspaceName) {
    return getExecRoot(workspaceName).getRelative(getRelativeOutputPath());
  }

  /**
   * Returns the local output path used by this Blaze instance.
   */
  public Path getLocalOutputPath() {
    return localOutputPath;
  }

  /**
   * Returns the directory where the stdout/stderr for actions can be stored
   * temporarily for a build. If the directory already exists, the directory
   * is cleaned.
   */
  public Path getActionConsoleOutputDirectory() {
    return getOutputBase().getRelative("action_outs");
  }

  /**
   * Returns the installed embedded binaries directory, under the shared
   * installBase location.
   */
  public Path getEmbeddedBinariesRoot() {
    return serverDirectories.getEmbeddedBinariesRoot();
  }

  /**
   * Returns the configuration-independent root where the build-data should be placed, given the
   * {@link BlazeDirectories} of this server instance. Nothing else should be placed here.
   */
  public Root getBuildDataDirectory(String workspaceName) {
    return Root.asDerivedRoot(getExecRoot(workspaceName), getOutputPath(workspaceName));
  }

 /**
  * Returns the MD5 content hash of the blaze binary (includes deploy JAR, embedded binaries, and
  * anything else that ends up in the install_base).
  */
  public HashCode getInstallMD5() {
    return serverDirectories.getInstallMD5();
  }

  public String getRelativeOutputPath() {
    return BlazeDirectories.getRelativeOutputPath(productName);
  }

  /**
   * Returns the output directory name, relative to the execRoot.
   * TODO(bazel-team): (2011) make this private?
   */
  public static String getRelativeOutputPath(String productName) {
    return StringCanonicalizer.intern(productName + "-out");
  }
}
