package openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Integration tests for SQL session affinity feature with DB2 database.
 * Tests that declared global temporary tables work correctly across multiple
 * requests by ensuring session affinity.
 */
@Slf4j
public class Db2SessionAffinityIntegrationTest {

    private static boolean isTestDisabled;

    @BeforeAll
    public static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    /**
     * Tests that declared global temporary tables work across multiple SQL statements.
     * This verifies that DECLARE GLOBAL TEMPORARY TABLE triggers session affinity
     * and subsequent operations use the same session/connection.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    public void testTemporaryTableSessionAffinity(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");

        log.info("Testing temporary table session affinity for DB2: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try (Statement stmt = conn.createStatement()) {
            // Create declared global temporary table (this should trigger session affinity)
            // DB2 temporary tables persist for the session - if it already exists, just clear it
            log.debug("Creating DB2 declared global temporary table");
            try {
                stmt.execute("DECLARE GLOBAL TEMPORARY TABLE temp_session_test (id INT, value VARCHAR(100)) ON COMMIT PRESERVE ROWS");
            } catch (SQLException e) {
                // If table already exists (SQLSTATE 42727), just delete existing data
                if ("42727".equals(e.getSQLState())) {
                    log.debug("Temp table already exists, clearing data");
                    try {
                        stmt.execute("DELETE FROM SESSION.temp_session_test");
                    } catch (SQLException deleteEx) {
                        // If delete fails, log and continue - table might be in an inconsistent state
                        log.warn("Failed to clear existing temp table: {}", deleteEx.getMessage());
                    }
                } else {
                    throw e;
                }
            }

            // Insert data into temporary table (should use same session)
            log.debug("Inserting data into temporary table");
            stmt.execute("INSERT INTO SESSION.temp_session_test VALUES (1, 'test_value')");

            // Query temporary table (should use same session)
            log.debug("Querying temporary table");
            ResultSet rs = stmt.executeQuery("SELECT id, value FROM SESSION.temp_session_test");
            
            // Verify data was inserted and retrieved successfully
            Assert.assertTrue("Should have at least one row in temporary table", rs.next());
            Assert.assertEquals("Session data should match", 1, rs.getInt("id"));
            Assert.assertEquals("Session data should match", "test_value", rs.getString("value"));
            
            // Verify no more rows
            Assert.assertFalse("Should have only one row", rs.next());
            
            rs.close();
            
            log.info("DB2 temporary table session affinity test passed");

        } finally {
            conn.close();
        }
    }

    /**
     * Tests that multiple temporary table operations work correctly.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    public void testComplexTemporaryTableOperations(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");

        log.info("Testing complex temporary table operations for DB2: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try (Statement stmt = conn.createStatement()) {
            // Create temporary table
            // DB2 temporary tables persist for the session - if it already exists, just clear it
            log.debug("Creating complex temp table");
            try {
                stmt.execute("DECLARE GLOBAL TEMPORARY TABLE temp_complex (id INT NOT NULL, name VARCHAR(100), amount DECIMAL(10,2)) ON COMMIT PRESERVE ROWS");
            } catch (SQLException e) {
                // If table already exists (SQLSTATE 42727), just delete existing data
                if ("42727".equals(e.getSQLState())) {
                    log.debug("Temp table already exists, clearing data");
                    try {
                        stmt.execute("DELETE FROM SESSION.temp_complex");
                    } catch (SQLException deleteEx) {
                        // If delete fails, log and continue - table might be in an inconsistent state
                        log.warn("Failed to clear existing temp table: {}", deleteEx.getMessage());
                    }
                } else {
                    throw e;
                }
            }

            // Insert multiple rows
            log.debug("Inserting multiple rows");
            stmt.execute("INSERT INTO SESSION.temp_complex VALUES (1, 'Alice', 100.50)");
            stmt.execute("INSERT INTO SESSION.temp_complex VALUES (2, 'Bob', 200.75)");
            stmt.execute("INSERT INTO SESSION.temp_complex VALUES (3, 'Charlie', 150.25)");

            // Update a row
            log.debug("Updating a row");
            stmt.executeUpdate("UPDATE SESSION.temp_complex SET amount = amount + 50.00 WHERE id = 2");

            // Query and verify
            log.debug("Querying temp table");
            ResultSet rs = stmt.executeQuery("SELECT id, name, amount FROM SESSION.temp_complex ORDER BY id");
            
            int rowCount = 0;
            while (rs.next()) {
                rowCount++;
                int id = rs.getInt("id");
                String name = rs.getString("name");
                double amount = rs.getDouble("amount");
                
                if (id == 1) {
                    Assert.assertEquals("Alice", name);
                    Assert.assertEquals(100.50, amount, 0.01);
                } else if (id == 2) {
                    Assert.assertEquals("Bob", name);
                    Assert.assertEquals(250.75, amount, 0.01); // Updated
                } else if (id == 3) {
                    Assert.assertEquals("Charlie", name);
                    Assert.assertEquals(150.25, amount, 0.01);
                }
            }
            
            Assert.assertEquals("Should have 3 rows", 3, rowCount);
            rs.close();
            
            log.info("DB2 complex temporary table operations test passed");

        } finally {
            conn.close();
        }
    }

    /**
     * Tests that temp table persists within same session across transactions.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    public void testTemporaryTablePersistenceAcrossTransactions(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");

        log.info("Testing temporary table persistence across transactions for DB2: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try (Statement stmt = conn.createStatement()) {
            // Create temp table
            // DB2 temporary tables persist for the session - if it already exists, just clear it
            try {
                stmt.execute("DECLARE GLOBAL TEMPORARY TABLE temp_persist (id INT, data VARCHAR(100)) ON COMMIT PRESERVE ROWS");
            } catch (SQLException e) {
                // If table already exists (SQLSTATE 42727), just delete existing data
                if ("42727".equals(e.getSQLState())) {
                    log.debug("Temp table already exists, clearing data");
                    try {
                        stmt.execute("DELETE FROM SESSION.temp_persist");
                    } catch (SQLException deleteEx) {
                        // If delete fails, log and continue - table might be in an inconsistent state
                        log.warn("Failed to clear existing temp table: {}", deleteEx.getMessage());
                    }
                } else {
                    throw e;
                }
            }

            // Start transaction and insert
            conn.setAutoCommit(false);
            stmt.execute("INSERT INTO SESSION.temp_persist VALUES (1, 'in_transaction')");
            conn.commit();

            // Start another transaction and query (should still see the temp table)
            conn.setAutoCommit(false);
            ResultSet rs = stmt.executeQuery("SELECT * FROM SESSION.temp_persist WHERE id = 1");
            Assert.assertTrue("Should find row inserted in previous transaction", rs.next());
            Assert.assertEquals("Data should match", "in_transaction", rs.getString("data"));
            rs.close();
            conn.commit();

            log.info("DB2 temporary table persistence across transactions test passed");

        } finally {
            conn.close();
        }
    }
}
