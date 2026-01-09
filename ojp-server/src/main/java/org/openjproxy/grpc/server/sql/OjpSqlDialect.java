package org.openjproxy.grpc.server.sql;

import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.dialect.*;

/**
 * Supported SQL dialects for the SQL enhancer engine.
 * Maps to Apache Calcite's dialect implementations.
 */
public enum OjpSqlDialect {
    GENERIC(AnsiSqlDialect.DEFAULT),
    POSTGRESQL(PostgresqlSqlDialect.DEFAULT),
    MYSQL(MysqlSqlDialect.DEFAULT),
    ORACLE(OracleSqlDialect.DEFAULT),
    SQL_SERVER(MssqlSqlDialect.DEFAULT),
    H2(H2SqlDialect.DEFAULT);
    
    private final SqlDialect calciteDialect;
    
    OjpSqlDialect(SqlDialect calciteDialect) {
        this.calciteDialect = calciteDialect;
    }
    
    public SqlDialect getCalciteDialect() {
        return calciteDialect;
    }
    
    /**
     * Get dialect by name, case-insensitive.
     * Defaults to GENERIC if not found.
     */
    public static OjpSqlDialect fromString(String name) {
        if (name == null || name.trim().isEmpty()) {
            return GENERIC;
        }
        
        try {
            return valueOf(name.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            return GENERIC;
        }
    }
}
