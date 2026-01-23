# SSL/TLS Certificate Configuration with Property Placeholders

## Overview

OJP Server supports property placeholders in JDBC URLs to allow SSL/TLS certificate paths to be configured server-side rather than in the JDBC connection URL. This is particularly useful when:

- Certificate files reside on the OJP server, not on client machines
- You want to centralize certificate management
- You need to avoid hardcoding file paths in connection URLs
- You're using different certificate paths across environments (dev, staging, production)

## How It Works

1. **Client Side (JDBC Driver)**: Configure the JDBC URL in `ojp.properties` with placeholders like `${ojp.server.sslrootcert}`
2. **Server Side (OJP Server)**: Configure the actual certificate paths using JVM properties or environment variables
3. **Runtime**: When the server receives the connection request, it automatically resolves all placeholders before establishing the database connection

## Placeholder Format

Placeholders use the syntax: `${property.name}`

Example:
```
${ojp.server.sslrootcert}
${ojp.server.trustStore}
${ojp.server.oracle.wallet.location}
```

## Configuration Methods

### JVM System Properties

Set properties when starting the OJP server:

```bash
java -jar ojp-server.jar \
  -Dojp.server.sslrootcert=/etc/ojp/certs/ca-cert.pem \
  -Dojp.server.sslcert=/etc/ojp/certs/client-cert.pem \
  -Dojp.server.sslkey=/etc/ojp/certs/client-key.pem
```

### Environment Variables

Set environment variables (property names converted to uppercase with underscores):

```bash
export OJP_SERVER_SSLROOTCERT=/etc/ojp/certs/ca-cert.pem
export OJP_SERVER_SSLCERT=/etc/ojp/certs/client-cert.pem
export OJP_SERVER_SSLKEY=/etc/ojp/certs/client-key.pem

java -jar ojp-server.jar
```

**Note**: JVM properties take precedence over environment variables.

## Database-Specific Examples

### PostgreSQL

PostgreSQL supports SSL/TLS connections with various verification modes.

#### Client Configuration (ojp.properties)

```properties
# Basic SSL with CA certificate verification
ojp.datasource.url=jdbc:ojp[localhost:1059]_postgresql://dbhost:5432/mydb?ssl=true&sslmode=verify-full&sslrootcert=${ojp.server.sslrootcert}

# Full mutual TLS with client certificate
ojp.datasource.url=jdbc:ojp[localhost:1059]_postgresql://dbhost:5432/mydb?ssl=true&sslmode=verify-full&sslrootcert=${ojp.server.sslrootcert}&sslcert=${ojp.server.sslcert}&sslkey=${ojp.server.sslkey}
```

#### Server Configuration

```bash
# JVM properties
java -jar ojp-server.jar \
  -Dojp.server.sslrootcert=/etc/ojp/certs/postgresql/ca-cert.pem \
  -Dojp.server.sslcert=/etc/ojp/certs/postgresql/client-cert.pem \
  -Dojp.server.sslkey=/etc/ojp/certs/postgresql/client-key.pem

# Or environment variables
export OJP_SERVER_SSLROOTCERT=/etc/ojp/certs/postgresql/ca-cert.pem
export OJP_SERVER_SSLCERT=/etc/ojp/certs/postgresql/client-cert.pem
export OJP_SERVER_SSLKEY=/etc/ojp/certs/postgresql/client-key.pem
```

#### PostgreSQL SSL Parameters

| Parameter | Description |
|-----------|-------------|
| `sslrootcert` | Path to CA certificate file |
| `sslcert` | Path to client certificate file |
| `sslkey` | Path to client private key file |
| `sslmode` | SSL mode: `disable`, `require`, `verify-ca`, `verify-full` |

### MySQL / MariaDB

MySQL and MariaDB use Java KeyStore (JKS) files for SSL/TLS.

#### Client Configuration (ojp.properties)

```properties
# SSL with truststore
ojp.datasource.url=jdbc:ojp[localhost:1059]_mysql://dbhost:3306/mydb?useSSL=true&requireSSL=true&trustCertificateKeyStoreUrl=${ojp.server.mysql.truststore}&trustCertificateKeyStorePassword=${ojp.server.mysql.truststorePassword}

# Mutual TLS with client certificate
ojp.datasource.url=jdbc:ojp[localhost:1059]_mysql://dbhost:3306/mydb?useSSL=true&requireSSL=true&trustCertificateKeyStoreUrl=${ojp.server.mysql.truststore}&trustCertificateKeyStorePassword=${ojp.server.mysql.truststorePassword}&clientCertificateKeyStoreUrl=${ojp.server.mysql.keystore}&clientCertificateKeyStorePassword=${ojp.server.mysql.keystorePassword}
```

#### Server Configuration

```bash
# JVM properties
java -jar ojp-server.jar \
  -Dojp.server.mysql.truststore=file:///etc/ojp/certs/mysql/truststore.jks \
  -Dojp.server.mysql.truststorePassword=changeit \
  -Dojp.server.mysql.keystore=file:///etc/ojp/certs/mysql/keystore.jks \
  -Dojp.server.mysql.keystorePassword=changeit

# Or environment variables
export OJP_SERVER_MYSQL_TRUSTSTORE=file:///etc/ojp/certs/mysql/truststore.jks
export OJP_SERVER_MYSQL_TRUSTSTOREPASSWORD=changeit
export OJP_SERVER_MYSQL_KEYSTORE=file:///etc/ojp/certs/mysql/keystore.jks
export OJP_SERVER_MYSQL_KEYSTOREPASSWORD=changeit
```

#### MySQL/MariaDB SSL Parameters

| Parameter | Description |
|-----------|-------------|
| `trustCertificateKeyStoreUrl` | URL to truststore (use `file:///` prefix) |
| `trustCertificateKeyStorePassword` | Truststore password |
| `clientCertificateKeyStoreUrl` | URL to client keystore |
| `clientCertificateKeyStorePassword` | Keystore password |
| `useSSL` | Enable SSL (true/false) |
| `requireSSL` | Require SSL connection (true/false) |

### Oracle

Oracle uses Oracle Wallet for SSL/TLS certificate management.

#### Client Configuration (ojp.properties)

```properties
# Oracle with wallet
ojp.datasource.url=jdbc:ojp[localhost:1059]_oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCPS)(HOST=dbhost)(PORT=2484))(CONNECT_DATA=(SERVICE_NAME=myservice)))

# Alternative format with wallet location in URL
ojp.datasource.url=jdbc:ojp[localhost:1059]_oracle:thin:@dbhost:2484/myservice?oracle.net.wallet_location=${ojp.server.oracle.wallet.location}&oracle.net.ssl_server_dn_match=true
```

#### Server Configuration

```bash
# JVM properties
java -jar ojp-server.jar \
  -Dojp.server.oracle.wallet.location=/etc/ojp/wallet \
  -Doracle.net.tns_admin=/etc/ojp/tns

# Or environment variables
export OJP_SERVER_ORACLE_WALLET_LOCATION=/etc/ojp/wallet
export ORACLE_NET_TNS_ADMIN=/etc/ojp/tns
```

#### Oracle SSL Parameters

| Parameter | Description |
|-----------|-------------|
| `oracle.net.wallet_location` | Path to Oracle Wallet directory |
| `oracle.net.ssl_server_dn_match` | Verify server DN (true/false) |
| `oracle.net.ssl_version` | SSL/TLS version |

**Note**: Oracle Wallet must be configured using Oracle Wallet Manager (`owm`) or `orapki` utility before use.

### SQL Server

SQL Server uses Java truststore for SSL/TLS.

#### Client Configuration (ojp.properties)

```properties
# SQL Server with SSL
ojp.datasource.url=jdbc:ojp[localhost:1059]_sqlserver://dbhost:1433;databaseName=mydb;encrypt=true;trustServerCertificate=false;trustStore=${ojp.server.sqlserver.truststore};trustStorePassword=${ojp.server.sqlserver.truststorePassword}

# With client certificate
ojp.datasource.url=jdbc:ojp[localhost:1059]_sqlserver://dbhost:1433;databaseName=mydb;encrypt=true;trustStore=${ojp.server.sqlserver.truststore};trustStorePassword=${ojp.server.sqlserver.truststorePassword};keyStore=${ojp.server.sqlserver.keystore};keyStorePassword=${ojp.server.sqlserver.keystorePassword}
```

#### Server Configuration

```bash
# JVM properties
java -jar ojp-server.jar \
  -Dojp.server.sqlserver.truststore=/etc/ojp/certs/sqlserver/truststore.jks \
  -Dojp.server.sqlserver.truststorePassword=changeit \
  -Dojp.server.sqlserver.keystore=/etc/ojp/certs/sqlserver/keystore.jks \
  -Dojp.server.sqlserver.keystorePassword=changeit

# Or environment variables
export OJP_SERVER_SQLSERVER_TRUSTSTORE=/etc/ojp/certs/sqlserver/truststore.jks
export OJP_SERVER_SQLSERVER_TRUSTSTOREPASSWORD=changeit
export OJP_SERVER_SQLSERVER_KEYSTORE=/etc/ojp/certs/sqlserver/keystore.jks
export OJP_SERVER_SQLSERVER_KEYSTOREPASSWORD=changeit
```

#### SQL Server SSL Parameters

| Parameter | Description |
|-----------|-------------|
| `encrypt` | Enable encryption (true/false) |
| `trustServerCertificate` | Trust server certificate without validation (true/false) |
| `trustStore` | Path to truststore file |
| `trustStorePassword` | Truststore password |
| `keyStore` | Path to keystore file |
| `keyStorePassword` | Keystore password |

### DB2

DB2 uses SSL/TLS with Java truststore.

#### Client Configuration (ojp.properties)

```properties
# DB2 with SSL
ojp.datasource.url=jdbc:ojp[localhost:1059]_db2://dbhost:50001/mydb:sslConnection=true;sslTrustStoreLocation=${ojp.server.db2.truststore};sslTrustStorePassword=${ojp.server.db2.truststorePassword};

# With client certificate
ojp.datasource.url=jdbc:ojp[localhost:1059]_db2://dbhost:50001/mydb:sslConnection=true;sslTrustStoreLocation=${ojp.server.db2.truststore};sslTrustStorePassword=${ojp.server.db2.truststorePassword};sslKeyStoreLocation=${ojp.server.db2.keystore};sslKeyStorePassword=${ojp.server.db2.keystorePassword};
```

#### Server Configuration

```bash
# JVM properties
java -jar ojp-server.jar \
  -Dojp.server.db2.truststore=/etc/ojp/certs/db2/truststore.jks \
  -Dojp.server.db2.truststorePassword=changeit \
  -Dojp.server.db2.keystore=/etc/ojp/certs/db2/keystore.jks \
  -Dojp.server.db2.keystorePassword=changeit

# Or environment variables
export OJP_SERVER_DB2_TRUSTSTORE=/etc/ojp/certs/db2/truststore.jks
export OJP_SERVER_DB2_TRUSTSTOREPASSWORD=changeit
export OJP_SERVER_DB2_KEYSTORE=/etc/ojp/certs/db2/keystore.jks
export OJP_SERVER_DB2_KEYSTOREPASSWORD=changeit
```

#### DB2 SSL Parameters

| Parameter | Description |
|-----------|-------------|
| `sslConnection` | Enable SSL (true/false) |
| `sslTrustStoreLocation` | Path to truststore file |
| `sslTrustStorePassword` | Truststore password |
| `sslKeyStoreLocation` | Path to keystore file |
| `sslKeyStorePassword` | Keystore password |

## Best Practices

### 1. Use Descriptive Property Names

Use property names that clearly indicate their purpose:

```bash
# Good
-Dojp.server.postgresql.prod.sslrootcert=/etc/certs/prod/ca.pem
-Dojp.server.mysql.dev.truststore=/etc/certs/dev/truststore.jks

# Avoid generic names
-Dojp.server.cert1=/etc/certs/ca.pem
-Dojp.server.cert2=/etc/certs/truststore.jks
```

### 2. Separate Certificates by Database and Environment

Organize certificates in a directory structure:

```
/etc/ojp/certs/
├── postgresql/
│   ├── dev/
│   │   ├── ca-cert.pem
│   │   ├── client-cert.pem
│   │   └── client-key.pem
│   └── prod/
│       ├── ca-cert.pem
│       ├── client-cert.pem
│       └── client-key.pem
├── mysql/
│   ├── dev/
│   │   ├── truststore.jks
│   │   └── keystore.jks
│   └── prod/
│       ├── truststore.jks
│       └── keystore.jks
└── oracle/
    ├── dev/
    │   └── wallet/
    └── prod/
        └── wallet/
```

### 3. Secure File Permissions

Ensure certificate files have appropriate permissions:

```bash
# Certificate files should be readable only by the OJP server user
chmod 400 /etc/ojp/certs/**/ca-cert.pem
chmod 400 /etc/ojp/certs/**/client-cert.pem
chmod 400 /etc/ojp/certs/**/client-key.pem
chmod 400 /etc/ojp/certs/**/*.jks

# Directories should be accessible
chmod 500 /etc/ojp/certs/
chmod 500 /etc/ojp/certs/*/
```

### 4. Use Environment Variables in Production

For production deployments, use environment variables instead of JVM properties to avoid exposing sensitive paths in process listings:

```bash
# In systemd service file
[Service]
Environment="OJP_SERVER_SSLROOTCERT=/etc/ojp/certs/prod/ca-cert.pem"
Environment="OJP_SERVER_SSLCERT=/etc/ojp/certs/prod/client-cert.pem"
Environment="OJP_SERVER_SSLKEY=/etc/ojp/certs/prod/client-key.pem"
```

### 5. Document Your Property Names

Maintain a configuration reference document listing all property placeholders used in your environment:

```markdown
| Property Name | Environment Variable | Description | Example Value |
|---------------|---------------------|-------------|---------------|
| ojp.server.sslrootcert | OJP_SERVER_SSLROOTCERT | PostgreSQL CA cert | /etc/ojp/certs/ca.pem |
| ojp.server.mysql.truststore | OJP_SERVER_MYSQL_TRUSTSTORE | MySQL truststore | file:///etc/ojp/certs/truststore.jks |
```

## Troubleshooting

### Error: Unable to Resolve Placeholder

**Error Message:**
```
Unable to resolve placeholder '${ojp.server.sslrootcert}'. 
Please set JVM property '-Dojp.server.sslrootcert=<value>' or environment variable 'OJP_SERVER_SSLROOTCERT'
```

**Solution:**
Ensure the property is set either as a JVM property or environment variable before starting the OJP server.

### File Not Found Errors

**Symptoms:**
- `FileNotFoundException` for certificate files
- SSL handshake failures

**Solutions:**
1. Verify the file paths are correct
2. Check file permissions (OJP server user must have read access)
3. Ensure files exist on the server where OJP is running (not the client)
4. For URL-based paths (e.g., MySQL), ensure correct format: `file:///absolute/path`

### Certificate Validation Errors

**Symptoms:**
- SSL handshake failures
- Certificate verification errors

**Solutions:**
1. Ensure CA certificate matches the server certificate
2. Check certificate expiration dates
3. Verify hostname matches certificate CN/SAN
4. For PostgreSQL, use `sslmode=verify-full` for full validation
5. Check certificate chain is complete

## Security Considerations

1. **Never commit certificates to version control**: Use placeholders in configuration files
2. **Rotate certificates regularly**: Update property values when certificates are rotated
3. **Limit file access**: Use restrictive file permissions (400 or 440)
4. **Use strong passwords**: For keystores and truststores
5. **Monitor certificate expiration**: Set up alerts for expiring certificates
6. **Validate certificates**: Always use certificate validation in production (`sslmode=verify-full` for PostgreSQL, `trustServerCertificate=false` for SQL Server)

## Additional Resources

- [PostgreSQL SSL Documentation](https://www.postgresql.org/docs/current/libpq-ssl.html)
- [MySQL SSL/TLS Documentation](https://dev.mysql.com/doc/connector-j/8.0/en/connector-j-reference-using-ssl.html)
- [Oracle Wallet Management](https://docs.oracle.com/en/database/oracle/oracle-database/19/dbseg/using-oracle-wallet-manager.html)
- [SQL Server Encryption](https://docs.microsoft.com/en-us/sql/connect/jdbc/using-ssl-encryption)
- [DB2 SSL Configuration](https://www.ibm.com/docs/en/db2/11.5?topic=connections-configuring-ssl-support)
