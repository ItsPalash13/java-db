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
        -ExecutorService executorService
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
        -String table
        -List~ColumnMetadata~ columns
    }

    class AnalyzedCreateDatabase {
        <<concrete>>
        -String database
    }

    class AnalyzedDropDatabase {
        <<concrete>>
        -String database
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
        CREATE_DATABASE
        DROP_DATABASE
        UNRESOLVED
    }

    class ExecutionPlan {
        <<interface>>
        +queryType() QueryType
    }

    class CreateTablePlan {
        <<concrete>>
        -String table
        -List~ColumnMetadata~ columns
    }

    class CreateDatabasePlan {
        <<concrete>>
        -String database
    }

    class DropDatabasePlan {
        <<concrete>>
        -String database
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

    class ExecutorService {
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
        +getTable(String name) Optional~TableMetadata~
        +tableExists(String name) boolean
        +createTable(TableMetadata table) TableMetadata
        +allTables() List~TableMetadata~
        +databaseExists(String name) boolean
        +allDatabases() List~String~
        +createDatabase(String name)
        +dropDatabase(String name)
        +load()
    }

    class DefaultCatalogManager {
        <<concrete>>
        -Map~String, TableMetadata~ tablesByName
        -Set~String~ databaseNames
        -CatalogStore catalogStore
        -int nextTableId
        +getTable(String name) Optional~TableMetadata~
        +tableExists(String name) boolean
        +createTable(TableMetadata table) TableMetadata
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
        -String name
        -List~ColumnMetadata~ columns
        +define(String name, List~ColumnMetadata~ columns) TableMetadata
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
        +saveAll(List~TableMetadata~ tables)
        +saveTable(TableMetadata table)
        +loadDatabases() List~String~
        +createDatabase(String name)
        +dropDatabase(String name)
    }

    class JsonCatalogStore {
        <<concrete>>
        +load() List~TableMetadata~
        +saveAll(List~TableMetadata~ tables)
        +saveTable(TableMetadata table)
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

    class CreateTableQuery {
        <<concrete>>
        -String table
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
    DefaultQueryProcessor --> ExecutorService : owns
    DefaultQueryProcessor --> StorageEngine : uses
    DefaultQueryAnalyser --> CatalogManager : reads
    QueryAnalyser <|.. DefaultQueryAnalyser
    AnalyzedQuery <|.. AnalyzedCreateTable
    AnalyzedQuery <|.. AnalyzedCreateDatabase
    AnalyzedQuery <|.. AnalyzedDropDatabase
    AnalyzedQuery <|.. UnresolvedQuery
    AnalyzedCreateTable --> ColumnMetadata : columns
    QueryPlanner <|.. DefaultQueryPlanner
    ExecutionPlan <|.. CreateTablePlan
    ExecutionPlan <|.. CreateDatabasePlan
    ExecutionPlan <|.. DropDatabasePlan
    ExecutionPlan <|.. UnresolvedPlan
    CreateTablePlan --> ColumnMetadata : columns
    CreateTablePlan --> QueryType
    CreateDatabasePlan --> QueryType
    DropDatabasePlan --> QueryType
    UnresolvedPlan --> UnresolvedQuery : source
    DefaultQueryPlanner ..> AnalyzedCreateTable
    DefaultQueryPlanner ..> AnalyzedCreateDatabase
    DefaultQueryPlanner ..> AnalyzedDropDatabase
    ExecutorService --> ExecutorRegistry
    ExecutorRegistry --> QueryExecutor
    QueryExecutor <|.. CommandExecutor
    CommandExecutor --> CatalogManager : writes
    CommandExecutor ..> CreateTablePlan
    CommandExecutor ..> CreateDatabasePlan
    CommandExecutor ..> DropDatabasePlan
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
