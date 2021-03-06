package io.lumify.core.bootstrap.lib;

import io.lumify.core.config.Configuration;
import io.lumify.core.exception.LumifyException;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLStreamHandlerFactory;
import java.security.NoSuchAlgorithmException;

public class HdfsLibCacheLoader extends LibLoader {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(HdfsLibCacheLoader.class);
    private static boolean hdfsStreamHandlerInitialized = false;

    @Override
    public void loadLibs(Configuration configuration) {
        LOGGER.info("Loading libs using %s", HdfsLibCacheLoader.class.getName());

        String hdfsLibCacheDirectory = configuration.get(Configuration.HDFS_LIB_CACHE_SOURCE_DIRECTORY, null);
        if (hdfsLibCacheDirectory == null) {
            LOGGER.warn("skipping HDFS libcache. Configuration parameter %s not found", Configuration.HDFS_LIB_CACHE_SOURCE_DIRECTORY);
            return;
        }

        try {
            String hdfsUser = "hadoop";
            FileSystem hdfsFileSystem = getFileSystem(configuration, hdfsUser);
            ensureHdfsStreamHandler(configuration, hdfsUser);
            addFilesFromHdfs(hdfsFileSystem, new Path(hdfsLibCacheDirectory));
        } catch (Exception e) {
            throw new LumifyException(String.format("Could not add HDFS files from %s", hdfsLibCacheDirectory), e);
        }
    }

    private FileSystem getFileSystem(Configuration configuration, String user) {
        FileSystem hdfsFileSystem;
        try {
            String hdfsRootDir = configuration.get(Configuration.HADOOP_URL);
            hdfsFileSystem = FileSystem.get(new URI(hdfsRootDir), configuration.toHadoopConfiguration(), user);
        } catch (Exception ex) {
            throw new LumifyException("Could not open HDFS file system.", ex);
        }
        return hdfsFileSystem;
    }

    private static synchronized void ensureHdfsStreamHandler(Configuration lumifyConfig, String user) {
        if (hdfsStreamHandlerInitialized) {
            return;
        }
        URLStreamHandlerFactory urlStreamHandlerFactory = new HdfsUrlStreamHandlerFactory(lumifyConfig.toHadoopConfiguration(), user);
        LOGGER.info("setting URLStreamHandlerFactory to %s", urlStreamHandlerFactory.getClass().getName());
        URL.setURLStreamHandlerFactory(urlStreamHandlerFactory);
        hdfsStreamHandlerInitialized = true;
    }

    private static void addFilesFromHdfs(FileSystem fs, Path source) throws IOException, NoSuchAlgorithmException {
        LOGGER.debug("adding files from HDFS path %s", source.toString());
        RemoteIterator<LocatedFileStatus> sourceFiles = fs.listFiles(source, true);
        while (sourceFiles.hasNext()) {
            LocatedFileStatus sourceFile = sourceFiles.next();
            if (sourceFile.isDirectory()) {
                continue;
            }
            addLibFile(sourceFile.getPath().toUri().toURL());
        }
    }

}
