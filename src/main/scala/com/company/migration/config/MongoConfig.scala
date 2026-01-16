package com.company.migration.config

import java.util.Properties

/**
 * MongoDB source configuration
 */
case class MongoConfig(
  uri: String,
  database: String,
  collection: String,
  readPreference: String,
  batchSize: Int,
  partitionField: String,
  partitionStrategy: String,
  pipeline: String
)

object MongoConfig {
  def fromProperties(props: Properties): MongoConfig = {
    def getProperty(key: String, default: String = ""): String = {
      props.getProperty(key, default)
    }

    def getIntProperty(key: String, default: Int): Int = {
      val value = props.getProperty(key)
      if (value != null && value.nonEmpty) value.toInt else default
    }

    MongoConfig(
      uri = getProperty("mongo.uri", "mongodb://localhost:27017"),
      database = getProperty("mongo.database"),
      collection = getProperty("mongo.collection"),
      readPreference = getProperty("mongo.readPreference", "primaryPreferred"),
      batchSize = getIntProperty("mongo.batchSize", 1000),
      partitionField = getProperty("mongo.partition.field", "_id"),
      partitionStrategy = getProperty("mongo.partition.strategy", "sample"),
      pipeline = getProperty("mongo.pipeline", "[]")
    )
  }
}
