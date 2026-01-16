package com.company.migration.execution

import com.company.migration.config.{MongoConfig, SparkJobConfig, TableConfig, YugabyteConfig}
import com.company.migration.mongo.MongoReader
import com.company.migration.transform.SchemaMapper
import com.company.migration.util.{Logging, Metrics}
import com.company.migration.yugabyte.YugabyteConnectionFactory
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.TaskContext
import scala.collection.JavaConverters._

/**
 * Orchestrates the migration of a single table
 * Enhanced with robust checkpointing (run_info + run_details)
 */
class TableMigrationJob(
  spark: SparkSession,
  mongoConfig: MongoConfig,
  yugabyteConfig: YugabyteConfig,
  sparkJobConfig: SparkJobConfig,
  tableConfig: TableConfig,
  connectionFactory: YugabyteConnectionFactory,
  metrics: Metrics,
  checkpointManager: Option[CheckpointManager] = None,
  runId: Long,
  prevRunId: Long = 0L,
  checkpointInterval: Int = 10000
) extends Logging {
  
  /**
   * Execute the migration job
   */
  def execute(): Unit = {
    val tableName = s"${tableConfig.sourceDatabase}.${tableConfig.sourceCollection}"
    logInfo(s"Starting migration for collection: $tableName -> ${tableConfig.targetSchema}.${tableConfig.targetTable}")
    
    try {
      // Step 1: Read from MongoDB
      val reader = new MongoReader(spark, mongoConfig)
      val df = reader.readCollection(tableConfig)
      
      // Step 2: Get target columns
      val targetColumns = SchemaMapper.getTargetColumns(df.schema, tableConfig)
      logInfo(s"Target columns (${targetColumns.size}): ${targetColumns.mkString(", ")}")
      
      // Step 3: Validate source columns
      SchemaMapper.validateSourceColumns(df, tableConfig)
      
      // Step 4: Log partition info
      logInfo(s"MongoDB partitions: ${df.rdd.getNumPartitions}")
      
      // Step 5: Determine partitions to process (new run vs. resume)
      // Extract checkpoint manager and prevRunId to local vals to avoid capturing 'this'
      val localCheckpointManagerForInit = checkpointManager
      val localPrevRunIdForInit = prevRunId
      val localTableNameForInit = tableName
      
      val partitionsToProcess = if (localCheckpointManagerForInit.isDefined && localPrevRunIdForInit > 0) {
        // Resume mode: Get pending partitions from previous run
        logInfo(s"Resume mode: Getting pending partitions from previous run $localPrevRunIdForInit")
        val pending = localCheckpointManagerForInit.get.getPendingPartitions(localTableNameForInit, localPrevRunIdForInit, "MIGRATE")
        logInfo(s"Found ${pending.size} pending partitions to retry")
        pending
      } else {
        // New run: Collect all partitions from DataFrame
        // Note: We can't get token ranges from DataFrame directly, so we'll track by partition_id
        // This is a limitation - in production, you'd want to extract token ranges from Spark partitions
        val numPartitions = df.rdd.getNumPartitions
        logInfo(s"New run: Processing all $numPartitions partitions")
        (0 until numPartitions).map { partitionId =>
          PartitionCheckpoint(
            tokenMin = 0L, // Will be updated during execution
            tokenMax = 0L, // Will be updated during execution
            partitionId = partitionId
          )
        }.toList
      }
      
      // Initialize checkpoint run if enabled
      localCheckpointManagerForInit.foreach { cm =>
        cm.initRun(
          tableName = localTableNameForInit,
          runId = runId,
          prevRunId = localPrevRunIdForInit,
          partitions = partitionsToProcess,
          runType = "MIGRATE"
        )
      }
      
      // Step 6: Execute COPY for each partition
      // Extract values needed for partition execution (avoid capturing entire TableMigrationJob)
      // Pattern: Only pass serializable configs, recreate non-serializable objects per partition
      val localTableConfig = tableConfig
      val localYugabyteConfig = yugabyteConfig
      val localTargetColumns = targetColumns
      val localSourceSchema = df.schema
      val localMetrics = metrics
      val localCheckpointEnabled = checkpointManager.isDefined
      val localCheckpointKeyspace = checkpointManager.map(_.keyspaceName).getOrElse("public")
      val localRunId = runId
      val localTableName = tableName
      val localCheckpointInterval = checkpointInterval
      val localPrevRunId = prevRunId
      
      // Create set of pending partition IDs for resume mode (extract to local val for serialization)
      val localPendingPartitionIds = if (localCheckpointEnabled && localPrevRunId > 0) {
        val pending = partitionsToProcess.map(_.partitionId).toSet
        logInfo(s"Resume mode: Will only process ${pending.size} pending partitions")
        pending
      } else {
        Set.empty[Int]
      }
      
      // Extract df to local val to avoid capturing 'this'
      val localDf = df
      
      // Use a static logger to avoid capturing 'this'
      val logger = org.slf4j.LoggerFactory.getLogger(classOf[TableMigrationJob])
      
      localDf.foreachPartition { (partition: Iterator[Row]) =>
        // Get partition ID from Spark context
        val partitionId = TaskContext.getPartitionId()
        
        // In resume mode, skip partitions that are already completed
        if (localPendingPartitionIds.nonEmpty && !localPendingPartitionIds.contains(partitionId)) {
          logger.info(s"Skipping partition $partitionId (already completed in previous run)")
          // Skip this partition - don't process it
        } else {
        
        // Create new connection factory per partition (not serializable)
        val localConnectionFactory = new YugabyteConnectionFactory(localYugabyteConfig)
        // Recreate checkpoint manager per partition if enabled (best practice for Spark)
        val localCheckpointManager = if (localCheckpointEnabled) {
          Some(new CheckpointManager(localYugabyteConfig, localCheckpointKeyspace))
        } else {
          None
        }
        
          val partitionExecutor = new PartitionExecutor(
            localTableConfig,
            localYugabyteConfig,
            localConnectionFactory,
            localTargetColumns,
            localSourceSchema,
            localMetrics,
            localCheckpointManager,
            localRunId,
            localTableName,
            partitionId,
            localCheckpointInterval
          )
          val _ = partitionExecutor.execute(partition)
        }
      }
      
      logInfo(s"Migration completed for table: ${tableConfig.targetSchema}.${tableConfig.targetTable}")
      
    } catch {
      case e: Exception =>
        logError(s"Migration failed for collection ${tableConfig.sourceDatabase}.${tableConfig.sourceCollection}: ${e.getMessage}", e)
        throw e
    }
  }
}
