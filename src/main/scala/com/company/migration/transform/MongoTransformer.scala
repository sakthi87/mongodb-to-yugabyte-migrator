package com.company.migration.transform

import com.company.migration.config.TableConfig
import org.apache.spark.sql.{DataFrame, functions => F}

/**
 * Builds a target-ready DataFrame from MongoDB source data.
 */
object MongoTransformer {

  def toTargetFrame(df: DataFrame, tableConfig: TableConfig): DataFrame = {
    if (tableConfig.mappingMode == "JSONB") {
      val idExpr = F.coalesce(F.col("_id").cast("string"), F.to_json(F.col("_id")))
      val docExpr = F.to_json(F.struct(df.columns.map(F.col): _*))

      df.select(
        idExpr.alias(tableConfig.idColumn),
        docExpr.alias(tableConfig.docColumn)
      )
    } else {
      // FLAT mode: map fields to target columns and cast types when configured.
      val sourceColumns = df.columns.toList
      val mappedColumns = if (tableConfig.columnMapping.isEmpty) {
        sourceColumns.map { colName =>
          F.col(colName).alias(colName)
        }
      } else {
        sourceColumns.map { sourceCol =>
          val targetCol = tableConfig.columnMapping.getOrElse(sourceCol, sourceCol)
          F.col(sourceCol).alias(targetCol)
        }
      }

      val selected = df.select(mappedColumns: _*)

      // Apply optional type mapping (targetCol -> Spark SQL type)
      tableConfig.typeMapping.foldLeft(selected) { case (acc, (sourceTypeOrCol, targetType)) =>
        // Treat key as target column name for MongoDB mappings
        if (acc.columns.contains(sourceTypeOrCol)) {
          acc.withColumn(sourceTypeOrCol, F.col(sourceTypeOrCol).cast(targetType))
        } else {
          acc
        }
      }
    }
  }
}
