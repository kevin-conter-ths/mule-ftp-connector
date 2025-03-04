/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.extension.ftp.internal.command;

import static java.lang.String.format;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.commons.net.ftp.FTPFile.DIRECTORY_TYPE;
import static org.mule.extension.file.common.api.util.UriUtils.createUri;
import static org.mule.extension.file.common.api.util.UriUtils.normalizeUri;
import static org.mule.extension.file.common.api.util.UriUtils.trimLastFragment;
import static org.mule.extension.ftp.internal.FtpUtils.getReplyErrorMessage;
import static org.mule.extension.ftp.internal.FtpUtils.normalizePath;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;

import org.mule.extension.file.common.api.FileAttributes;
import org.mule.extension.file.common.api.FileConnectorConfig;
import org.mule.extension.file.common.api.FileSystem;
import org.mule.extension.file.common.api.command.ExternalFileCommand;
import org.mule.extension.file.common.api.command.FileCommand;
import org.mule.extension.file.common.api.exceptions.FileAlreadyExistsException;
import org.mule.extension.ftp.api.ftp.FtpFileAttributes;
import org.mule.extension.ftp.internal.FtpCopyDelegate;
import org.mule.extension.ftp.internal.connection.FtpFileSystem;
import org.mule.runtime.api.connection.ConnectionException;
import org.mule.runtime.api.exception.MuleRuntimeException;

import java.io.IOException;
import java.net.URI;
import java.util.Calendar;
import java.util.Optional;
import java.util.Stack;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.net.MalformedServerReplyException;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPConnectionClosedException;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPListParseEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for {@link FileCommand} implementations that target a FTP/SFTP server
 *
 * @since 1.0
 */
public abstract class FtpCommand extends ExternalFileCommand<FtpFileSystem> {

  private static final Logger LOGGER = LoggerFactory.getLogger(FtpCommand.class);
  private static final int FTP_LIST_PAGE_SIZE = 25;
  protected static final String ROOT = "/";
  protected static final String SEPARATOR = "/";

  protected final FTPClient client;

  protected FtpCommand(FtpFileSystem fileSystem) {
    this(fileSystem, fileSystem.getClient());
  }

  /**
   * Creates a new instance
   *
   * @param fileSystem the {@link FileSystem} on which the operation is performed
   * @param client a ready to use {@link FTPClient} to perform the operations
   */
  protected FtpCommand(FtpFileSystem fileSystem, FTPClient client) {
    super(fileSystem);
    this.client = client;
  }

  /**
   * Similar to {@link #getFile(String)} but throwing an {@link IllegalArgumentException} if the
   * {@code filePath} doesn't exist
   *
   * @param filePath the path to the file you want
   * @return a {@link FtpFileAttributes}
   * @throws IllegalArgumentException if the {@code filePath} doesn't exist
   */
  protected FtpFileAttributes getExistingFile(String filePath) {
    return getFile(filePath, true);
  }

  /**
   * Obtains a {@link FtpFileAttributes} for the given {@code filePath} by using the {@link FTPClient#mlistFile(String)} FTP
   * command
   *
   * @param filePath the path to the file you want
   * @return a {@link FtpFileAttributes} or {@code null} if it doesn't exist
   */
  public FtpFileAttributes getFile(String filePath) {
    return getFile(filePath, false);
  }

  protected FtpFileAttributes getFile(String filePath, boolean requireExistence) {
    // We need to normalize the filePath because it can have a trailing separator
    URI uri = normalizeUri(resolveUri(normalizePath(filePath)));
    if (LOGGER.isTraceEnabled()) {
      LOGGER.trace("Get file attributes for path {}", uri);
    }
    return getFileFromAbsoluteUri(uri, requireExistence);
  }

  protected FtpFileAttributes getFileFromAbsoluteUri(URI uri, boolean requireExistence) {
    Optional<FTPFile> ftpFile;
    try {
      ftpFile = doGetFileFromAbsoluteUri(uri);
    } catch (Exception e) {
      throw exception("Found exception trying to obtain path " + uri.getPath(), e);
    }

    if (ftpFile.isPresent()) {
      FtpFileAttributes attributes = new FtpFileAttributes(uri, ftpFile.get());
      if (LOGGER.isTraceEnabled()) {
        LOGGER.trace("Obtained file attributes {}", attributes);
      }
      return attributes;
    } else {
      if (requireExistence) {
        throw pathNotFoundException(uri);
      } else {
        return null;
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected boolean exists(URI uri) {
    return ROOT.equals(uri.getPath()) || getFile(normalizePath(uri.getPath())) != null;
  }

  /**
   * Changes the current working directory to the given {@code path}
   *
   * @param path the path to which you wish to move
   * @throws IllegalArgumentException if the CWD could not be changed
   */
  protected void changeWorkingDirectory(String path) {
    if (!tryChangeWorkingDirectory(path)) {
      throw new IllegalArgumentException(format("Could not change working directory to '%s'. Path doesn't exist or is not a directory",
                                                path));
    }
    LOGGER.debug("working directory changed to {}", path);
  }

  /**
   * Returns a {@link URI} relative to the {@code baseUri} and the given {@code filePath}
   *
   * @param filePath the path to a file or directory
   * @return a relative {@link URI}
   */
  protected URI resolveUri(String filePath) {
    URI uri = getBasePath(fileSystem);
    if (filePath != null) {
      uri = createUri(uri.getPath(), filePath);
    }
    return uri;
  }

  /**
   * Attempts to change the current working directory. If it was not possible (for example, because it doesn't exist), it returns
   * {@code false}
   *
   * @param path the path to which you wish to move
   * @return {@code true} if the CWD was changed. {@code false} otherwise
   */
  protected boolean tryChangeWorkingDirectory(String path) {
    try {
      return client.changeWorkingDirectory(normalizePath(path));
    } catch (IOException e) {
      throw exception("Exception was found while trying to change working directory to " + path, e);
    }
  }

  /**
   * Creates the directory of the given {@code directoryName} in the current working directory
   *
   * @param directoryName the name of the directory you want to create
   */
  protected void makeDirectory(String directoryName) {
    try {
      if (!client.makeDirectory(normalizePath(directoryName))) {
        throw exception("Failed to create directory " + directoryName);
      }
    } catch (Exception e) {
      throw exception("Exception was found trying to create directory " + directoryName, e);
    }
  }


  /**
   * Renames the file at {@code filePath} to {@code newName}.
   *
   * @param filePath the path of the file to be renamed
   * @param newName the new name
   * @param overwrite whether to overwrite the target file if it already exists
   */
  protected void rename(String filePath, String newName, boolean overwrite) {
    URI source = resolveExistingPath(filePath);
    URI target = createUri(trimLastFragment(source).getPath(), newName);

    if (exists(target)) {
      if (!overwrite) {
        throw new FileAlreadyExistsException(format("'%s' cannot be renamed because '%s' already exists", source.getPath(),
                                                    target.getPath()));
      }

      try {
        fileSystem.delete(target.getPath());
      } catch (Exception e) {
        throw exception(format("Exception was found deleting '%s' as part of renaming '%s'", target.getPath(), source.getPath()),
                        e);
      }
    }

    try {
      boolean result = client.rename(normalizePath(source.getPath()), normalizePath(target.getPath()));
      if (!result) {
        throw new MuleRuntimeException(createStaticMessage(format("Could not rename path '%s' to '%s'", filePath, newName)));
      }
      LOGGER.debug("{} renamed to {}", filePath, newName);
    } catch (Exception e) {
      throw exception(format("Exception was found renaming '%s' to '%s'", source.getPath(), newName), e);
    }
  }


  protected void createDirectory(String directoryPath) {
    URI uri = createUri(fileSystem.getBasePath(), directoryPath);
    FileAttributes targetFile = getFile(directoryPath);

    if (targetFile != null) {
      throw new FileAlreadyExistsException(format("Directory '%s' already exists", uri.getPath()));
    }

    mkdirs(normalizeUri(uri));
  }

  /**
   * Performs the base logic and delegates into
   * {@link FtpCopyDelegate#doCopy(FileConnectorConfig, FileAttributes, URI, boolean)} to perform the actual
   * copying logic
   *  @param config the config that is parameterizing this operation
   * @param source the path to be copied
   * @param target the path to the target destination
   * @param overwrite whether to overwrite existing target paths
   * @param createParentDirectory whether to create the target's parent directory if it doesn't exist
   */
  protected final void copy(FileConnectorConfig config, String source, String target, boolean overwrite,
                            boolean createParentDirectory, String renameTo, FtpCopyDelegate delegate) {
    FileAttributes sourceFile = getExistingFile(source);
    URI targetUri = createUri(getBasePath(fileSystem).getPath(), target);
    FileAttributes targetFile = getFile(targetUri.getPath());
    // This additional check has to be added because there are directories that exist that do not appear when listed.
    boolean targetPathIsDirectory = getUriToDirectory(target).isPresent();
    String targetFileName = isBlank(renameTo) ? getFileName(source) : renameTo;
    if (targetPathIsDirectory || targetFile != null) {
      if (targetPathIsDirectory || targetFile.isDirectory()) {
        if (sourceFile.isDirectory() && (targetFile != null && sourceFile.getName().equals(targetFile.getName())) && !overwrite) {
          throw alreadyExistsException(targetUri);
        } else {
          targetUri = createUri(targetUri.getPath(), targetFileName);
        }
      } else if (!overwrite) {
        throw alreadyExistsException(targetUri);
      }
    } else {
      if (createParentDirectory) {
        mkdirs(targetUri);
        targetUri = createUri(targetUri.getPath(), targetFileName);
      } else {
        throw pathNotFoundException(targetUri);
      }
    }

    final String cwd = getCurrentWorkingDirectory();
    delegate.doCopy(config, sourceFile, targetUri, overwrite);
    LOGGER.debug("Copied '{}' to '{}'", sourceFile, targetUri.getPath());
    changeWorkingDirectory(cwd);
  }

  private String getFileName(String path) {
    // This path needs to be normalized first because if it ends in a separator the method will return an empty String.
    return FilenameUtils.getName(normalizeUri(createUri(path)).getPath());
  }

  /**
   * @return the path of the current working directory
   */
  protected String getCurrentWorkingDirectory() {
    try {
      return client.printWorkingDirectory();
    } catch (Exception e) {
      throw exception("Failed to determine current working directory");
    }
  }

  private Optional<FTPFile> doGetFileFromAbsoluteUri(URI absoluteUri) throws IOException {
    String filePath = normalizeUri(absoluteUri).getPath();
    FTPFile file = null;
    // Check if MLST command is supported.
    try {
      file = client.mlistFile(filePath); // This method also obtains directories.
    } catch (MalformedServerReplyException e) {
      LOGGER.debug(e.getMessage());
    }
    if (file == null) {
      return getFileFromParentDirectory(absoluteUri);
    }
    return Optional.ofNullable(file);
  }

  private Optional<FTPFile> getFileFromParentDirectory(URI absoluteUri) throws IOException {
    String filePath = normalizePath(absoluteUri.getPath());
    String fileParentPath = getParentPath(absoluteUri);

    if (fileParentPath == null) {
      return Optional.of(createRootFile());
    }

    if (tryChangeWorkingDirectory(fileParentPath)) {
      Optional<FTPFile> foundFile = findFileByPath(filePath);
      if (foundFile.isPresent()) {
        return foundFile;
      } else {
        return findDirectoryByPath(filePath);
      }
    }

    return Optional.empty();
  }

  private Optional<FTPFile> findFileByPath(String filePath) throws IOException {
    FTPListParseEngine engine = client.initiateListParsing(filePath);
    return findFileInEngine(engine, filePath);
  }

  private Optional<FTPFile> findDirectoryByPath(String filePath) throws IOException {
    // If the file is a directory the list parsing can't find the directory by its name, it needs to do listParsing by current directory
    FTPListParseEngine engine = client.initiateListParsing();
    return findFileInEngine(engine, filePath);
  }

  private Optional<FTPFile> findFileInEngine(FTPListParseEngine engine, String filePath) {
    while (engine.hasNext()) {
      FTPFile[] files = engine.getNext(FTP_LIST_PAGE_SIZE);
      for (FTPFile file : files) {
        if (file != null && FilenameUtils.getName(filePath).equals(file.getName())) {
          return Optional.of(file);
        }
      }
    }
    return Optional.empty();
  }

  private String getParentPath(URI absoluteUri) {
    URI parentPath = trimLastFragment(absoluteUri);
    if (parentPath == null || isBlank(parentPath.getPath())) {
      return isNotBlank(normalizeUri(absoluteUri).getPath()) ? ROOT : null;
    }
    return parentPath.getPath();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void doMkDirs(URI directoryUri) {
    String cwd = getCurrentWorkingDirectory();
    Stack<URI> fragments = new Stack<>();
    String[] subPaths = directoryUri.getPath().split(SEPARATOR);
    // This uri needs to be normalized so that if it has a trailing separator it is erased.
    URI subUri = normalizeUri(createUri(SEPARATOR, directoryUri.getPath()));
    try {
      for (int i = subPaths.length - 1; i > 0; i--) {
        if (tryChangeWorkingDirectory(subUri.getPath())) {
          break;
        }
        fragments.push(subUri);
        subUri = trimLastFragment(subUri);
      }

      while (!fragments.isEmpty()) {
        URI fragment = fragments.pop();
        makeDirectory(normalizeUri(fragment).getPath());
        changeWorkingDirectory(fragment.getPath());
      }
    } catch (Exception e) {
      throw exception("Found exception trying to recursively create directory " + directoryUri.getPath(), e);
    } finally {
      changeWorkingDirectory(cwd);
    }
  }

  /**
   * @return an {@link FTPFile} that represents the root directory of the ftp server
   */
  private FTPFile createRootFile() {
    FTPFile file = new FTPFile();
    file.setName(ROOT);
    file.setType(DIRECTORY_TYPE);
    file.setTimestamp(Calendar.getInstance());
    return file;
  }

  /**
   * {@inheritDoc} Same as the super method but adding the FTP reply code
   */
  @Override
  public RuntimeException exception(String message, Exception cause) {
    if (cause instanceof FTPConnectionClosedException) {
      cause = new ConnectionException(cause);
    }
    return super.exception(enrichExceptionMessage(message), cause);
  }

  private String enrichExceptionMessage(String message) {
    return format("%s. %s", message, getReplyErrorMessage(client.getReplyCode(), client.getReplyString()));
  }

  /**
   * Given a {@link String}path to a directory relative to the basePath, this method checks if the directory exists and returns an
   * {@link Optional} with the {@link URI} to it, or an empty one if the directory does not exist. To check the existance of the
   * directory it is tried to change the working directory to it. Note that if the check is successful the underlying
   * {@link FtpFileSystem} will have its working directory changed.
   *
   * @param directory directory you want to get the path from
   * @return an {@link Optional} with the path to the directory if it exists, or an empty one if the directory does not exist.
   */
  protected Optional<URI> getUriToDirectory(String directory) {
    URI baseUri = createUri(SEPARATOR, fileSystem.getBasePath());
    URI uri = directory == null ? baseUri : createUri(baseUri.getPath(), directory);
    boolean couldChangeWorkingDir;
    try {
      couldChangeWorkingDir = fileSystem.getClient().changeWorkingDirectory(normalizePath(uri.getPath()));
    } catch (IOException e) {
      couldChangeWorkingDir = false;
    }

    return couldChangeWorkingDir ? Optional.of(uri) : Optional.empty();
  }

  /**
   * {@inheritDoc}
   */
  protected URI getBasePath(FileSystem fileSystem) {
    return createUri(fileSystem.getBasePath());
  }

}
