package org.openjproxy.grpc.server.utils;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for loading JDBC drivers from a configurable directory at runtime.
 * This allows customers to add proprietary drivers without recompiling OJP Server.
 */
@Slf4j
@UtilityClass
public class DriverLoader {
    
    /**
     * Loads all JAR files from the specified directory into the classpath.
     * Creates the directory if it doesn't exist.
     * 
     * @param driversPath Path to the directory containing external library JAR files
     * @return true if loading was successful (even if no JARs found), false on error
     */
    public boolean loadDriversFromPath(String driversPath) {
        if (driversPath == null || driversPath.trim().isEmpty()) {
            log.debug("No external libraries path configured, skipping external driver loading");
            return true;
        }
        
        Path driverDir = Paths.get(driversPath);
        
        // Create directory if it doesn't exist
        if (!Files.exists(driverDir)) {
            try {
                Files.createDirectories(driverDir);
                log.info("Created external libraries directory: {}", driverDir.toAbsolutePath());
                log.info("Place proprietary JDBC drivers (e.g., ojdbc*.jar) in this directory");
                return true;
            } catch (Exception e) {
                log.error("Failed to create external libraries directory: {}", driverDir.toAbsolutePath(), e);
                return false;
            }
        }
        
        // Check if it's a directory
        if (!Files.isDirectory(driverDir)) {
            log.error("External libraries path exists but is not a directory: {}", driverDir.toAbsolutePath());
            return false;
        }
        
        // Find all JAR files in the directory
        List<File> jarFiles = new ArrayList<>();
        File dir = driverDir.toFile();
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
        
        if (files == null || files.length == 0) {
            log.info("No JAR files found in external libraries directory: {}", driverDir.toAbsolutePath());
            return true;
        }
        
        for (File file : files) {
            jarFiles.add(file);
        }
        
        // Load JARs into classpath
        try {
            List<URL> urls = new ArrayList<>();
            for (File jarFile : jarFiles) {
                urls.add(jarFile.toURI().toURL());
                log.info("Loading external library JAR: {}", jarFile.getName());
            }
            
            // Create a new URLClassLoader with the JAR files
            URL[] urlArray = urls.toArray(new URL[0]);
            URLClassLoader classLoader = new URLClassLoader(urlArray, Thread.currentThread().getContextClassLoader());
            
            // Set the context class loader so JDBC DriverManager can find the drivers
            Thread.currentThread().setContextClassLoader(classLoader);
            
            log.info("Successfully loaded {} external library JAR(s) from: {}", jarFiles.size(), driverDir.toAbsolutePath());
            return true;
            
        } catch (Exception e) {
            log.error("Failed to load external library JARs from: {}", driverDir.toAbsolutePath(), e);
            return false;
        }
    }
}
