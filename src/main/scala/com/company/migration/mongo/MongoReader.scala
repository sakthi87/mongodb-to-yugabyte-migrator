package com.company.migration.mongo

import com.company.migration.config.{MongoConfig, TableConfig}
import com.company.migration.transform.MongoTransformer
import com.company.migration.util.Logging
import org.apache.spark.sql.{DataFrame, SparkSession}

/**
 * Reads data from MongoDB using the Spark MongoDB Connector.
 */
class MongoReader(spark: SparkSession, mongoConfig: MongoConfig) extends Logging {

  def readCollection(tableConfig: TableConfig): DataFrame = {
    logInfo(s"Reading MongoDB collection: ${mongoConfig.database}.${mongoConfig.collection}")

    val reader = spark.read
      .format("mongodb")
      .option("connection.uri", mongoConfig.uri)
      .option("database", mongoConfig.database)
      .option("collection", mongoConfig.collection)
      .option("readPreference.name", mongoConfig.readPreference)
      .option("batchSize", mongoConfig.batchSize)
      .option("pipeline", mongoConfig.pipeline)

    val partitionerClass = mongoConfig.partitionStrategy.toLowerCase match {
      case "sample" => "com.mongodb.spark.sql.connector.read.partitioner.SamplePartitioner"
      case "single" => "com.mongodb.spark.sql.connector.read.partitioner.SinglePartitioner"
      case other =>
        logWarn(s"Unknown partition strategy '$other', defaulting to sample partitioner")
        "com.mongodb.spark.sql.connector.read.partitioner.SamplePartitioner"
    }

    val df = reader
      .option("partitioner", partitionerClass)
      .option("partitioner.options.partitionKey", mongoConfig.partitionField)
      .load()

    MongoTransformer.toTargetFrame(df, tableConfig)
  }
}
