MongoDB to YugabyteDB YSQL Migrator
===================================

Purpose
-------
Migrate data from MongoDB to YugabyteDB YSQL with no transformations. The goal
is a faithful "as-is" copy, with any downstream transformations handled by a
separate layer.

This project will reuse the YugabyteDB write path (COPY / INSERT, connection
factory, batching, checkpointing, validation) from the existing Cassandra
migrator and replace only the source reader with MongoDB.

Quick Start
-----------
1) Edit `migration.properties.example` and save as `migration.properties`
2) Create the target table in YugabyteDB YSQL
3) Build the project
4) Run with spark-submit

Example (local):
```
mvn package -DskipTests
spark-submit \
  --class com.company.migration.MainApp \
  --master 'local[4]' \
  target/mongodb-to-yugabyte-migrator-1.0.0-SNAPSHOT.jar \
  src/main/resources/migration.properties
```

Data Mapping Modes
------------------
1) JSONB mode (no field mapping required)
   - Stores full document in `doc JSONB`.
   - Stores `_id` in `id TEXT`.
   - Use when you want a faithful "as-is" copy.

2) FLAT mode (field-to-field mapping)
   - Maps Mongo fields to YSQL columns.
   - Use when the target table has multiple columns.

Configuration Overview
----------------------
MongoDB source:
```
mongo.uri=mongodb://localhost:27017
mongo.database=transaction_datastore
mongo.collection=transactions_flat
```

Target table:
```
table.target.schema=public
table.target.table=transactions_flat
```

Mapping (FLAT mode):
```
mapping.mode=FLAT
table.columnMapping._id=id
table.columnMapping.customerId=customer_id
table.columnMapping.orderTotal=order_total
table.typeMapping.order_total=DECIMAL(18,2)
```

Mapping (JSONB mode):
```
mapping.mode=JSONB
mapping.idColumn=id
mapping.docColumn=doc
```

MongoDB Partitioning and Pipeline
---------------------------------
These options are passed to the MongoDB Spark connector:

```
mongo.partition.field=_id
mongo.partition.strategy=sample
mongo.pipeline=[]
```

- `mongo.partition.field`: Field used to split the collection for parallel reads.
  `_id` works well for most collections.
- `mongo.partition.strategy`: Partitioning strategy.
  - `sample`: Uses sampling to create partitions (recommended).
  - `single`: Disables partitioning (single partition, simplest).
- `mongo.pipeline`: Optional Mongo aggregation pipeline in JSON array form.
  Example to filter and project:
  ```
  mongo.pipeline=[{"$match":{"status":"PAID"}},{"$project":{"_id":1,"customerId":1,"orderTotal":1}}]
  ```

Repository Layout
-----------------
- `src/main/scala/com/company/migration/`
  - `mongo/`        MongoDB reader & partitioning
  - `yugabyte/`     Reused write path (COPY/INSERT)
  - `config/`       Mongo + Yugabyte + Spark configs
  - `execution/`    Job orchestration, retry, checkpointing
  - `transform/`    Mapping logic (JSONB or flat fields)

