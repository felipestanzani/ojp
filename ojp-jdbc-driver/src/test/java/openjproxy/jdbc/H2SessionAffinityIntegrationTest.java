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

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for SQL session affinity feature with H2 database.
 * Tests that temporary tables work correctly across multiple requests
 * by ensuring session affinity.
 */
@Slf4j
public class H2SessionAffinityIntegrationTest {

    private static boolean isH2TestEnabled;

    @BeforeAll
    public static void checkTestConfiguration() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
    }

    /**
     * Tests that temporary tables work across multiple SQL statements.
     * This verifies that CREATE LOCAL TEMPORARY TABLE triggers session affinity
     * and subsequent operations use the same session/connection.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    public void testTemporaryTableSessionAffinity(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");

        log.info("Testing temporary table session affinity for H2: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try (Statement stmt = conn.createStatement()) {
            // Drop temp table if it exists (cleanup from previous run)
            try {
                stmt.execute("DROP TABLE IF EXISTS temp_session_test");
            } catch (SQLException e) {
                // Ignore - table might not exist
            }

            // Create temporary table (this should trigger session affinity)
            log.debug("Creating H2 local temporary table");
            stmt.execute("CREATE LOCAL TEMPORARY TABLE temp_session_test (id INT, \"value\" VARCHAR(100))");

            // Insert data into temporary table (should use same session)
            log.debug("Inserting data into temporary table");
            stmt.execute("INSERT INTO temp_session_test VALUES (1, 'test_value')");

            // Query temporary table (should use same session)
            log.debug("Querying temporary table");
            ResultSet rs = stmt.executeQuery("SELECT id, \"value\" FROM temp_session_test");
            
            // Verify data was inserted and retrieved successfully
            Assert.assertTrue("Should have at least one row in temporary table", rs.next());
            Assert.assertEquals("Session data should match", 1, rs.getInt("id"));
            Assert.assertEquals("Session data should match", "test_value", rs.getString("value"));
            
            // Verify no more rows
            Assert.assertFalse("Should have only one row", rs.next());
            
            rs.close();
            
            log.info("H2 temporary table session affinity test passed");

        } finally {
            conn.close();
        }
    }

    /**
     * Tests that multiple temporary table operations work correctly.
     * This includes creating, inserting, updating, and querying temp tables.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    public void testComplexTemporaryTableOperations(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");

        log.info("Testing complex temporary table operations for H2: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try (Statement stmt = conn.createStatement()) {
            // Cleanup
            try {
                stmt.execute("DROP TABLE IF EXISTS temp_complex");
            } catch (SQLException e) {
                // Ignore
            }

            // Create temporary table
            log.debug("Creating complex temp table");
            stmt.execute("CREATE LOCAL TEMPORARY TABLE temp_complex (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(10,2))");

            // Insert multiple rows
            log.debug("Inserting multiple rows");
            stmt.execute("INSERT INTO temp_complex VALUES (1, 'Alice', 100.50)");
            stmt.execute("INSERT INTO temp_complex VALUES (2, 'Bob', 200.75)");
            stmt.execute("INSERT INTO temp_complex VALUES (3, 'Charlie', 150.25)");

            // Update a row
            log.debug("Updating a row");
            stmt.executeUpdate("UPDATE temp_complex SET amount = amount + 50.00 WHERE id = 2");

            // Query and verify
            log.debug("Querying temp table");
            ResultSet rs = stmt.executeQuery("SELECT id, name, amount FROM temp_complex ORDER BY id");
            
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
                    Assert.assertEquals(250.75, amount, 0.01); // Updated from 200.75 to 250.75
                } else if (id == 3) {
                    Assert.assertEquals("Charlie", name);
                    Assert.assertEquals(150.25, amount, 0.01);
                }
            }
            
            Assert.assertEquals("Should have 3 rows", 3, rowCount);
            rs.close();
            
            log.info("H2 complex temporary table operations test passed");

        } finally {
            conn.close();
        }
    }

    /**
     * Tests that temp table created outside transaction persists within same session.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    public void testTemporaryTablePersistenceAcrossTransactions(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");

        log.info("Testing temporary table persistence across transactions for H2: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try (Statement stmt = conn.createStatement()) {
            // Cleanup
            try {
                stmt.execute("DROP TABLE IF EXISTS temp_persist");
            } catch (SQLException e) {
                // Ignore
            }

            // Create temp table
            stmt.execute("CREATE LOCAL TEMPORARY TABLE temp_persist (id INT, data VARCHAR(100))");

            // Start transaction and insert
            conn.setAutoCommit(false);
            stmt.execute("INSERT INTO temp_persist VALUES (1, 'in_transaction')");
            conn.commit();

            // Start another transaction and query (should still see the temp table)
            conn.setAutoCommit(false);
            ResultSet rs = stmt.executeQuery("SELECT * FROM temp_persist WHERE id = 1");
            Assert.assertTrue("Should find row inserted in previous transaction", rs.next());
            Assert.assertEquals("Data should match", "in_transaction", rs.getString("data"));
            rs.close();
            conn.commit();

            log.info("H2 temporary table persistence across transactions test passed");

        } finally {
            conn.close();
        }
    }
}
