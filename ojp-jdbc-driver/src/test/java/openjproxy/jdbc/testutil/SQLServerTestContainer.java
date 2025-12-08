package openjproxy.jdbc.testutil;

import org.testcontainers.containers.MSSQLServerContainer;

/**
 * Singleton SQL Server test container for all SQL Server integration tests.
 * This ensures that all tests share the same SQL Server instance to improve test performance
 * and reduce resource usage.
 */
public class SQLServerTestContainer {
    
    // SQL Server Docker image version
    private static final String MSSQL_IMAGE = "mcr.microsoft.com/mssql/server:2022-latest";
    
    private static MSSQLServerContainer<?> container;
    private static boolean isStarted = false;
    private static boolean shutdownHookRegistered = false;
    
    /**
     * Gets or creates the shared SQL Server test container instance.
     * The container is automatically started on first access.
     * 
     * @return the shared MSSQLServerContainer instance
     */
    public static synchronized MSSQLServerContainer<?> getInstance() {
        if (container == null) {
            container = new MSSQLServerContainer<>(MSSQL_IMAGE)
                    .acceptLicense();
        }
        
        if (!isStarted) {
            container.start();
            isStarted = true;
            
            // Add shutdown hook to stop container when JVM exits
            if (!shutdownHookRegistered) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    if (container != null && container.isRunning()) {
                        container.stop();
                    }
                }));
                shutdownHookRegistered = true;
            }
        }
        
        return container;
    }
    
    /**
     * Gets the JDBC URL for connecting to the test container.
     * 
     * @return JDBC URL string
     */
    public static String getJdbcUrl() {
        return getInstance().getJdbcUrl();
    }
    
    /**
     * Gets the username for connecting to the test container.
     * 
     * @return username string
     */
    public static String getUsername() {
        return getInstance().getUsername();
    }
    
    /**
     * Gets the password for connecting to the test container.
     * 
     * @return password string
     */
    public static String getPassword() {
        return getInstance().getPassword();
    }
    
    /**
     * Checks if SQL Server tests are enabled via system property.
     * 
     * @return true if SQL Server tests should run
     */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }
}
