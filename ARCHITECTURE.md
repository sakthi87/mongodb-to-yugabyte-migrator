MongoDB -> YugabyteDB YSQL Migrator Architecture
================================================

Goals
-----
- Move data "as-is" from MongoDB to YugabyteDB YSQL without transformations.
- Reuse existing YugabyteDB write path from the Cassandra migrator.
- Provide high throughput via Spark + COPY FROM STDIN.
- Support checkpointing, retry, and validation.

Non-Goals (initial phase)
-------------------------
- Complex field transformations or denormalization.
- Change data model or schema semantics.
- Full CDC (change data capture) / continuous sync.

Key Decision: "As-Is" Data Mapping
----------------------------------
YSQL is relational, MongoDB is document-oriented. To preserve documents without
transformation, the recommended baseline is:

1) JSONB mode (default)
   - Store the full MongoDB document in a `doc JSONB` column.
   - Store `_id` in a dedicated primary key column `id TEXT` (or UUID if
     compatible).
   - Preserve everything else verbatim in `doc`.

2) Flat mode (field-to-field)
   - Map top-level fields to columns with a fixed schema.
   - Field mapping uses `table.columnMapping.<source>=<target>`.
   - Optional casts use `table.typeMapping.<target>=<spark_sql_type>`.
   - Nested objects/arrays can be stored as JSONB per field, or left in `doc`.

JSONB mode provides true "as-is" semantics without schema coupling.

High-Level Data Flow
--------------------
MongoDB (Spark Mongo Connector)
  -> DataFrame
  -> RowTransformer (JSONB serialization + optional _id handling)
  -> Yugabyte COPY FROM STDIN (or INSERT mode)
  -> YSQL table

Components
----------
1) Mongo Reader (new)
   - Uses Spark MongoDB Connector to read collections.
   - Supports filters, projections, sampling, and partitioning.
   - Outputs DataFrame with `_id` and full document (JSON representation).

2) Yugabyte Writer (reused)
   - `YugabyteConnectionFactory` for JDBC connections.
   - `CopyWriter` (COPY FROM STDIN) for fast loads.
   - `InsertBatchWriter` for idempotent loading when needed.
   - `UpsertStatementBuilder` for INSERT mode.

3) Job Orchestration (reused + adapted)
   - `TableMigrationJob` style orchestration.
   - Partitioned processing with retries.
   - Metrics, checkpoints, and validation.

4) Config (new + reused)
   - `MongoConfig` (source settings)
   - `YugabyteConfig` (target settings, reused)
   - `SparkJobConfig` (reused)
   - `TableConfig` (reused/extended for JSONB mode)

Proposed Config Keys (migration.properties)
-------------------------------------------
[MongoDB]
mongo.uri=mongodb://user:pass@host:27017
mongo.database=transaction_datastore
mongo.collection=transactions
mongo.readPreference=primaryPreferred
mongo.batchSize=1000
mongo.partition.field=_id
mongo.partition.strategy=sample
mongo.pipeline=[]  # optional aggregation pipeline in JSON

[Yugabyte]
yugabyte.host=localhost
yugabyte.port=5433
yugabyte.database=transaction_datastore
yugabyte.username=yugabyte
yugabyte.password=yugabyte
yugabyte.insertMode=COPY
yugabyte.copyReplace=true
yugabyte.truncateTargetTable=false

[Mapping]
mapping.mode=JSONB
mapping.idColumn=id
mapping.docColumn=doc
mapping.idType=TEXT  # TEXT or UUID

Target Schema (JSONB Mode)
--------------------------
CREATE TABLE public.<table_name> (
  id TEXT PRIMARY KEY,
  doc JSONB NOT NULL
);

If `_id` is ObjectId, it is stored as its 24-char hex string.

Partitioning & Parallelism
--------------------------
Partitioning depends on MongoDB connector capabilities:
- Sample-based split strategy (default).
- Range partitioning on `_id` or time field if present.

Spark parallelism (executor/cores/partitions) is configured as in the Cassandra
migrator.

Validation & Checkpointing
--------------------------
Reuse existing validation mechanics:
- Row counts from metrics.
- Optional checksum validation on `_id`/document hash.

Checkpointing stores run state in YugabyteDB (same tables and schema).

Failure Handling
----------------
- Retries on partition failures.
- Resume from checkpoints.
- Optional `INSERT` mode for idempotency if duplicates are possible.

Security & Connectivity
-----------------------
- Support TLS and auth via MongoDB URI params.
- Use Yugabyte JDBC params as in existing migrator.

Implementation Plan (High Level)
--------------------------------
1) Scaffold project with Spark + MongoDB Connector dependency.
2) Implement `MongoConfig` and `MongoReader`.
3) Implement `MongoRowTransformer` (JSONB mode).
4) Reuse `Yugabyte*` writer modules from Cassandra migrator.
5) Wire `MainApp` + `TableMigrationJob` equivalents.
6) Add migration.properties.example.

