# SQL Server Database Testing Guide

This document explains how to set up and run SQL Server tests with OJP.

## Prerequisites

1. **Docker** - Required to run SQL Server locally
2. **Microsoft SQL Server JDBC Driver** - Automatically included in dependencies

## Setup Instructions

### 1. Quick start (recommended)

We provide a helper script that starts a local SQL Server in Docker, waits until it is ready, creates the test database and user, installs XA stored procedures, and grants the required permissions. It also cleans up the container on exit.

Steps:

1. From the repository root, make the script executable (first time only):
   - macOS/Linux: `chmod +x scripts/sqlserver/container_runner.sh`
2. Start SQL Server for tests:
   - macOS/Linux: `./scripts/sqlserver/container_runner.sh`

What the script does:
- Runs container `ojp-sqlserver` using `mcr.microsoft.com/mssql/server:2022-latest`
- SA password: `TestPassword123!`
- Exposes port `1433`
- Creates database `defaultdb`
- Creates login/user `testuser` with password `TestPassword123!` and grants `db_owner` on `defaultdb`
- Installs the JDBC XA stored procedures and grants the required EXECUTE permissions in `master`
- Waits until the server is ready before running any SQL
- Keeps running until you press Ctrl+C, then stops and removes the container automatically

After the script says “SQL Server is ready!”, you can proceed to “Run SQL Server Tests”.

### 2. Manual setup (advanced)

If you prefer to run the steps by hand, follow this sequence which mirrors the script exactly.

#### 1. Start SQL Server Database

Use the official Microsoft SQL Server image for testing:

```bash
docker run --name ojp-sqlserver \
  -e ACCEPT_EULA=Y \
  -e SA_PASSWORD=TestPassword123! \
  -e MSSQL_AGENT_ENABLED=true \
  -d -p 1433:1433 mcr.microsoft.com/mssql/server:2022-latest
```

Wait for the database to fully start (may take up to a minute). You can check readiness by running a simple query with sqlcmd inside the container in a loop until it returns without error:

```bash
until docker exec ojp-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P TestPassword123! -C -Q "SELECT 1" >/dev/null 2>&1; do
  echo "Waiting for SQL Server to be ready..."; sleep 1; done
```

#### 2. Create test database, login and user, grant permissions

Run the following commands from your shell (they execute sqlcmd inside the container). Note: no GO batches are needed when using `-Q`.

```bash
# Create test database
docker exec ojp-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P TestPassword123! -C -Q "
CREATE DATABASE defaultdb;"

# Create login for tests
docker exec ojp-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P TestPassword123! -C -Q "
CREATE LOGIN testuser WITH PASSWORD = 'TestPassword123!';"

# Map login to database user and grant db_owner in defaultdb
docker exec ojp-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P TestPassword123! -C -d defaultdb -Q "
CREATE USER testuser FOR LOGIN testuser;
ALTER ROLE db_owner ADD MEMBER testuser;"
```

#### 3. Install JDBC XA stored procedures and grant permissions

The SQL Server JDBC driver provides stored procedures for XA transactions. Install them in `master` and grant permissions to `testuser`:

```bash
# Install XA stored procedures
docker exec ojp-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P TestPassword123! -C -d master -Q "
EXEC sp_sqljdbc_xa_install;"

# Ensure user exists in master and grant EXECUTE on XA procs, add to SqlJDBCXAUser role
docker exec ojp-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P TestPassword123! -C -d master -Q "
IF NOT EXISTS (SELECT * FROM sys.database_principals WHERE name = 'testuser')
BEGIN
  CREATE USER testuser FOR LOGIN testuser;
END

GRANT EXECUTE ON xp_sqljdbc_xa_init TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_start TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_end TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_prepare TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_commit TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_rollback TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_recover TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_forget TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_rollback_ex TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_forget_ex TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_prepare_ex TO testuser;
GRANT EXECUTE ON xp_sqljdbc_xa_init_ex TO testuser;

IF NOT EXISTS (SELECT * FROM sys.database_principals WHERE name = 'SqlJDBCXAUser' AND type = 'R')
BEGIN
  CREATE ROLE [SqlJDBCXAUser];
END
ALTER ROLE [SqlJDBCXAUser] ADD MEMBER testuser;"
```

#### 4. Stop and remove the container

When you finish running tests:

```bash
docker rm -f ojp-sqlserver
```


### 3. Start OJP Server

In a separate terminal:
```bash
cd ojp
mvn verify -pl ojp-server -Prun-ojp-server
```

### 4. Run SQL Server Tests

To run only SQL Server tests:

```bash
cd ojp-jdbc-driver
mvn test -DenableSqlServerTests
```

To run SQL Server tests alongside other databases:

```bash
cd ojp-jdbc-driver
mvn test -DenableSqlServerTests -DenableOracleTests
```

## Test Configuration Files

- `sqlserver_connections.csv` - SQL Server-only connection configuration
- `h2_postgres_mysql_mariadb_oracle_sqlserver_connections.csv` - Multi-database configuration including SQL Server

## Connection String Format

The SQL Server connection string for OJP follows this format:

```
jdbc:ojp[localhost:1059]_sqlserver://localhost:1433;databaseName=defaultdb;encrypt=false;trustServerCertificate=true
```

Where:
- `localhost:1059` - OJP server address and port
- `sqlserver://localhost:1433` - SQL Server instance
- `databaseName=defaultdb` - Target database
- `encrypt=false;trustServerCertificate=true` - SSL configuration for testing

## LOBs special treatment
In SQL Server JDBC driver, advancing a ResultSet invalidates any associated LOBs (Blob, Clob, binary streams). To prevent errors, OJP reads LOB-containing rows one at a time instead of batching multiple rows.

Additionally, LOBs are fully read into memory upfront, which may increase memory usage depending on their size.
