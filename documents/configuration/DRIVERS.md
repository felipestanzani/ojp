# OJP Drop-In Driver Support

OJP Server includes built-in support for open-source database drivers (H2, PostgreSQL, MySQL, MariaDB). For proprietary databases like Oracle, SQL Server, and DB2, you can add drivers without recompiling OJP.

## Overview

Due to licensing restrictions, OJP cannot distribute proprietary JDBC drivers. Instead, OJP provides a "drop-in driver" mechanism that allows you to add these drivers at deployment time.

### Supported Proprietary Databases

- **Oracle Database** - Requires `ojdbc*.jar`
- **Microsoft SQL Server** - Requires `mssql-jdbc-*.jar`
- **IBM DB2** - Requires `db2jcc*.jar`

## How It Works

1. **Driver Directory**: OJP loads JAR files from a configurable directory at startup
2. **Automatic Detection**: Drivers are automatically registered when found
3. **Helpful Messages**: OJP provides clear instructions when a driver is missing

## Setup Instructions

### Option 1: Runnable JAR (Local/VM Deployment)

#### Step 1: Download Drivers

Download the required JDBC driver from the vendor:

- **Oracle**: [Oracle JDBC Downloads](https://www.oracle.com/database/technologies/jdbc-downloads.html)
- **SQL Server**: [Microsoft JDBC Driver Downloads](https://learn.microsoft.com/en-us/sql/connect/jdbc/download-microsoft-jdbc-driver-for-sql-server)
- **DB2**: Contact IBM or check your DB2 installation directory

#### Step 2: Create Drivers Directory

```bash
mkdir -p ./ojp-libs
```

#### Step 3: Copy Driver JARs

```bash
# Example for Oracle
cp ~/Downloads/ojdbc11.jar ./ojp-libs/

# Example for SQL Server
cp ~/Downloads/mssql-jdbc-12.4.2.jre11.jar ./ojp-libs/

# Example for DB2
cp ~/Downloads/db2jcc4.jar ./ojp-libs/
```

#### Step 4: Run OJP Server

```bash
# Default location (./ojp-libs)
java -jar ojp-server-0.3.2-snapshot-shaded.jar

# Custom location
java -Dojp.libs.path=/path/to/ojp-libs -jar ojp-server-0.3.2-snapshot-shaded.jar
```

### Option 2: Docker with Volume Mount

#### Step 1: Download and Prepare Drivers

```bash
# Create external libraries directory on host
mkdir -p ./ojp-libs

# Download and copy drivers
cp ~/Downloads/ojdbc11.jar ./ojp-libs/
```

#### Step 2: Run Container with Volume

```bash
docker run -d \
  -p 1059:1059 \
  -v $(pwd)/ojp-libs:/opt/ojp/ojp-libs \
  rrobetti/ojp:0.3.2-snapshot
```

The container is pre-configured to look for drivers in `/opt/ojp/ojp-libs`.

### Option 3: Docker - Custom Image Build

This option creates a single container image with drivers embedded.

#### Step 1: Download Drivers

```bash
mkdir -p ./ojp-libs
cp ~/Downloads/ojdbc11.jar ./ojp-libs/
```

#### Step 2: Build Custom Image

```bash
# Using the provided Dockerfile.proprietary
docker build -f Dockerfile.proprietary -t my-company/ojp:1.0.0 .
```

#### Step 3: Run Custom Image

```bash
docker run -d -p 1059:1059 my-company/ojp:1.0.0
```

### Option 4: Docker Compose

```yaml
version: '3.8'
services:
  ojp:
    image: rrobetti/ojp:0.3.2-snapshot
    ports:
      - "1059:1059"
    volumes:
      - ./ojp-libs:/opt/ojp/ojp-libs
    environment:
      - OJP_LIBS_PATH=/opt/ojp/ojp-libs
```

## Configuration

### Environment Variables

You can configure the external libraries directory using environment variables:

```bash
# Using environment variable (underscores and uppercase)
export OJP_LIBS_PATH=/custom/path/to/ojp-libs

# Or using JVM system property (dots and lowercase)
java -Dojp.libs.path=/custom/path/to/ojp-libs -jar ojp-server.jar
```

### Default Paths

- **Runnable JAR**: `./ojp-libs` (relative to current directory)
- **Docker**: `/opt/ojp/ojp-libs`

## Verification

When OJP starts, check the logs for driver loading messages:

### Successful Loading

```
INFO  Loading external JDBC drivers...
INFO  Loading driver JAR: ojdbc11.jar
INFO  Successfully loaded 1 driver JAR(s) from: /opt/ojp/ojp-libs
INFO  Oracle JDBC driver loaded successfully
```

### Driver Not Found

```
INFO  Oracle JDBC driver not found. To use Oracle databases:
INFO    1. Download ojdbc*.jar from Oracle (https://www.oracle.com/database/technologies/jdbc-downloads.html)
INFO    2. Place it in: /opt/ojp/ojp-libs
INFO    3. Restart OJP Server
```

## Kubernetes Deployment

### Using ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: ojp-drivers
data: {}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ojp-server
spec:
  template:
    spec:
      containers:
      - name: ojp
        image: rrobetti/ojp:0.3.2-snapshot
        volumeMounts:
        - name: drivers
          mountPath: /opt/ojp/ojp-libs
      volumes:
      - name: drivers
        configMap:
          name: ojp-drivers
```

### Using Init Container (Download from Internal Repository)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ojp-server
spec:
  template:
    spec:
      initContainers:
      - name: download-drivers
        image: curlimages/curl:latest
        command:
        - sh
        - -c
        - |
          curl -o /ojp-libs/ojdbc11.jar $ORACLE_DRIVER_URL
        env:
        - name: ORACLE_DRIVER_URL
          value: "https://your-internal-repo/ojp-libs/ojdbc11.jar"
        volumeMounts:
        - name: drivers
          mountPath: /ojp-libs
      containers:
      - name: ojp
        image: rrobetti/ojp:0.3.2-snapshot
        volumeMounts:
        - name: drivers
          mountPath: /opt/ojp/ojp-libs
      volumes:
      - name: drivers
        emptyDir: {}
```

## Troubleshooting

### Driver Not Loaded

**Problem**: Driver JAR is in the directory but not loaded.

**Solutions**:
1. Check file permissions (must be readable)
2. Verify file has `.jar` extension (case-insensitive)
3. Check logs for loading errors
4. Ensure driver JAR is not corrupted

### Wrong Driver Version

**Problem**: JDBC driver version incompatible with database.

**Solutions**:
1. Download correct driver version for your database
2. Refer to vendor compatibility matrix
3. Test driver independently before adding to OJP

### Multiple Driver Versions

**Problem**: Multiple versions of same driver in directory.

**Recommendation**: Keep only one version to avoid conflicts. Remove older versions.

### Permission Denied

**Problem**: OJP cannot read driver files.

**Solutions**:
```bash
# Fix permissions
chmod 644 ./ojp-libs/*.jar

# For Docker volume mounts
chown -R 1000:1000 ./ojp-libs/
```

## Best Practices

1. **Version Control**: Track which driver versions you're using
2. **Testing**: Test driver independently before production deployment
3. **Security**: Store drivers in a secure internal repository
4. **Updates**: Keep drivers updated for security patches
5. **Documentation**: Document which drivers your deployment requires

## CI/CD Integration

### GitLab CI Example

```yaml
build-ojp-with-drivers:
  stage: build
  script:
    - mkdir drivers
    - curl -o ojp-libs/ojdbc11.jar $ORACLE_DRIVER_URL
    - docker build -f Dockerfile.proprietary -t $CI_REGISTRY_IMAGE:$CI_COMMIT_SHA .
    - docker push $CI_REGISTRY_IMAGE:$CI_COMMIT_SHA
```

### GitHub Actions Example

```yaml
name: Build OJP with Drivers
on: [push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Download drivers
        run: |
          mkdir drivers
          curl -o ojp-libs/ojdbc11.jar ${{ secrets.ORACLE_DRIVER_URL }}
      - name: Build Docker image
        run: docker build -f Dockerfile.proprietary -t ojp:latest .
```

## Security Considerations

1. **Licensing**: Ensure compliance with vendor license terms
2. **Distribution**: Never commit proprietary drivers to public repositories
3. **Access Control**: Restrict access to driver storage locations
4. **Scanning**: Scan driver JARs for vulnerabilities
5. **Compliance**: Follow your organization's software procurement policies

## FAQs

**Q: Can I use multiple proprietary drivers?**  
A: Yes, place all required JAR files in the external libraries directory.

**Q: What if I don't need proprietary drivers?**  
A: No action needed. OJP works with open-source drivers out of the box.

**Q: Can I hot-reload drivers?**  
A: No, OJP loads drivers at startup. Restart the server to load new drivers.

**Q: Does this work with JDBC driver dependencies?**  
A: Yes, place all required JARs (driver + dependencies) in the external libraries directory.

**Q: Can I use this with embedded OJP?**  
A: Yes, set `ojp.libs.path` before initializing OJP components.

## Support

For issues or questions about driver support:
- **GitHub Issues**: [Open an issue](https://github.com/Open-J-Proxy/ojp/issues)
- **Discord**: [Join our community](https://discord.gg/J5DdHpaUzu)
- **Documentation**: [OJP Documentation](https://github.com/Open-J-Proxy/ojp)
