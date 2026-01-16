package com.company.migration.transform

import com.company.migration.config.TableConfig
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.types.StructType

/**
 * Maps source schema to YugabyteDB schema
 * Handles column name mapping and type compatibility
 */
object SchemaMapper {
  
  /**
   * Get target column names in order
   * Applies column mapping if specified
   * Includes constant columns at the end (for audit fields, etc.)
   */
  def getTargetColumns(sourceSchema: StructType, tableConfig: TableConfig): List[String] = {
    val sourceColumns = if (tableConfig.mappingMode == "JSONB") {
      List(tableConfig.idColumn, tableConfig.docColumn)
    } else if (tableConfig.columnMapping.isEmpty) {
      // No mapping - use source column names
      sourceSchema.fieldNames.toList
    } else {
      // Apply column mapping
      sourceSchema.fieldNames.map { sourceCol =>
        tableConfig.columnMapping.getOrElse(sourceCol, sourceCol)
      }.toList
    }
    
    // Append constant columns (columns with default values not in source)
    val constantColumnNames = tableConfig.constantColumns.keys.toList
    sourceColumns ++ constantColumnNames
  }
  
  /**
   * Get source column name for a target column
   */
  def getSourceColumnName(targetColumn: String, tableConfig: TableConfig): String = {
    if (tableConfig.mappingMode == "JSONB" || tableConfig.mappingMode == "FLAT") {
      return targetColumn
    }
    // Reverse lookup in column mapping
    tableConfig.columnMapping.find(_._2 == targetColumn)
      .map(_._1)
      .getOrElse(targetColumn)
  }
  
  /**
   * Validate that all source columns exist in the DataFrame
   */
  def validateSourceColumns(df: DataFrame, tableConfig: TableConfig): Unit = {
    val sourceColumns = df.schema.fieldNames.toSet

    if (tableConfig.mappingMode == "JSONB") {
      val required = Set(tableConfig.idColumn, tableConfig.docColumn)
      val missing = required.diff(sourceColumns)
      if (missing.nonEmpty) {
        throw new IllegalArgumentException(
          s"Required JSONB columns missing from DataFrame: ${missing.mkString(", ")}. " +
            s"Available columns: ${sourceColumns.mkString(", ")}"
        )
      }
    } else {
      // FLAT mode: verify mapped target columns exist (MongoTransformer already applies mapping)
      if (tableConfig.columnMapping.nonEmpty) {
        val expectedTargets = tableConfig.columnMapping.values.toSet
        val missing = expectedTargets.diff(sourceColumns)
        if (missing.nonEmpty) {
          throw new IllegalArgumentException(
            s"Target columns missing from DataFrame: ${missing.mkString(", ")}. " +
              s"Available columns: ${sourceColumns.mkString(", ")}"
          )
        }
      }
    }
  }
}

