package org.openjproxy.grpc.client;

import io.grpc.StatusRuntimeException;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.codehaus.plexus.util.ExceptionUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.openjproxy.jdbc.xa.OjpXADataSource;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

@Slf4j
public class MultinodeXAIntegrationTest {
    private static final int THREADS = 5; // Number of worker threads
    private static final int RAMPUP_MS = 50 * 1000; // 50 seconds Ramp-up window in milliseconds

    protected static boolean isTestDisabled;
    private static Queue<Long> queryDurations = new ConcurrentLinkedQueue<>();
    private static AtomicInteger totalQueries = new AtomicInteger(0);
    private static AtomicInteger totalFailedQueries = new AtomicInteger(0);
    private static AtomicInteger nonConnectivityFailedQueries = new AtomicInteger(0);
    private static ExecutorService queryExecutor = new RoundRobinExecutorService(100);

    private static OjpXADataSource xaDataSource;

    @BeforeAll
    public static void checkTestConfiguration() {
        // Check both system property and environment variable
        boolean sysPropEnabled = Boolean.parseBoolean(System.getProperty("multinodeXATestsEnabled", "false"));
        boolean envVarEnabled = Boolean.parseBoolean(System.getenv("MULTINODE_XA_TESTS_ENABLED"));
        isTestDisabled = !(sysPropEnabled || envVarEnabled);
    }

    @SneakyThrows
    public void setUp() throws SQLException {
        queryDurations = new ConcurrentLinkedQueue<>();
        totalQueries = new AtomicInteger(0);
        totalFailedQueries = new AtomicInteger(0);
    }

    @SneakyThrows
    @ParameterizedTest
    @CsvFileSource(resources = "/multinode_connection.csv")
    public void runTests(String driverClass, String url, String user, String password) throws SQLException {
        assumeFalse(isTestDisabled, "Multinode tests are disabled");

        this.setUp();
        // 1. Schema and seeding (using non-XA connection for setup)
        Class.forName(driverClass);
        try (Connection conn = java.sql.DriverManager.getConnection(url, user, password)) {
            Statement stmt = conn.createStatement();
            stmt.execute(
                    "DROP TABLE IF EXISTS order_items CASCADE;" +
                            "DROP TABLE IF EXISTS reviews CASCADE;" +
                            "DROP TABLE IF EXISTS orders CASCADE;" +
                            "DROP TABLE IF EXISTS products CASCADE;" +
                            "DROP TABLE IF EXISTS users CASCADE;" +

                            "CREATE TABLE users (" +
                            "  id SERIAL PRIMARY KEY," +
                            "  username VARCHAR(50)," +
                            "  email VARCHAR(100)" +
                            ");" +
                            "CREATE TABLE products (" +
                            "  id SERIAL PRIMARY KEY," +
                            "  name VARCHAR(100)," +
                            "  price DECIMAL" +
                            ");" +
                            "CREATE TABLE orders (" +
                            "  id SERIAL PRIMARY KEY," +
                            "  user_id INT REFERENCES users(id)," +
                            "  order_date TIMESTAMP DEFAULT now()" +
                            ");" +
                            "CREATE TABLE order_items (" +
                            "  id SERIAL PRIMARY KEY," +
                            "  order_id INT REFERENCES orders(id)," +
                            "  product_id INT REFERENCES products(id)," +
                            "  quantity INT" +
                            ");" +
                            "CREATE TABLE reviews (" +
                            "  id SERIAL PRIMARY KEY," +
                            "  user_id INT REFERENCES users(id)," +
                            "  product_id INT REFERENCES products(id)," +
                            "  rating INT," +
                            "  comment TEXT" +
                            ");"
            );
            stmt.close();

            // Seed data
            System.out.println("Seeding users...");
            conn.createStatement().execute(
                    "INSERT INTO users (username, email) " +
                            "SELECT 'user' || g, 'user' || g || '@example.com' FROM generate_series(1,10000) g"
            );
            System.out.println("Seeding products...");
            conn.createStatement().execute(
                    "INSERT INTO products (name, price) " +
                            "SELECT 'Product ' || g, (random()*1000)::int + 1 FROM generate_series(1,1000) g"
            );
            System.out.println("Seeding orders...");
            conn.createStatement().execute(
                    "INSERT INTO orders (user_id, order_date) " +
                            "SELECT (random()*9999 + 1)::int, NOW() - INTERVAL '1 day' * (random()*365)::int FROM generate_series(1,50000) g"
            );
            System.out.println("Seeding order_items...");
            conn.createStatement().execute(
                    "INSERT INTO order_items (order_id, product_id, quantity) " +
                            "SELECT (random()*49999+1)::int, (random()*999+1)::int, (random()*10+1)::int FROM generate_series(1,100000) g"
            );
            System.out.println("Seeding reviews...");
            conn.createStatement().execute(
                    "INSERT INTO reviews (user_id, product_id, rating, comment) " +
                            "SELECT (random()*9999+1)::int, (random()*999+1)::int, (random()*5+1)::int, 'review ' || g FROM generate_series(1,30000) g"
            );
        }

        // 2. Test timing with ramp-up
        long globalStart = System.nanoTime();
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        int rampupPerThread = (THREADS > 1) ? RAMPUP_MS / (THREADS - 1) : 0;
        for (int t = 0; t < THREADS; t++) {

            final int threadNum = t;
            executor.submit(() -> {
                try {
                    // Ramp-up delay for this thread
                    if (threadNum > 0) Thread.sleep(threadNum * rampupPerThread);
                } catch (InterruptedException ie) {
                    throw new RuntimeException(ie);
                }
                try {
                    runExactQuerySequence(threadNum, driverClass, url, user, password);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        executor.shutdown();
        while (!executor.isTerminated()) {
            Thread.sleep(1000);
        }
        long globalEnd = System.nanoTime();

        // 3. Reporting
        int numQueries = totalQueries.get();
        int numTotalFailures = totalFailedQueries.get();
        int numNonConnectivityFailures = nonConnectivityFailedQueries.get();
        long totalTimeMs = (globalEnd - globalStart) / 1_000_000;
        double avgQueryMs = numQueries > 0
                ? queryDurations.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0
                : 0;

        Thread.sleep(5000); //Wait for any pending queries and logs to flush
        System.out.println("\n=== TEST REPORT ===");
        System.out.println("Total queries executed: " + numQueries);
        System.out.println("Total test duration: " + totalTimeMs + " ms");
        System.out.printf("Average query duration: %.3f ms\n", avgQueryMs);
        System.out.println("Total query failures: " + numTotalFailures);
        System.out.println("Total non-connectivity-related failures: " + numNonConnectivityFailures);
        //Assertions.assertEquals(2160, numQueries);
        Assertions.assertTrue(numTotalFailures < 50, "Expected fewer than 50 failures, but got: " + numTotalFailures);
        Assertions.assertTrue(numNonConnectivityFailures <= 5, "Expected maximum 5 failures not related to connectivity, but got: " + numNonConnectivityFailures);
        Assertions.assertTrue(totalTimeMs < 180000, "Total test time too high: " + totalTimeMs + " ms");
        Assertions.assertTrue(avgQueryMs < 1000.0, "Average query time too high: " + avgQueryMs + " ms");
    }

    @SneakyThrows
    private static void timeAndRun(Callable<Void> query) {
        // Run each query in its own thread to better test multinode balancing
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            long start = System.nanoTime();
            try {
                query.call();
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage() + " \n " + ExceptionUtils.getStackTrace(e));
            }
            long end = System.nanoTime();
            queryDurations.add(end - start);
            totalQueries.incrementAndGet();

        }, queryExecutor);
        future.get();
    }

    @FunctionalInterface
    private interface SqlWork {
        void accept(Connection conn) throws Exception;
    }

    /**
     * Direct XA transaction wrapper: creates a new XAConnection, executes work inside an XA transaction,
     * and properly cleans up resources. No external pooling layer.
     */
    private static void withXATx(
            String driverClass,
            String url,
            String user,
            String password,
            SqlWork work
    ) throws Exception {
        // Load driver if needed
        Class.forName(driverClass);
        
        // Initialize XADataSource if needed
        if (xaDataSource == null) {
            synchronized (MultinodeXAIntegrationTest.class) {
                if (xaDataSource == null) {
                    xaDataSource = new OjpXADataSource();
                    xaDataSource.setUrl(url);
                    xaDataSource.setUser(user);
                    xaDataSource.setPassword(password);
                    log.info("✓ OjpXADataSource initialized (no pooling)");
                }
            }
        }
        
        // Create new XAConnection for this transaction
        XAConnection xaConn = xaDataSource.getXAConnection();
        Connection conn = null;
        XAResource xaRes = null;
        Xid xid = null;
        
        try {
            conn = xaConn.getConnection();
            xaRes = xaConn.getXAResource();
            
            // Generate unique Xid for this transaction
            xid = new SimpleXid(
                100, 
                ("gtrid_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId()).getBytes(),
                ("bqual_" + System.nanoTime()).getBytes()
            );
            
            // Start XA transaction
            xaRes.start(xid, XAResource.TMNOFLAGS);
            
            // Execute work
            work.accept(conn);
            
            // End and prepare XA transaction
            xaRes.end(xid, XAResource.TMSUCCESS);
            int prepareResult = xaRes.prepare(xid);
            
            // Commit if prepared successfully
            if (prepareResult == XAResource.XA_OK) {
                xaRes.commit(xid, false);
            }
            
        } catch (Exception e) {
            // Rollback on error
            if (xaRes != null && xid != null) {
                try {
                    xaRes.end(xid, XAResource.TMFAIL);
                    xaRes.rollback(xid);
                } catch (Exception rollbackEx) {
                    log.warn("XA rollback failed", rollbackEx);
                }
            }
            throw e;
        } finally {
            // Always close connection and XAConnection
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception ignored) {
                }
            }
            if (xaConn != null) {
                try {
                    xaConn.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Simple Xid implementation for XA transactions
     */
    private static class SimpleXid implements Xid {
        private final int formatId;
        private final byte[] gtrid;
        private final byte[] bqual;

        public SimpleXid(int formatId, byte[] gtrid, byte[] bqual) {
            this.formatId = formatId;
            this.gtrid = gtrid;
            this.bqual = bqual;
        }

        @Override
        public int getFormatId() {
            return formatId;
        }

        @Override
        public byte[] getGlobalTransactionId() {
            return gtrid;
        }

        @Override
        public byte[] getBranchQualifier() {
            return bqual;
        }
    }

    private static void runExactQuerySequence(int threadNum, String driverClass, String url, String user, String password) throws SQLException {

        // Transaction Block 1: create user, create order for that user, add order items
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pstUser = conn.prepareStatement(
                            "INSERT INTO users (username, email) VALUES (?, ?) RETURNING id")) {
                        pstUser.setString(1, "txuser_" + threadNum);
                        pstUser.setString(2, "txuser_" + threadNum + "@example.com");
                        ResultSet rs = pstUser.executeQuery();
                        int userId = 0;
                        if (rs.next()) userId = rs.getInt(1);

                        int orderId = 0;
                        try (PreparedStatement pstOrder = conn.prepareStatement(
                                "INSERT INTO orders (user_id, order_date) VALUES (?, NOW()) RETURNING id")) {
                            pstOrder.setInt(1, userId);
                            ResultSet rsOrder = pstOrder.executeQuery();
                            if (rsOrder.next()) orderId = rsOrder.getInt(1);

                            // Add 3 items to this order
                            for (int i = 1; i <= 3; i++) {
                                try (PreparedStatement pstItem = conn.prepareStatement(
                                        "INSERT INTO order_items (order_id, product_id, quantity) VALUES (?, ?, ?)")) {
                                    pstItem.setInt(1, orderId);
                                    pstItem.setInt(2, i);
                                    pstItem.setInt(3, i);
                                    pstItem.execute();
                                }
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage() + " \n " + ExceptionUtils.getStackTrace(e));
            }
            return null;
        });

        // Transaction Block 2: update product price and log a review for the product
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pstUpdate = conn.prepareStatement(
                            "UPDATE products SET price = price + 1 WHERE id = ?")) {
                        pstUpdate.setInt(1, 1);
                        pstUpdate.execute();
                        try (PreparedStatement pstReview = conn.prepareStatement(
                                "INSERT INTO reviews (user_id, product_id, rating, comment) VALUES (?, ?, ?, ?)")) {
                            pstReview.setInt(1, 1);
                            pstReview.setInt(2, 1);
                            pstReview.setInt(3, 4);
                            pstReview.setString(4, "Auto tx review");
                            pstReview.execute();
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage() + " \n " + ExceptionUtils.getStackTrace(e));
            }
            return null;
        });

        // Transaction Block 3: delete and recreate a review
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pstDel = conn.prepareStatement(
                            "DELETE FROM reviews WHERE user_id = ? AND product_id = ?")) {
                        pstDel.setInt(1, 1);
                        pstDel.setInt(2, 1);
                        pstDel.execute();
                    }
                    try (PreparedStatement pstIns = conn.prepareStatement(
                            "INSERT INTO reviews (user_id, product_id, rating, comment) VALUES (?, ?, ?, ?)")) {
                        pstIns.setInt(1, 1);
                        pstIns.setInt(2, 1);
                        pstIns.setInt(3, 5);
                        pstIns.setString(4, "Recreated review");
                        pstIns.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage() + " \n " + ExceptionUtils.getStackTrace(e));
            }
            return null;
        });

        // Queries 4-100: Each query uses direct XA transaction management (new XAConnection per transaction)

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO users (username, email) VALUES (?, ?)")) {
                        pst.setString(1, "userA");
                        pst.setString(2, "userA@example.com");
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO users (username, email) VALUES (?, ?)")) {
                        pst.setString(1, "userB");
                        pst.setString(2, "userB@example.com");
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO users (username, email) VALUES (?, ?)")) {
                        pst.setString(1, "userC");
                        pst.setString(2, "userC@example.com");
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("UPDATE users SET email=? WHERE id=?")) {
                        pst.setString(1, "changed1@example.com");
                        pst.setInt(2, 1);
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("UPDATE users SET email=? WHERE id=?")) {
                        pst.setString(1, "changed2@example.com");
                        pst.setInt(2, 2);
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("UPDATE users SET username=? WHERE id=?")) {
                        pst.setString(1, "userA_updated");
                        pst.setInt(2, 1);
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO users (username, email) VALUES (?, ?)")) {
                        pst.setString(1, "userD");
                        pst.setString(2, "userD@example.com");
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO users (username, email) VALUES (?, ?)")) {
                        pst.setString(1, "userE");
                        pst.setString(2, "userE@example.com");
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("UPDATE users SET email=? WHERE id=?")) {
                        pst.setString(1, "changed3@example.com");
                        pst.setInt(2, 3);
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO users (username, email) VALUES (?, ?)")) {
                        pst.setString(1, "userF");
                        pst.setString(2, "userF@example.com");
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        // -- Product insert/update
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO products (name, price) VALUES (?, ?)")) {
                        pst.setString(1, "ProductA");
                        pst.setBigDecimal(2, new BigDecimal("123.45"));
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO products (name, price) VALUES (?, ?)")) {
                        pst.setString(1, "ProductB");
                        pst.setBigDecimal(2, new BigDecimal("67.89"));
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO products (name, price) VALUES (?, ?)")) {
                        pst.setString(1, "ProductC");
                        pst.setBigDecimal(2, new BigDecimal("250.00"));
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("UPDATE products SET price=? WHERE id=?")) {
                        pst.setBigDecimal(1, new BigDecimal("199.99"));
                        pst.setInt(2, 1);
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("UPDATE products SET price=? WHERE id=?")) {
                        pst.setBigDecimal(1, new BigDecimal("299.99"));
                        pst.setInt(2, 2);
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO products (name, price) VALUES (?, ?)")) {
                        pst.setString(1, "ProductD");
                        pst.setBigDecimal(2, new BigDecimal("111.11"));
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO products (name, price) VALUES (?, ?)")) {
                        pst.setString(1, "ProductE");
                        pst.setBigDecimal(2, new BigDecimal("77.77"));
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("UPDATE products SET name=? WHERE id=?")) {
                        pst.setString(1, "ProductA-Updated");
                        pst.setInt(2, 1);
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("UPDATE products SET name=? WHERE id=?")) {
                        pst.setString(1, "ProductB-Updated");
                        pst.setInt(2, 2);
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("INSERT INTO products (name, price) VALUES (?, ?)")) {
                        pst.setString(1, "ProductF");
                        pst.setBigDecimal(2, new BigDecimal("333.33"));
                        pst.execute();
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        // Various SELECT queries

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM orders WHERE id=?")) {
                        pst.setInt(1, 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM orders WHERE user_id=?")) {
                        pst.setInt(1, 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM orders")) {
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt(1);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM orders WHERE user_id=?")) {
                        pst.setInt(1, 2);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt(1);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM orders ORDER BY order_date DESC LIMIT 10")) {
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM order_items WHERE order_id=?")) {
                        pst.setInt(1, 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM order_items WHERE id=?")) {
                        pst.setInt(1, 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM order_items")) {
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt(1);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT count(*) FROM order_items WHERE order_id=?")) {
                        pst.setInt(1, 1);
                        log.info("select count of quantities from order_items for order_id = " + 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                Long sum = rs.getLong(1);
                                log.info("count returned = " + sum);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT SUM(quantity) FROM order_items WHERE order_id=?")) {
                        pst.setInt(1, 1);
                        log.info("select sum of quantities from order_items for order_id = " + 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getObject(1);
                                if (rs.wasNull()) {
                                    log.info("Result was null");
                                } else {
                                    Long sum = rs.getLong(1);
                                    log.info("sum returned = " + sum);
                                }
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT AVG(quantity) FROM order_items")) {
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getDouble(1);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM reviews WHERE id=?")) {
                        pst.setInt(1, 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM reviews WHERE product_id=?")) {
                        pst.setInt(1, 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM reviews")) {
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt(1);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT AVG(rating) FROM reviews WHERE product_id=?")) {
                        pst.setInt(1, 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getDouble(1);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM reviews WHERE user_id=?")) {
                        pst.setInt(1, 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT u.username, o.id FROM users u JOIN orders o ON u.id = o.user_id WHERE u.id=?")) {
                        pst.setInt(1, 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getString("username");
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT oi.id, p.name FROM order_items oi JOIN products p ON oi.product_id=p.id WHERE oi.order_id=?")) {
                        pst.setInt(1, 1);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("id");
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT user_id, COUNT(*) FROM orders GROUP BY user_id")) {
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt("user_id");
                                rs.getInt(2);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT AVG(quantity) FROM order_items")) {
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getDouble(1);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        // Loop of 7 * 3 queries
        for (int i = 0; i < 7; i++) {
            final int idx = i + 2;

            timeAndRun(() -> {
                try {
                    withXATx(driverClass, url, user, password, conn -> {
                        try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM orders WHERE id=?")) {
                            pst.setInt(1, idx);
                            try (ResultSet rs = pst.executeQuery()) {
                                while (rs.next()) {
                                    rs.getInt("id");
                                }
                            }
                        }
                    });
                } catch (Exception e) {
                    incrementFailures(e);
                    System.err.println("Query failed: " + e.getMessage());
                }
                return null;
            });
            timeAndRun(() -> {
                try {
                    withXATx(driverClass, url, user, password, conn -> {
                        try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM users WHERE id=?")) {
                            pst.setInt(1, idx);
                            try (ResultSet rs = pst.executeQuery()) {
                                while (rs.next()) {
                                    rs.getInt("id");
                                }
                            }
                        }
                    });
                } catch (Exception e) {
                    incrementFailures(e);
                    System.err.println("Query failed: " + e.getMessage());
                }
                return null;
            });
            timeAndRun(() -> {
                try {
                    withXATx(driverClass, url, user, password, conn -> {
                        try (PreparedStatement pst = conn.prepareStatement("SELECT * FROM products WHERE id=?")) {
                            pst.setInt(1, idx);
                            try (ResultSet rs = pst.executeQuery()) {
                                while (rs.next()) {
                                    rs.getInt("id");
                                }
                            }
                        }
                    });
                } catch (Exception e) {
                    incrementFailures(e);
                    System.err.println("Query failed: " + e.getMessage());
                }
                return null;
            });
        }

        // Final simple counts
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM users")) {
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt(1);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM orders")) {
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt(1);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });
        timeAndRun(() -> {
            try {
                withXATx(driverClass, url, user, password, conn -> {
                    try (PreparedStatement pst = conn.prepareStatement("SELECT COUNT(*) FROM reviews")) {
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) {
                                rs.getInt(1);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                incrementFailures(e);
                System.err.println("Query failed: " + e.getMessage());
            }
            return null;
        });

        // 5 heavy queries, all with direct XA transactions
        for (int i = 0; i < 5; i++) {
            timeAndRun(() -> {
                try {
                    withXATx(driverClass, url, user, password, conn -> {
                        try (PreparedStatement pst = conn.prepareStatement(
                                "SELECT" +
                                        "  u.id," +
                                        "  COUNT(o.id) AS num_orders," +
                                        "  SUM(oi.quantity) AS total_quantity" +
                                        " FROM users u" +
                                        " JOIN orders o ON u.id = o.user_id" +
                                        " JOIN order_items oi ON o.id = oi.order_id" +
                                        " GROUP BY u.id"
                        )) {
                            try (ResultSet rs = pst.executeQuery()) {
                                while (rs.next()) {
                                    rs.getInt(1);
                                }
                            }
                        }
                    });
                } catch (Exception e) {
                    incrementFailures(e);
                    System.err.println("Query failed: " + e.getMessage());
                }
                return null;
            });
        }
    }

    private static void incrementFailures(Exception e) {
        totalFailedQueries.incrementAndGet();
        if (!(e instanceof StatusRuntimeException && e.getMessage().contains("UNAVAILABLE"))) {
            //Errors non related to the fact that a node is down
            nonConnectivityFailedQueries.incrementAndGet();
        }
    }
}
