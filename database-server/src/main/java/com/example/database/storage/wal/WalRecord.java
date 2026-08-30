package com.example.database.storage.wal;

import com.example.database.storage.catalog.ColumnType;

import java.util.List;
import java.util.Objects;

/**
 * One catalog change to log before applying it to {@code CatalogManager}.
 * Column ids are not stored — replay lets the catalog assign them.
 * {@code txnId} groups records until a matching {@link WalOp#COMMIT} is replayed.
 */
public final class WalRecord {

    private final WalOp op;
    private final Integer txnId;
    private final String database;
    private final String table;
    private final String name;
    private final List<ColumnPayload> columns;
    private final List<Integer> columnIds;

    private WalRecord(
            WalOp op,
            Integer txnId,
            String database,
            String table,
            String name,
            List<ColumnPayload> columns,
            List<Integer> columnIds
    ) {
        this.op = Objects.requireNonNull(op, "op");
        this.txnId = txnId;
        this.database = database;
        this.table = table;
        this.name = name;
        this.columns = columns == null ? List.of() : List.copyOf(columns);
        this.columnIds = columnIds == null ? List.of() : List.copyOf(columnIds);
    }

    public static WalRecord commit(int txnId) {
        if (txnId < 1) {
            throw new IllegalArgumentException("txnId must be positive");
        }
        return new WalRecord(WalOp.COMMIT, txnId, null, null, null, null, null);
    }

    /**
     * Barrier marker appended to {@code wal.log} on checkpoint (never replaces prior lines).
     * For {@link WalOp#CHECKPOINT}, {@code txnId} is the high-water mark (may be null if 0).
     */
    public static WalRecord checkpoint(int maxTxnId) {
        if (maxTxnId < 0) {
            throw new IllegalArgumentException("maxTxnId must be >= 0");
        }
        Integer encoded = maxTxnId == 0 ? null : maxTxnId;
        return new WalRecord(WalOp.CHECKPOINT, encoded, null, null, null, null, null);
    }

    public static WalRecord createDatabase(int txnId, String database) {
        return ddl(WalOp.CREATE_DATABASE, txnId, require(database, "database"), null, null, null, null);
    }

    public static WalRecord dropDatabase(int txnId, String database) {
        return ddl(WalOp.DROP_DATABASE, txnId, require(database, "database"), null, null, null, null);
    }

    public static WalRecord createTable(int txnId, String database, String table, List<ColumnPayload> columns) {
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("columns must not be empty");
        }
        return ddl(
                WalOp.CREATE_TABLE,
                txnId,
                require(database, "database"),
                require(table, "table"),
                null,
                columns,
                null
        );
    }

    public static WalRecord dropTable(int txnId, String database, String table) {
        return ddl(
                WalOp.DROP_TABLE,
                txnId,
                require(database, "database"),
                require(table, "table"),
                null,
                null,
                null
        );
    }

    public static WalRecord addColumn(int txnId, String database, String table, ColumnPayload column) {
        return ddl(
                WalOp.ADD_COLUMN,
                txnId,
                require(database, "database"),
                require(table, "table"),
                null,
                List.of(Objects.requireNonNull(column, "column")),
                null
        );
    }

    public static WalRecord dropColumn(int txnId, String database, String table, String column) {
        return ddl(
                WalOp.DROP_COLUMN,
                txnId,
                require(database, "database"),
                require(table, "table"),
                require(column, "column"),
                null,
                null
        );
    }

    public static WalRecord createIndex(int txnId, String database, String table, String index, List<Integer> columnIds) {
        Objects.requireNonNull(columnIds, "columnIds");
        if (columnIds.isEmpty()) {
            throw new IllegalArgumentException("columnIds must not be empty");
        }
        return ddl(
                WalOp.CREATE_INDEX,
                txnId,
                require(database, "database"),
                require(table, "table"),
                require(index, "index"),
                null,
                columnIds
        );
    }

    public static WalRecord dropIndex(int txnId, String index) {
        return ddl(WalOp.DROP_INDEX, txnId, null, null, require(index, "index"), null, null);
    }

    static WalRecord fromParsed(
            WalOp op,
            Integer txnId,
            String database,
            String table,
            String name,
            List<ColumnPayload> columns,
            List<Integer> columnIds
    ) {
        return new WalRecord(op, txnId, database, table, name, columns, columnIds);
    }

    public WalRecord withTxnId(int txnId) {
        if (txnId < 1) {
            throw new IllegalArgumentException("txnId must be positive");
        }
        return new WalRecord(op, txnId, database, table, name, columns, columnIds);
    }

    public WalOp op() {
        return op;
    }

    public Integer txnId() {
        return txnId;
    }

    public String database() {
        return database;
    }

    public String table() {
        return table;
    }

    public String name() {
        return name;
    }

    public List<ColumnPayload> columns() {
        return columns;
    }

    public List<Integer> columnIds() {
        return columnIds;
    }

    private static WalRecord ddl(
            WalOp op,
            int txnId,
            String database,
            String table,
            String name,
            List<ColumnPayload> columns,
            List<Integer> columnIds
    ) {
        if (txnId < 1) {
            throw new IllegalArgumentException("txnId must be positive");
        }
        return new WalRecord(op, txnId, database, table, name, columns, columnIds);
    }

    private static String require(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    /**
     * Column definition in a WAL payload (no assigned id).
     */
    public record ColumnPayload(String name, ColumnType type, boolean nullable) {
        public ColumnPayload {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            if (name.isBlank()) {
                throw new IllegalArgumentException("column name must not be blank");
            }
        }
    }
}
