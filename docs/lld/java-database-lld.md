```mermaid
classDiagram
    direction TB

    class DatabaseServer {
        <<concrete>>
        -StorageEngine storageEngine
        -NetworkModule networkModule
        -QueryProcessor queryProcessor
        +start()
        +stop()
    }

    class NetworkModule {
        <<interface>>
        +start()
        +stop()
    }

    class RequestHandler {
        <<interface>>
        +handle(Request request) Response
    }

    class DefaultRequestHandler {
        <<concrete>>
        -QueryProcessor queryProcessor
        +handle(Request request) Response
    }

    class QueryProcessor {
        <<interface>>
        +execute(String query) QueryResult
        +executeText(String query) String
        +endConnectionSession()
    }

    class DefaultQueryProcessor {
        <<concrete>>
        -QueryLexer lexer
        -QueryParser parser
        -QueryPlanner planner
        -QueryDispatcher queryDispatcher
        -StorageEngine storageEngine
        +execute(String query) QueryResult
    }

    class QueryAnalyser {
        <<interface>>
        +analyse(AstNode ast) AnalyzedQuery
    }

    class DefaultQueryAnalyser {
        <<concrete>>
        -CatalogManager catalogManager
        +analyse(AstNode ast) AnalyzedQuery
    }

    class AnalyzedQuery {
        <<interface>>
    }

    class AnalyzedCreateTable {
        <<concrete>>
        -String database
        -String table
        -List~ColumnMetadata~ columns
        -Optional~String~ primaryKeyColumn
    }

    class AnalyzedCreateDatabase {
        <<concrete>>
        -String database
    }

    class AnalyzedDropTable {
        <<concrete>>
        -String database
        -String table
    }

    class AnalyzedDropDatabase {
        <<concrete>>
        -String database
    }

    class AnalyzedAddColumn {
        <<concrete>>
        -String database
        -String table
        -ColumnMetadata column
    }

    class AnalyzedDropColumn {
        <<concrete>>
        -String database
        -String table
        -String column
    }

    class AnalyzedCreateIndex {
        <<concrete>>
        -String database
        -String table
        -String index
        -List~Integer~ columnIds
    }

    class AnalyzedDropIndex {
        <<concrete>>
        -String database
        -String table
        -String index
    }

    class AnalyzedDescribeTable {
        <<concrete>>
        -String database
        -String table
    }

    class AnalyzedShowDatabases {
        <<concrete>>
    }

    class AnalyzedShowTables {
        <<concrete>>
        -String database
    }

    class ResolvedProjection {
        <<concrete>>
        -OptionalInt columnId
        -ColumnType type
        +column(ColumnMetadata) ResolvedProjection
        +literal(ColumnType, Object) ResolvedProjection
    }

    class ResolvedInsertValue {
        <<concrete>>
        -int columnId
        -ColumnType type
        -Object value
    }

    class ResolvedAssignment {
        <<concrete>>
        -int columnId
        -ColumnType type
        -Expression value
    }

    class AnalyzedSelect {
        <<concrete>>
        -String database
        -String table
        -List~ResolvedProjection~ projections
        -Expression where
        -List~IndexMetadata~ indexes
    }

    class AnalyzedInsert {
        <<concrete>>
        -String database
        -String table
        -List~ResolvedInsertValue~ values
    }

    class AnalyzedUpdate {
        <<concrete>>
        -String database
        -String table
        -List~ResolvedAssignment~ assignments
        -Expression where
    }

    class AnalyzedDelete {
        <<concrete>>
        -String database
        -String table
        -Expression where
    }

    class UnresolvedQuery {
        <<concrete>>
        -AstNode source
    }

    class AnalysisException {
        <<concrete>>
        +toResponse() String
    }

    class QueryType {
        <<enumeration>>
        CREATE_TABLE
        DROP_TABLE
        CREATE_DATABASE
        DROP_DATABASE
        ADD_COLUMN
        DROP_COLUMN
        CREATE_INDEX
        DROP_INDEX
        BEGIN
        COMMIT
        ROLLBACK
        CHECKPOINT
        DESCRIBE_TABLE
        SHOW_DATABASES
        SHOW_TABLES
        SELECT
        INSERT
        UPDATE
        DELETE
        UNRESOLVED
    }

    class ExecutionPlan {
        <<interface>>
        +queryType() QueryType
    }

    class CreateTablePlan {
        <<concrete>>
        -String database
        -String table
        -List~ColumnMetadata~ columns
        -Optional~String~ primaryKeyColumn
    }

    class CreateDatabasePlan {
        <<concrete>>
        -String database
    }

    class DropTablePlan {
        <<concrete>>
        -String database
        -String table
    }

    class DropDatabasePlan {
        <<concrete>>
        -String database
    }

    class AddColumnPlan {
        <<concrete>>
        -String database
        -String table
        -ColumnMetadata column
    }

    class DropColumnPlan {
        <<concrete>>
        -String database
        -String table
        -String column
    }

    class CreateIndexPlan {
        <<concrete>>
        -String database
        -String table
        -String index
        -List~Integer~ columnIds
    }

    class DropIndexPlan {
        <<concrete>>
        -String database
        -String table
        -String index
    }

    class AccessPath {
        <<concrete>>
        -Kind kind
        -String indexName
        +tableScan() AccessPath
        +indexScan(String) AccessPath
    }

    class SelectPlan {
        <<concrete>>
        -String database
        -String table
        -List~ResolvedProjection~ projections
        -AccessPath accessPath
    }

    class InsertPlan {
        <<concrete>>
        -String database
        -String table
        -List~ResolvedInsertValue~ values
    }

    class UpdatePlan {
        <<concrete>>
        -String database
        -String table
        -List~ResolvedAssignment~ assignments
        -AccessPath accessPath
    }

    class DeletePlan {
        <<concrete>>
        -String database
        -String table
        -AccessPath accessPath
    }

    class UnresolvedPlan {
        <<concrete>>
        -UnresolvedQuery source
    }

    class QueryPlanner {
        <<interface>>
        +plan(AnalyzedQuery analyzed) ExecutionPlan
    }

    class DefaultQueryPlanner {
        <<concrete>>
        +plan(AnalyzedQuery analyzed) ExecutionPlan
    }

    class QueryResult {
        <<concrete>>
        +ok() QueryResult
        +okEcho(String message) QueryResult
        +error(String message) QueryResult
        +resultSet(columns, rows) QueryResult
        +hasResultSet() boolean
        +toResponse() String
        +toWireResponse() WireResponse
    }

    class ExecutionException {
        <<concrete>>
        +toResponse() String
    }

    class QueryExecutor {
        <<interface>>
        +execute(ExecutionPlan plan) QueryResult
    }

    class CommandExecutor {
        <<concrete>>
        -CatalogManager catalogManager
        -TransactionManager transactionManager
        -LockManager lockManager
        -WALManager walManager
        -TableStore tableStore
        +execute(ExecutionPlan plan) QueryResult
    }

    class DescribeExecutor {
        <<concrete>>
        -CatalogManager catalogManager
        +execute(ExecutionPlan plan) QueryResult
    }

    class VolcanoExecutor {
        <<concrete>>
        -TableStore tableStore
        -LockManager lockManager
        -TransactionManager transactionManager
        -CatalogManager catalogManager
        +execute(ExecutionPlan plan) QueryResult
    }

    class VectorizedExecutor {
        <<concrete>>
        +execute(ExecutionPlan plan) QueryResult
    }

    class BatchExecutor {
        <<concrete>>
        +execute(ExecutionPlan plan) QueryResult
    }

    class Tuple {
        <<concrete>>
        -long rowId
        -Object[] values
    }

    class ExpressionEvaluator {
        <<concrete>>
        -Map~String, Integer~ columnIdsByName
        +evaluate(Expression expression, Tuple tuple) Object
        +matches(Expression where, Tuple tuple) boolean
    }

    class VolcanoOperator {
        <<interface>>
        +open()
        +next() Tuple
        +close()
    }

    class ExecutorRegistry {
        <<concrete>>
        +register(QueryType type, QueryExecutor executor)
        +get(QueryType type) QueryExecutor
    }

    class QueryDispatcher {
        <<concrete>>
        -ExecutorRegistry registry
        +execute(ExecutionPlan plan) QueryResult
    }

    class StorageEngine {
        <<interface>>
        +start()
        +stop()
        +dataDirectory() DataDirectory
        +catalogManager() CatalogManager
        +transactionManager() TransactionManager
        +lockManager() LockManager
        +walManager() WALManager
        +tableStore() TableStore
        +bufferPool() BufferPool
    }

    class DefaultStorageEngine {
        <<concrete>>
        -DataDirectory dataDirectory
        -PhysicalStorage physicalStorage
        -DefaultCatalogManager catalogManager
        -WALManager walManager
        -TransactionManager transactionManager
        -LockManager lockManager
        -CheckpointScheduler checkpointScheduler
        -TableStore tableStore
        -BufferPool bufferPool
        -boolean checkpointEnabled
        +start()
        +stop()
        +dataDirectory() DataDirectory
        +catalogManager() CatalogManager
        +transactionManager() TransactionManager
        +lockManager() LockManager
        +walManager() WALManager
        +tableStore() TableStore
        +bufferPool() BufferPool
    }

    class DataDirectory {
        <<concrete>>
        -Path root
        +defaults() DataDirectory
        +root() Path
        +ensureExists()
    }

    class PhysicalStorage {
        <<interface>>
        +pageSize() int
        +create(String file)
        +delete(String file)
        +exists(String file) boolean
        +read(String file) byte[]
        +write(String file, byte[] bytes)
        +read(String file, long offset, int length) byte[]
        +write(String file, long offset, byte[] bytes)
        +byteLength(String file) long
        +flush(String file)
        +createDirectory(String path)
        +deleteDirectory(String path)
        +listDirectories(String path) List~String~
    }

    class DefaultPhysicalStorage {
        <<concrete>>
        -Path root
        -int pageSize
        +pageSize() int
        +create / delete / exists / read / write / byteLength / flush
        +createDirectory / deleteDirectory / listDirectories
    }

    class PhysicalStorageException {
        <<concrete>>
    }

    class CatalogManager {
        <<interface>>
        +getTable(String database, String table) Optional~TableMetadata~
        +tableExists(String database, String table) boolean
        +createTable(TableMetadata table) TableMetadata
        +dropTable(String database, String table)
        +addColumn(String database, String table, ColumnMetadata column) TableMetadata
        +dropColumn(String database, String table, String column) TableMetadata
        +createIndex(String database, String table, IndexMetadata index) TableMetadata
        +dropIndex(String index)
        +allTables() List~TableMetadata~
        +databaseExists(String name) boolean
        +allDatabases() List~String~
        +createDatabase(String name)
        +dropDatabase(String name)
        +load()
    }

    class DefaultCatalogManager {
        <<concrete>>
        -Map~String, Map~String, TableMetadata~~ tablesByDatabase
        -Set~String~ databaseNames
        -CatalogStore catalogStore
        -int nextTableId
        -Object stateLock
            // restoreSnapshot skips no-op; synchronized with getTable (no empty-map window)
        +getTable(String database, String table) Optional~TableMetadata~
        +tableExists(String database, String table) boolean
        +createTable(TableMetadata table) TableMetadata
        +dropTable(String database, String table)
        +addColumn(String database, String table, ColumnMetadata column) TableMetadata
        +dropColumn(String database, String table, String column) TableMetadata
        +createIndex(String database, String table, IndexMetadata index) TableMetadata
        +dropIndex(String index)
        +allTables() List~TableMetadata~
        +databaseExists(String name) boolean
        +allDatabases() List~String~
        +createDatabase(String name)
        +dropDatabase(String name)
        +load()
    }

    class TableMetadata {
        <<concrete>>
        -OptionalInt tableId
        -String database
        -String name
        -List~ColumnMetadata~ columns
        -List~IndexMetadata~ indexes
        -Optional~String~ primaryKeyColumn
        +define(String database, String name, List~ColumnMetadata~ columns) TableMetadata
    }

    class IndexMetadata {
        <<concrete>>
        -String name
        -List~Integer~ columnIds
        -boolean unique
        +define(String name, List~Integer~ columnIds) IndexMetadata
    }

    class ColumnMetadata {
        <<concrete>>
        -OptionalInt columnId
        -String name
        -ColumnType type
        -boolean nullable
        +define(String name, ColumnType type) ColumnMetadata
    }

    class ColumnType {
        <<enumeration>>
        INT
        VARCHAR
        BOOLEAN
    }

    class CatalogException {
        <<concrete>>
    }

    class CatalogStore {
        <<interface>>
        +load() List~TableMetadata~
        +saveTable(TableMetadata table)
        +dropTable(String database, String table)
        +loadDatabases() List~String~
        +createDatabase(String name)
        +dropDatabase(String name)
    }

    class JsonCatalogStore {
        <<concrete>>
        +load() List~TableMetadata~
        +saveTable(TableMetadata table)
        +dropTable(String database, String table)
        +loadDatabases() List~String~
        +createDatabase(String name)
        +dropDatabase(String name)
    }

    class TableStore {
        <<interface>>
        +insert(String database, String table, Object[] values) Tuple
        +scan(String database, String table) Iterator~Tuple~
        +update(String database, String table, long rowId, Object[] values)
        +delete(String database, String table, long rowId)
        +dropTable(String database, String table)
        +dropDatabase(String database)
        +prepareTable(String database, String table)
    }

    class TableHeapFiles {
        <<concrete>>
        +ibdPath(String database, String table) String
    }

    class FileTableStore {
        <<concrete>>
        -CatalogManager catalogManager
        -BufferPool bufferPool
        -PhysicalStorage physicalStorage
        +insert / scan / update / delete / findByRowId / restoreRow
        +prepareTable / dropTable / dropDatabase / setSuppressSideEffects
            // restoreRow: IndexMaintainer unless suppressSideEffects; redo uses INDEX_* WAL
    }

    class InMemoryTableStore {
        <<concrete>>
        -Map~String, List~Tuple~~ tables
        -AtomicLong nextRowId
        +insert / scan / update / delete / dropTable / dropDatabase
    }

    class PageLayout {
        <<concrete>>
        +DEFAULT_PAGE_SIZE: int
        +MAGIC / HEADER_SIZE / SLOT_SIZE
        +OFF_MAGIC … OFF_LSN_RESERVED
    }

    class PageType {
        <<enumeration>>
        HEAP
        HEAP_META
        INDEX_META
        INDEX_LEAF
        INDEX_INTERNAL
    }

    class HeapMetaPage {
        <<concrete>>
        +createEmpty(int pageId, int pageSize) HeapMetaPage
        +wrap(byte[] data) HeapMetaPage
        +pageSize() int
        +setPageSize(int pageSize) void
    }

    class IndexMetaPage {
        <<concrete>>
        +createEmpty(int pageId, int pageSize) IndexMetaPage
        +wrap(byte[] data) IndexMetaPage
        +rootPageId() int
        +height() int
        +pageSize() int
        +setRoot(int rootPageId, int height) void
        +setPageSize(int pageSize) void
    }

    class Rid {
        <<record>>
        +int pageId
        +int slotId
    }

    class RidMap {
        <<interface>>
        +put(long rowId, Rid rid)
        +get(long rowId) Optional~Rid~
        +remove(long rowId)
        +clear()
    }

    class InMemoryRidMap {
        <<concrete>>
        -ConcurrentHashMap~Long, Rid~ byRowId
    }

    class RowCodec {
        <<concrete>>
        +encodedLength(long rowId, Object[] values, ColumnType[] types) int
        +encode(long rowId, Object[] values, ColumnType[] types) byte[]
        +decode(byte[] payload, ColumnType[] types) Tuple
    }

    class HeapPage {
        <<concrete>>
        -byte[] data
        -int pageSize
        +createEmpty(int pageId, int pageSize) HeapPage
        +wrap(byte[] data) HeapPage
        +insert(long rowId, Object[] values, ColumnType[] types) int
        +read(int slotId, ColumnType[] types) Optional~Tuple~
        +update / delete / scanLive / freeSpace / toBytes
    }

    class PageLayoutException {
        <<concrete>>
    }

    class IndexStore {
        <<interface>>
        +createIndex(...)
        +dropIndex(...)
        +insert(...) / delete(...) / lookupEquals(...) / lookupRange(...)
    }

    class IndexRange {
        <<record>>
    }

    class IndexSortedBuilder {
        <<concrete>>
    }

    class IndexPageWal {
        <<concrete>>
    }

    class IndexScanSpec {
        <<concrete>>
    }

    class FileIndexStore {
        <<concrete>>
    }

    class IndexScanOperator {
        <<concrete>>
        +lookupRange via IndexScanSpec
    }

    class LockManager {
        <<interface>>
        +runExclusiveCatalog(Runnable action)
        +runExclusiveCatalog(Supplier~T~ action) T
        +lockExclusiveCatalog()
        +unlockExclusiveCatalog()
        +bindOwner(long ownerId)
        +clearOwnerBinding()
        +lockEngine(LockMode mode)
        +unlockEngine(LockMode mode)
        +runWithEngineX(...)
        +runWithTable(String db, String table, LockMode mode, ...)
        +runWithDatabase(String db, LockMode mode, ...)
        +lockTable / unlockTable / lockRow / unlockRow
        +unlockAllForOwner()
        +unlockSharedForOwner()
    }

    class LockMode {
        <<enumeration>>
        IS
        IX
        S
        X
    }

    class LockLevel {
        <<enumeration>>
        ENGINE
        CATALOG
        DATABASE
        TABLE
        ROW
    }

    class LockKey {
        <<record>>
        +engine() LockKey
        +catalog() LockKey
        +database(String) LockKey
        +table(String, String) LockKey
        +row(String, String, long) LockKey
    }

    class LockException {
        <<concrete>>
    }

    class TransactionAbortedException {
        <<concrete>>
    }

    class CatalogLockException {
        <<concrete>>
    }

    class DefaultLockManager {
        <<concrete>>
        -ReentrantLock catalogLock
        -ReentrantLock stateMutex
        -Map~LockKey, LockState~ states
        -Duration lockWait
        +runExclusiveCatalog / lockEngine / runWithEngineX
        +runWithTable / runWithDatabase
        +lockTable / lockRow / unlockAllForOwner
    }

    class TransactionManager {
        <<interface>>
        +runInTransaction(Runnable action)
        +runInTransaction(Supplier~T~ action) T
        +seedNextTxnId(int nextTxnId)
        +beginExplicit(LockManager, CatalogManager)
        +commitExplicit(LockManager, CatalogManager)
        +rollbackExplicit(LockManager, CatalogManager)
        +endConnectionSession(LockManager, CatalogManager)
        +inExplicitTransaction() boolean
        +activeExplicitSessionCount() int
        +currentTxnId() int
    }

    class DefaultTransactionManager {
        <<concrete>>
        -WALManager walManager
        -UndoManager undoManager
        -AtomicInteger nextTxnId
        -AtomicInteger activeExplicitSessions
        -ThreadLocal~TransactionContext~ context
        +runInTransaction(Runnable action)
        +runInTransaction(Supplier~T~ action) T
        +runInTransaction(LockManager, TableStore, Supplier~T~ action) T
        +beginExplicit / commitExplicit / rollbackExplicit
            // READ COMMITTED + Strict 2PL; DML undo via UndoManager
            // rollbackExplicit always unlocks + clears session even if undo throws
            // also drops .idx/.ibd created after BEGIN
    }

    class UndoManager {
        <<interface>>
        +recordInsert / recordUpdate / recordDelete
        +rollback(txnId, TableStore)
        +clear(txnId)
            // Index* undo API optional; IndexMaintainer relies on heap undo + re-maintain
    }

    class IsolationLevel {
        <<enumeration>>
        READ_COMMITTED
        REPEATABLE_READ
    }

    class TransactionControlExecutor {
        <<concrete>>
        +execute(ExecutionPlan plan) QueryResult
    }

    class CheckpointExecutor {
        <<concrete>>
        +execute(ExecutionPlan plan) QueryResult
    }

    class CheckpointPlan {
        <<concrete>>
        +queryType() QueryType
    }

    class WALManager {
        <<interface>>
        +append(WalRecord record)
        +flush()
        +discardPending()
        +replay(CatalogManager catalogManager) int
        +checkpoint() int
    }

    class DefaultWALManager {
        <<concrete>>
        -PhysicalStorage physicalStorage
        +append(WalRecord record)
        +flush()
        +discardPending()
        +replay(CatalogManager catalogManager) int
        +checkpoint() int
    }

    class WalCheckpointMeta {
        <<concrete>>
        -int maxTxnId
    }

    class CheckpointStrategy {
        <<interface>>
        +awaitTrigger()
    }

    class CheckpointStrategyKind {
        <<enumeration>>
        TIMEOUT
        WAL_SIZE
    }

    class TimeoutCheckpointStrategy {
        <<concrete>>
        -Duration timeout
        +awaitTrigger()
    }

    class WalSizeCheckpointStrategy {
        <<concrete>>
        -PhysicalStorage physicalStorage
        -long maxWalSizeBytes
        +awaitTrigger()
    }

    class CheckpointScheduler {
        <<concrete>>
        -CheckpointStrategy strategy
        -LockManager lockManager
        -WALManager walManager
        +start()
        +stop()
    }

    class WalRecord {
        <<concrete>>
        -WalOp op
        -Integer txnId
        +commit(int txnId) WalRecord
        +createTable(int txnId, String, String, List) WalRecord
    }

    class WalOp {
        <<enumeration>>
        CREATE_DATABASE
        DROP_DATABASE
        CREATE_TABLE
        DROP_TABLE
        ADD_COLUMN
        DROP_COLUMN
        CREATE_INDEX
        DROP_INDEX
        COMMIT
        CHECKPOINT
    }

    class BufferPool {
        <<interface>>
        +pin(PageId pageId) BufferFrame
        +newPage(String file) BufferFrame
        +unpin(BufferFrame frame)
        +latchShared / latchExclusive / unlatch
        +markDirty(BufferFrame frame)
        +flush(PageId pageId) / flushAll()
    }

    class PageId {
        <<record>>
        +String file
        +int pageId
    }

    class BufferFrame {
        <<concrete>>
        -byte[] data
        -int pinCount
        -boolean dirty
        +data() / pageId() / pinCount() / dirty()
    }

    class DefaultBufferPool {
        <<concrete>>
        -PhysicalStorage storage
        -BufferFrame[] frames
        -clockHand: int
        +DEFAULT_FRAME_COUNT = 64
    }

    class BufferPoolException {
        <<concrete>>
    }

    class LaunchConfig {
        <<concrete>>
        -int port
        -Path dataDir
        +parse(String[] args) LaunchConfig
    }

    class ServerEnvironment {
        <<concrete>>
        +load(DataDirectory dataDirectory) ServerEnvironment
        +defaults() ServerEnvironment
        +catalogLockWait() Duration
        +checkpointEnabled() boolean
        +checkpointStrategyKind() CheckpointStrategyKind
        +pageSize() int
        +createCheckpointStrategy(PhysicalStorage) CheckpointStrategy
    }

    class PageFileValidator {
        <<concrete>>
        +validateAll(DataDirectory dataDirectory, PhysicalStorage storage) void
    }

    class QueryLexer {
        <<interface>>
        +tokenize(String query) List~Token~
    }

    class DefaultQueryLexer {
        <<concrete>>
        +tokenize(String query) List~Token~
    }

    class LexException {
        <<concrete>>
        -int index
        +index() int
        +toResponse() String
    }

    class TokenCatalog {
        <<enumeration>>
        SELECT
        UPDATE
        INSERT
        DELETE
        PRIMARY
        KEY
        INT
        VARCHAR
        BOOLEAN_TYPE
        IDENTIFIER
        STRING
        BOOLEAN
        NUMBER
        DOT
        EOF
    }

    class Token {
        <<concrete>>
        -TokenCatalog kind
        -String lexeme
        -int index
    }

    class QueryParser {
        <<interface>>
        +parse(List~Token~ tokens) AstNode
    }

    class DefaultQueryParser {
        <<concrete>>
        -ParserRegistry registry
        +parse(List~Token~ tokens) AstNode
    }

    class ParserRegistry {
        <<concrete>>
        +register(TokenCatalog, Parser)
        +getParser(TokenCatalog) Parser
    }

    class Parser {
        <<interface>>
        +parse(TokenStream) AstNode
    }

    class SelectParser {
        <<concrete>>
    }

    class UpdateParser {
        <<concrete>>
    }

    class InsertParser {
        <<concrete>>
    }

    class DeleteParser {
        <<concrete>>
    }

    class CreateParser {
        <<concrete>>
    }

    class AlterParser {
        <<concrete>>
    }

    class DropParser {
        <<concrete>>
    }

    class TokenStream {
        <<concrete>>
        +peek() Token
        +consume() Token
        +expect(TokenCatalog) Token
    }

    class ParseException {
        <<concrete>>
        -int index
        +toResponse() String
    }

    class AstNode {
        <<interface>>
    }

    class Query {
        <<interface>>
    }

    class SelectQuery {
        <<concrete>>
    }

    class UpdateQuery {
        <<concrete>>
    }

    class InsertQuery {
        <<concrete>>
    }

    class DeleteQuery {
        <<concrete>>
    }

    class ColumnSqlType {
        <<enumeration>>
        INT
        VARCHAR
        BOOLEAN
    }

    class ColumnDefinition {
        <<concrete>>
        -String name
        -ColumnSqlType type
        -boolean primaryKey
    }

    class QualifiedTable {
        <<concrete>>
        -String database
        -String table
        +qualifiedName() String
    }

    class CreateTableQuery {
        <<concrete>>
        -QualifiedTable table
        -List~ColumnDefinition~ columns
        -Optional~String~ primaryKeyColumn
    }

    class Expression {
        <<interface>>
    }

    class ColumnExpression {
        <<concrete>>
    }

    class LiteralExpression {
        <<concrete>>
    }

    class BinaryExpression {
        <<concrete>>
    }

    class ServerSocket {
        <<interface>>
        -int port
        +accept() ClientConnection
        +close()
    }

    class ClientConnection {
        <<interface>>
        -ConnectionId id
        +receive() Request
        +send(Response response)
        +close()
    }

    class Request {
        <<interface>>
        +decode()
    }

    class Response {
        <<interface>>
        +encode()
    }

    class ConnectionId {
        <<concrete>>
        -String value
    }

    class TcpNetworkModule {
        <<concrete>>
        -ServerSocket serverSocket
        -RequestHandler requestHandler
        +start()
        +stop()
    }

    class TcpServerSocket {
        <<concrete>>
        -java.net.ServerSocket socket
        -int port
        +accept() ClientConnection
        +close()
    }

    class TcpClientConnection {
        <<concrete>>
        -java.net.Socket socket
        -ConnectionId id
        +receive() Request
        +send(Response response)
        +close()
    }

    class TcpRequest {
        <<concrete>>
        +decode()
    }

    class TcpResponse {
        <<concrete>>
        +encode()
    }

    class JsonWireResponse {
        <<concrete>>
        -WireResponse wireResponse
        +encode()
    }

    class WireProtocol {
        <<concrete>>
        +VERSION int
    }

    class WireMessage {
        <<sealed>>
        Error
        Ok
        ResultSet
        Done
    }

    class WireResponse {
        <<concrete>>
        -int version
        -List messages
    }

    class WireResponseEncoder {
        <<concrete>>
        +fromProcessorText(String) WireResponse
    }

    class WireResponseJson {
        <<concrete>>
        +toBytes(WireResponse) byte[]
        +toJson(WireResponse) String
    }

    DatabaseServer --> StorageEngine : owns
    DatabaseServer --> NetworkModule : owns
    DatabaseServer --> QueryProcessor : uses

    NetworkModule <|.. TcpNetworkModule
    RequestHandler <|.. DefaultRequestHandler
    QueryProcessor <|.. DefaultQueryProcessor
    StorageEngine <|.. DefaultStorageEngine
    DefaultStorageEngine --> DataDirectory : owns
    StorageEngine --> PhysicalStorage : owns
    PhysicalStorage <|.. DefaultPhysicalStorage
    DefaultPhysicalStorage --> DataDirectory : uses
    PhysicalStorage ..> PhysicalStorageException : throws
    StorageEngine --> CatalogManager : owns
    CatalogManager <|.. DefaultCatalogManager
    DefaultCatalogManager --> TableMetadata : stores
    TableMetadata --> ColumnMetadata : columns
    TableMetadata --> IndexMetadata : indexes
    ColumnMetadata --> ColumnType : type
    CatalogManager ..> CatalogException : throws
    CatalogManager --> CatalogStore : owns
    CatalogStore <|.. JsonCatalogStore
    JsonCatalogStore --> PhysicalStorage : uses
    QueryLexer <|.. DefaultQueryLexer
    QueryLexer ..> Token : produces
    QueryLexer ..> LexException : throws
    QueryParser <|.. DefaultQueryParser
    Parser <|.. SelectParser
    Parser <|.. UpdateParser
    Parser <|.. InsertParser
    Parser <|.. DeleteParser
    Parser <|.. CreateParser
    Parser <|.. AlterParser
    Parser <|.. DropParser

    TcpNetworkModule --> RequestHandler : owns
    DefaultRequestHandler --> QueryProcessor : queryProcessor
    DefaultQueryProcessor --> QueryLexer : owns
    DefaultQueryProcessor --> QueryParser : owns
    DefaultQueryProcessor --> QueryPlanner : owns
    DefaultQueryProcessor --> QueryDispatcher : owns
    DefaultQueryProcessor --> StorageEngine : uses
    DefaultQueryAnalyser --> CatalogManager : reads
    QueryAnalyser <|.. DefaultQueryAnalyser
    AnalyzedQuery <|.. AnalyzedCreateTable
    AnalyzedQuery <|.. AnalyzedCreateDatabase
    AnalyzedQuery <|.. AnalyzedDropTable
    AnalyzedQuery <|.. AnalyzedDropDatabase
    AnalyzedQuery <|.. AnalyzedAddColumn
    AnalyzedQuery <|.. AnalyzedDropColumn
    AnalyzedQuery <|.. AnalyzedCreateIndex
    AnalyzedQuery <|.. AnalyzedDropIndex
    AnalyzedQuery <|.. AnalyzedDescribeTable
    AnalyzedQuery <|.. AnalyzedShowDatabases
    AnalyzedQuery <|.. AnalyzedShowTables
    AnalyzedQuery <|.. AnalyzedSelect
    AnalyzedQuery <|.. AnalyzedInsert
    AnalyzedQuery <|.. AnalyzedUpdate
    AnalyzedQuery <|.. AnalyzedDelete
    AnalyzedQuery <|.. UnresolvedQuery
    AnalyzedSelect --> ResolvedProjection : projections
    AnalyzedInsert --> ResolvedInsertValue : values
    AnalyzedUpdate --> ResolvedAssignment : assignments
    AnalyzedAddColumn --> ColumnMetadata : column
    QueryPlanner <|.. DefaultQueryPlanner
    ExecutionPlan <|.. CreateTablePlan
    ExecutionPlan <|.. CreateDatabasePlan
    ExecutionPlan <|.. DropTablePlan
    ExecutionPlan <|.. DropDatabasePlan
    ExecutionPlan <|.. AddColumnPlan
    ExecutionPlan <|.. DropColumnPlan
    ExecutionPlan <|.. CreateIndexPlan
    ExecutionPlan <|.. DropIndexPlan
    ExecutionPlan <|.. DescribeTablePlan
    ExecutionPlan <|.. ShowDatabasesPlan
    ExecutionPlan <|.. ShowTablesPlan
    ExecutionPlan <|.. SelectPlan
    ExecutionPlan <|.. InsertPlan
    ExecutionPlan <|.. UpdatePlan
    ExecutionPlan <|.. DeletePlan
    ExecutionPlan <|.. UnresolvedPlan
    CreateTablePlan --> ColumnMetadata : columns
    CreateTablePlan --> QueryType
    CreateDatabasePlan --> QueryType
    DropTablePlan --> QueryType
    DropDatabasePlan --> QueryType
    AddColumnPlan --> QueryType
    DropColumnPlan --> QueryType
    CreateIndexPlan --> QueryType
    DropIndexPlan --> QueryType
    AddColumnPlan --> ColumnMetadata : column
    SelectPlan --> AccessPath
    UpdatePlan --> AccessPath
    DeletePlan --> AccessPath
    SelectPlan --> QueryType
    InsertPlan --> QueryType
    UpdatePlan --> QueryType
    DeletePlan --> QueryType
    UnresolvedPlan --> UnresolvedQuery : source
    DefaultQueryPlanner ..> AnalyzedCreateTable
    DefaultQueryPlanner ..> AnalyzedCreateDatabase
    DefaultQueryPlanner ..> AnalyzedDropTable
    DefaultQueryPlanner ..> AnalyzedDropDatabase
    DefaultQueryPlanner ..> AnalyzedAddColumn
    DefaultQueryPlanner ..> AnalyzedDropColumn
    DefaultQueryPlanner ..> AnalyzedCreateIndex
    DefaultQueryPlanner ..> AnalyzedDropIndex
    DefaultQueryPlanner ..> AnalyzedSelect
    DefaultQueryPlanner ..> AnalyzedInsert
    DefaultQueryPlanner ..> AnalyzedUpdate
    DefaultQueryPlanner ..> AnalyzedDelete
    QueryDispatcher --> ExecutorRegistry
    ExecutorRegistry --> QueryExecutor
    QueryExecutor <|.. CommandExecutor
    QueryExecutor <|.. TransactionControlExecutor
    QueryExecutor <|.. CheckpointExecutor
    QueryExecutor <|.. DescribeExecutor
    QueryExecutor <|.. VolcanoExecutor
    QueryExecutor <|.. VectorizedExecutor
    QueryExecutor <|.. BatchExecutor
    CommandExecutor --> CatalogManager : writes
    CommandExecutor --> TableStore : dropTable / dropDatabase
    DescribeExecutor --> CatalogManager : reads
    CommandExecutor --> TransactionManager : implicit txn / explicit append
    CommandExecutor --> LockManager : ENGINE IX + table X / database X / catalog X
    CommandExecutor --> WALManager : append (flush at commit)
    VolcanoExecutor --> TableStore : DML/DQL
    VolcanoExecutor --> LockManager : ENGINE IS/IX + table IS/IX + row S/X
    VolcanoExecutor --> TransactionManager : implicit txn per statement
    VolcanoExecutor ..> VolcanoOperator : compiles plan
    VolcanoOperator ..> Tuple
    ExpressionEvaluator ..> Tuple
    TableStore <|.. FileTableStore
    TableStore <|.. InMemoryTableStore
    FileTableStore --> BufferPool
    FileTableStore --> CatalogManager
    FileTableStore --> RidMap
    InMemoryTableStore --> Tuple
    RidMap <|.. InMemoryRidMap
    InMemoryRidMap --> Rid
    HeapPage ..> RowCodec : encode/decode
    HeapPage ..> PageLayout : constants
    HeapPage ..> PageType : HEAP
    HeapMetaPage ..> PageType : HEAP_META
    HeapPage ..> Tuple : read/scan
    RowCodec ..> ColumnType
    RowCodec ..> Tuple
    HeapPage ..> PageLayoutException : throws
    TransactionControlExecutor --> TransactionManager : begin/commit/rollback
    CheckpointExecutor --> LockManager : runWithEngineX + catalog
    CheckpointExecutor --> BufferPool : flushAll
    CheckpointExecutor --> WALManager : flush + checkpoint
    CheckpointScheduler --> BufferPool : flushAll
    CheckpointExecutor --> WALManager : checkpoint
    CheckpointExecutor --> TransactionManager : reject if explicit
    TransactionManager <|.. DefaultTransactionManager
    DefaultTransactionManager --> WALManager : flush COMMIT / discard
    LockManager <|.. DefaultLockManager
    WALManager <|.. DefaultWALManager
    DefaultWALManager --> PhysicalStorage : wal.log
    DefaultWALManager --> PhysicalStorage : wal.checkpoint
    DefaultWALManager --> PhysicalStorage : replay/replay-*.log
    DefaultWALManager ..> CatalogManager : replay
    CheckpointStrategy <|.. TimeoutCheckpointStrategy
    CheckpointStrategy <|.. WalSizeCheckpointStrategy
    CheckpointScheduler --> CheckpointStrategy
    CheckpointScheduler --> LockManager
    CheckpointScheduler --> WALManager : checkpoint
    ServerEnvironment ..> CheckpointStrategy : createCheckpointStrategy
    DefaultStorageEngine ..> PageFileValidator : start validates .ibd/.idx
    PageFileValidator ..> PhysicalStorage : read pages
    PageFileValidator ..> PageLayout : header constants
    PageFileValidator ..> PageType : type byte
    PageFileValidator ..> HeapMetaPage : .ibd page 0 stamp
    PageFileValidator ..> IndexMetaPage : .idx page 0 stamp
    FileTableStore ..> HeapMetaPage : ensure page 0
    FileIndexStore ..> IndexMetaPage : pageSize stamp
    WalRecord --> WalOp
    DefaultStorageEngine --> TransactionManager : owns
    DefaultStorageEngine --> LockManager : owns
    DefaultStorageEngine --> WALManager : owns
    DefaultStorageEngine --> CheckpointScheduler : owns
    ExecutionPlan <|.. CheckpointPlan
    CheckpointExecutor ..> CheckpointPlan
    VolcanoExecutor ..> SelectPlan
    VolcanoExecutor ..> InsertPlan
    VolcanoExecutor ..> UpdatePlan
    VolcanoExecutor ..> DeletePlan
    CommandExecutor ..> CreateTablePlan
    CommandExecutor ..> CreateDatabasePlan
    CommandExecutor ..> DropTablePlan
    CommandExecutor ..> DropDatabasePlan
    CommandExecutor ..> AddColumnPlan
    CommandExecutor ..> DropColumnPlan
    CommandExecutor ..> CreateIndexPlan
    CommandExecutor ..> DropIndexPlan
    StorageEngine --> TableStore : owns
    DefaultStorageEngine --> FileTableStore : constructs
    StorageEngine --> IndexStore : owns
    StorageEngine --> BufferPool : owns
    DefaultStorageEngine --> DefaultBufferPool : constructs
    BufferPool <|.. DefaultBufferPool
    DefaultBufferPool --> BufferFrame
    DefaultBufferPool --> PageId
    DefaultBufferPool --> PhysicalStorage : page I/O
    DefaultBufferPool ..> HeapPage : newPage empty image
    DefaultQueryParser --> ParserRegistry
    ParserRegistry --> Parser
    DefaultQueryParser ..> TokenStream
    QueryParser ..> AstNode : produces
    QueryParser ..> ParseException : throws
    AstNode <|-- Query
    Query <|.. SelectQuery
    Query <|.. UpdateQuery
    Query <|.. InsertQuery
    Query <|.. DeleteQuery
    Query <|.. CreateTableQuery
    CreateTableQuery --> QualifiedTable : table
    CreateTableQuery --> ColumnDefinition : columns
    ColumnDefinition --> ColumnSqlType : type
    AstNode <|-- Expression
    Expression <|.. ColumnExpression
    Expression <|.. LiteralExpression
    Expression <|.. BinaryExpression
    Token --> TokenCatalog : kind

    ServerSocket <|.. TcpServerSocket
    ClientConnection <|.. TcpClientConnection
    Request <|.. TcpRequest
    Response <|.. TcpResponse
    Response <|.. JsonWireResponse

    DefaultRequestHandler --> WireResponseEncoder : fromProcessorText
    WireResponseEncoder --> WireResponse
    JsonWireResponse --> WireResponse : wireResponse
    JsonWireResponse --> WireResponseJson : encode

    TcpNetworkModule --> ServerSocket : serverSocket
    ServerSocket --> ClientConnection : accept()
    ClientConnection --> ConnectionId : id
    ClientConnection --> Request : receive()
    ClientConnection --> Response : send()
```
