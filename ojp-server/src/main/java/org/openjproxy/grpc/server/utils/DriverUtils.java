package org.openjproxy.grpc.server.utils;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import static org.openjproxy.grpc.server.Constants.H2_DRIVER_CLASS;
import static org.openjproxy.grpc.server.Constants.MARIADB_DRIVER_CLASS;
import static org.openjproxy.grpc.server.Constants.MYSQL_DRIVER_CLASS;
import static org.openjproxy.grpc.server.Constants.ORACLE_DRIVER_CLASS;
import static org.openjproxy.grpc.server.Constants.POSTGRES_DRIVER_CLASS;
import static org.openjproxy.grpc.server.Constants.SQLSERVER_DRIVER_CLASS;
import static org.openjproxy.grpc.server.Constants.DB2_DRIVER_CLASS;

@Slf4j
@UtilityClass
public class DriverUtils {
    
    /**
     * Register all JDBC drivers supported.
     * @param driversPath Optional path to external libraries directory for user guidance in error messages
     */
    public void registerDrivers(String driversPath) {
        String driverPathMessage = (driversPath != null && !driversPath.trim().isEmpty()) 
            ? driversPath 
            : "./ojp-libs";
            
        //Register open source drivers
        try {
            Class.forName(H2_DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            log.error("Failed to register H2 JDBC driver.", e);
        }
        try {
            Class.forName(POSTGRES_DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            log.error("Failed to register PostgreSQL JDBC driver.", e);
        }
        try {
            Class.forName(MYSQL_DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            log.error("Failed to register MySQL JDBC driver.", e);
        }
        try {
            Class.forName(MARIADB_DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            log.error("Failed to register MariaDB JDBC driver.", e);
        }
        //Register proprietary drivers (if present)
        try {
            Class.forName(ORACLE_DRIVER_CLASS);
            log.info("Oracle JDBC driver loaded successfully");
        } catch (ClassNotFoundException e) {
            log.info("Oracle JDBC driver not found. To use Oracle databases:");
            log.info("  1. Download ojdbc*.jar from Oracle (https://www.oracle.com/database/technologies/jdbc-downloads.html)");
            log.info("  2. Place it in: {}", driverPathMessage);
            log.info("  3. Restart OJP Server");
        }
        try {
            Class.forName(SQLSERVER_DRIVER_CLASS);
            log.info("SQL Server JDBC driver loaded successfully");
        } catch (ClassNotFoundException e) {
            log.info("SQL Server JDBC driver not found. To use SQL Server databases:");
            log.info("  1. Download mssql-jdbc-*.jar from Microsoft (https://learn.microsoft.com/en-us/sql/connect/jdbc/download-microsoft-jdbc-driver-for-sql-server)");
            log.info("  2. Place it in: {}", driverPathMessage);
            log.info("  3. Restart OJP Server");
        }
        try {
            Class.forName(DB2_DRIVER_CLASS);
            log.info("DB2 JDBC driver loaded successfully");
        } catch (ClassNotFoundException e) {
            log.info("DB2 JDBC driver not found. To use DB2 databases:");
            log.info("  1. Download db2jcc*.jar from IBM");
            log.info("  2. Place it in: {}", driverPathMessage);
            log.info("  3. Restart OJP Server");
        }
    }
    
    /**
     * Register all JDBC drivers supported without path information.
     * @deprecated Use {@link #registerDrivers(String)} instead to provide better error messages
     */
    @Deprecated
    public void registerDrivers() {
        // Just call the open source driver registration
        // Proprietary drivers are registered in StatementServiceImpl static block
        try {
            Class.forName(H2_DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            log.error("Failed to register H2 JDBC driver.", e);
        }
        try {
            Class.forName(POSTGRES_DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            log.error("Failed to register PostgreSQL JDBC driver.", e);
        }
        try {
            Class.forName(MYSQL_DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            log.error("Failed to register MySQL JDBC driver.", e);
        }
        try {
            Class.forName(MARIADB_DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            log.error("Failed to register MariaDB JDBC driver.", e);
        }
    }
}
