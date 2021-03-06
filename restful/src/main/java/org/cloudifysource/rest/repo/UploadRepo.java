/*******************************************************************************
 * Copyright (c) 2013 GigaSpaces Technologies Ltd. All rights reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *******************************************************************************/
package org.cloudifysource.rest.repo;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.io.FileUtils;
import org.cloudifysource.dsl.internal.CloudifyConstants;
import org.cloudifysource.dsl.internal.CloudifyMessageKeys;
import org.cloudifysource.rest.controllers.RestErrorException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * A class for uploading files and getting uploaded files.
 * @author yael
 *
 */
@Component
public class UploadRepo {
    private static final Logger logger = Logger.getLogger(UploadRepo.class.getName());

    private int uploadSizeLimitBytes = CloudifyConstants.DEFAULT_UPLOAD_SIZE_LIMIT_BYTES;
    private int cleanupTimeoutMillis = CloudifyConstants.DEFAULT_UPLOAD_TIMEOUT_MILLIS;
    private File baseDir = new File(CloudifyConstants.REST_FOLDER);
    private ScheduledExecutorService executor;
    private File restUploadDir;

    /**
     * creating the upload directory and initializing scheduled thread.
     *
     * @throws IOException .
     */
    @PostConstruct
    public void init() throws IOException {
        createUploadDir();
        logger.log(Level.INFO, "created rest uploads directory - " + restUploadDir.getAbsolutePath());
        createScheduledExecutor();
    }

    private void createScheduledExecutor() {
        final CleanUploadDirRunnable cleanupThread =
                new CleanUploadDirRunnable(restUploadDir, cleanupTimeoutMillis);
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(cleanupThread, 0, cleanupTimeoutMillis, TimeUnit.MILLISECONDS);

    }

    /**
     *
     * @throws IOException .
     */
    @PreDestroy
    public void destroy() throws IOException {
        executor.shutdown();
        FileUtils.deleteDirectory(restUploadDir);
    }

    private void reset() {
        executor.shutdownNow();
        createScheduledExecutor();
    }

    private void createUploadDir() throws IOException {
        restUploadDir = new File(baseDir, CloudifyConstants.UPLOADS_FOLDER_NAME);
        if (restUploadDir.exists()) {
            FileUtils.deleteDirectory(restUploadDir);
        }
        restUploadDir.mkdirs();
    }

    private void copyMultipartFileToLocalFile(final MultipartFile srcFile, final File storedFile)
            throws IOException {
        if (srcFile == null) {
            return;
        }
        srcFile.transferTo(storedFile);
        storedFile.deleteOnExit();

    }

    /**
     * Creates a new folder with a randomly generated name (using the UUID class) which holds the uploaded file.
     * The folder located at the main upload folder in {@link #baseDir}.
     * This uploaded file and its folder will be deleted after {@link #cleanupTimeoutMillis} millis.
     *
     * @param fileName
     * 			The name of the uploaded file.
     * 			If null, the multipartFile's original file name will be used as the file's name.
     * @param multipartFile
     *          The file to upload.
     * @return the uploaded key.
     * @throws RestErrorException if the file doesn't end with zip.
     * @throws IOException .
     */
    public String put(final String fileName, final MultipartFile multipartFile) throws IOException, RestErrorException {
        String name = fileName == null ? multipartFile.getOriginalFilename() : fileName;
        // enforce size limit
        long fileSize = multipartFile.getSize();
        if (fileSize > getUploadSizeLimitBytes()) {
            logger.warning("Upload file [" + name + "] size ("
                    + fileSize + ") exceeded the permitted size limit (" + getUploadSizeLimitBytes() + ").");
            throw new RestErrorException(
                    CloudifyMessageKeys.UPLOAD_FILE_SIZE_LIMIT_EXCEEDED.getName(),
                    name, fileSize, getUploadSizeLimitBytes());
        }
        final String dirName = UUID.randomUUID().toString();
        final File srcDir = new File(restUploadDir, dirName);
        srcDir.mkdirs();
        final File storedFile = new File(srcDir, name);
        copyMultipartFileToLocalFile(multipartFile, storedFile);


        logger.finer("File [" + storedFile.getAbsolutePath() + "] uploaded.");
        return dirName;
    }

    /**
     * Gets the file stored in a directory with the given name (uploadDirName).
     *
     * @param key
     *            - the name of the upload file's directory.
     * @return the suitable file or null if a file with that name doesn't exist.
     */
    public File get(final String key) {
        if (key == null) {
            logger.finer("failed to get uploaded file, key is null.");
            return null;
        }
        if (restUploadDir == null) {
            logger.finer("failed to get uploaded file, key is " + key + ", upload directory is null.");
            return null;
        }
        if (!restUploadDir.exists()) {
            logger.finer("failed to get uploaded file. key is " + key
                    + ", upload directory [" + restUploadDir.getAbsolutePath() + "] does not exist.");
            return null;
        }

        logger.finer("Trying to get the uploaded file stored in a directory named - " + key
                + " (under " + restUploadDir.getAbsolutePath() + ").");
        final File dir = new File(restUploadDir, key);
        if (dir.exists()) {
            if (!dir.isDirectory()) {
                logger.finer("The file found is not a directory [" + dir.getAbsolutePath() + "].");
                return null;
            }
            File[] listFiles = dir.listFiles();
            if (listFiles.length > 0) {
                File uploadedFile = listFiles[0];
                logger.finer("Returning the found uploaded file [" + uploadedFile.getAbsolutePath() + "].");
                return uploadedFile;
            } else {
                logger.finer("The directory [" + dir.getAbsolutePath() + "] does not contain an uploaded file.");
            }
        } else {
            logger.finer("No directory with name " + key + " was found at " + restUploadDir.getAbsolutePath());
        }
        return null;
    }

    public File getRestUploadDir() {
        return restUploadDir;
    }

    /**
     * Sets the cleanup timeout and reset the scheduled thread.
     * @param cleanupTimeoutMillis .
     */
    public void resetTimeout(final int cleanupTimeoutMillis) {
        logger.info("reset timeout to " + cleanupTimeoutMillis + " milliseconds.");
        this.setCleanupTimeoutMillis(cleanupTimeoutMillis);
        reset();
    }

    public File getBaseDir() {
        return baseDir;
    }

    public void setBaseDir(final File baseDir) {
        this.baseDir = baseDir;
    }

    public int getCleanupTimeoutMillis() {
        return cleanupTimeoutMillis;
    }

    public void setCleanupTimeoutMillis(final int cleanupTimeoutMillis) {
        this.cleanupTimeoutMillis = cleanupTimeoutMillis;
    }

    public int getUploadSizeLimitBytes() {
        return uploadSizeLimitBytes;
    }

    public void setUploadSizeLimitBytes(final int uploadSizeLimitBytes) {
        this.uploadSizeLimitBytes = uploadSizeLimitBytes;
    }
}
