
### Prerequisites
- Java 22 or higher
- Maven 3.9+
- Docker (for running databases and OJP server)

### Getting Started

1. **Clone the repository**
   ```bash
   git clone https://github.com/Open-J-Proxy/ojp.git
   cd ojp
   ```

2. **Build the project**
   ```bash
   mvn clean install -DskipTests
   ```

3. **Start OJP server** (required for running tests)
   ```bash
   mvn verify -pl ojp-server -Prun-ojp-server
   ```

4. **Run integration tests**
   Navigate to the ojp-jdbc-driver folder first:
   ```bash
   cd ojp-jdbc-driver
   mvn test -DenableH2Tests=true
   ```
**Note:** By default, all database tests (including H2) are disabled. To run specific database tests locally, use the appropriate enable flags (e.g., `-DenableH2Tests=true`, `-DenablePostgresTests=true`). To run the full set of integration tests, you have to run all the databases locally. Follow the instructions at [Run Local Databases](../../documents/environment-setup/run-local-databases.md)

### Databases with integration tests
We have comprehensive JDBC integration tests with OJP for the following databases:
- Postgres
- MariaDB
- MySQL
- CockroachDB
- Oracle
- SQL Server
- DB2
- H2

The free and open source databases (H2, Postgres, MySQL, MariaDB and CockroachDB) JDBC drivers are packed with OJP. All database tests are disabled by default and must be explicitly enabled using their respective flags (e.g., `-DenableH2Tests=true`). In CI pipelines, only H2 tests run in the Main CI workflow as a fast fail-fast mechanism. For proprietary databases like Oracle and SQL Server, see specific sections below.

### Oracle Database Setup (Optional)
Oracle integration tests require the Oracle JDBC driver and due to licensing restrictions we do not pack it with OJP.
For detailed Oracle setup instructions, see [Oracle Testing Guide](../../documents/environment-setup/oracle-testing-guide.md).

### SQL Server Database Setup (Optional)
SQL Server integration tests use the Microsoft SQL Server JDBC driver which is not included in OJP dependencies.
For detailed SQL Server setup instructions, see [SQL Server Testing Guide](../../documents/environment-setup/sqlserver-testing-guide.md).

### DB2 Database Setup (Optional)
DB2 integration tests use the IBM JDBC driver which is not included in OJP dependencies.
For detailed DB2 instructions, see [DB2 Testing Guide](../../documents/environment-setup/db2-testing-guide.md).

### CockroachDB Database Setup (Optional)
CockroachDB integration tests use the PostgreSQL JDBC driver which is already included in OJP dependencies.
For detailed CockroachDB setup instructions, see [CockroachDB Testing Guide](../../documents/environment-setup/cockroachdb-testing-guide.md).


### Testing Configuration
- Test connection configurations are stored in CSV files under `test/resources`
- File naming indicates supported databases (e.g., `h2_postgres_connections.csv` supports H2 and PostgreSQL)
- Integration tests run against each configured connection
- See [run-local-databases.md](documents/environment-setup/run-local-databases.md) for local database setup

### Test Options
- `-DenableH2Tests` - Enable H2 integration tests (disabled by default)
- `-DenablePostgresTests` - Enable PostgreSQL integration tests (disabled by default)
- `-DenableMySQLTests` - Enable MySQL integration tests (disabled by default)
- `-DenableMariaDBTests` - Enable MariaDB integration tests (disabled by default)
- `-DenableCockroachDBTests` - Enable CockroachDB integration tests (disabled by default)
- `-DenableOracleTests` - Enable Oracle integration tests (disabled by default, requires manual Oracle JDBC driver setup)
- `-DenableSqlServerTests` - Enable SQL Server integration tests (disabled by default)

### Workflow Ordering and Fail-Fast Strategy
The CI workflows are organized in a hierarchical order to save CI cycles:

1. **Main CI** - Runs first and only executes H2 database tests
   - H2 is an embedded database that requires no external services
   - Runs the fastest and serves as a fail-fast mechanism
   - If basic functionality is broken, this workflow fails immediately without running expensive database setups
   - Only enables H2 tests with `-DenableH2Tests=true`

2. **Specialized Workflows** - Run only after Main CI succeeds
   - Multinode Integration Tests
   - Oracle Database Testing
   - SQL Server Integration Tests
   
This approach ensures that:
- Quick feedback on broken code (H2 tests run in seconds)
- Resource efficiency (expensive database setups only run if basic tests pass)
- Reduced CI costs and execution time
- Early detection of major issues before running full test suite

### Contributing code
1. Fork the repository
2. Create a feature branch
3. Make your changes with tests
4. Ensure all tests pass
5. Submit a pull request

For questions or support, please open an issue on GitHub.
