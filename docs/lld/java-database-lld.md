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
        +execute(String query) String
    }

    class DefaultQueryProcessor {
        <<concrete>>
        -QueryLexer lexer
        -QueryParser parser
        -QueryPlanner planner
        -QueryDispatcher queryDispatcher
        -StorageEngine storageEngine
        +execute(String query) String
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
        -String message
        +ok() QueryResult
        +toResponse() String
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
        +execute(ExecutionPlan plan) QueryResult
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
    }

    class DefaultStorageEngine {
        <<concrete>>
        -DataDirectory dataDirectory
        -PhysicalStorage physicalStorage
        -DefaultCatalogManager catalogManager
        +start()
        +stop()
        +dataDirectory() DataDirectory
        +catalogManager() CatalogManager
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
        +create(String file)
        +delete(String file)
        +exists(String file) boolean
        +read(String file) byte[]
        +write(String file, byte[] bytes)
        +read(String file, long offset, int length) byte[]
        +write(String file, long offset, byte[] bytes)
        +flush(String file)
        +createDirectory(String path)
        +deleteDirectory(String path)
        +listDirectories(String path) List~String~
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
    }

    class IndexStore {
        <<interface>>
    }

    class LockManager {
        <<interface>>
    }

    class TransactionManager {
        <<interface>>
    }

    class WALManager {
        <<interface>>
    }

    class BufferPool {
        <<interface>>
    }

    class LaunchConfig {
        <<concrete>>
        -int port
        -Path dataDir
        +parse(String[] args) LaunchConfig
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
    AnalyzedQuery <|.. UnresolvedQuery
    AnalyzedCreateTable --> ColumnMetadata : columns
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
    UnresolvedPlan --> UnresolvedQuery : source
    DefaultQueryPlanner ..> AnalyzedCreateTable
    DefaultQueryPlanner ..> AnalyzedCreateDatabase
    DefaultQueryPlanner ..> AnalyzedDropTable
    DefaultQueryPlanner ..> AnalyzedDropDatabase
    DefaultQueryPlanner ..> AnalyzedAddColumn
    DefaultQueryPlanner ..> AnalyzedDropColumn
    DefaultQueryPlanner ..> AnalyzedCreateIndex
    DefaultQueryPlanner ..> AnalyzedDropIndex
    QueryDispatcher --> ExecutorRegistry
    ExecutorRegistry --> QueryExecutor
    QueryExecutor <|.. CommandExecutor
    CommandExecutor --> CatalogManager : writes
    CommandExecutor ..> CreateTablePlan
    CommandExecutor ..> CreateDatabasePlan
    CommandExecutor ..> DropTablePlan
    CommandExecutor ..> DropDatabasePlan
    CommandExecutor ..> AddColumnPlan
    CommandExecutor ..> DropColumnPlan
    CommandExecutor ..> CreateIndexPlan
    CommandExecutor ..> DropIndexPlan
    StorageEngine --> TableStore : owns
    StorageEngine --> IndexStore : owns
    StorageEngine --> LockManager : owns
    StorageEngine --> TransactionManager : owns
    StorageEngine --> WALManager : owns
    StorageEngine --> BufferPool : owns
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

    TcpNetworkModule --> ServerSocket : serverSocket
    ServerSocket --> ClientConnection : accept()
    ClientConnection --> ConnectionId : id
    ClientConnection --> Request : receive()
    ClientConnection --> Response : send()
```
