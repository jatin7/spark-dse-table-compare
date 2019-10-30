import org.apache.spark.sql.{DataFrame, SparkSession}
import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.functions._
import scala.collection.JavaConverters._
import org.apache.spark.sql.types.{ArrayType, DecimalType, StructType}

object TableCompare {
  def main(args: Array[String]): Unit = {
    val spark               = SparkSession.builder.appName("Table Compare").getOrCreate()
    val config              = ConfigFactory.load()
    val master_table        = config.getString("master_table.table")
    val master_keyspace     = config.getString("master_table.keyspace")
    val compare_table       = config.getString("compare_table.table")
    val compare_keyspace    = config.getString("compare_table.keyspace")
    val primary_join        = config.getString("join_column.primary")
    val clustering_join1    = config.getString("join_column.clustering1")
    val clustering_join2    = config.getString("join_column.clustering2")
    val clustering_join3    = config.getString("join_column.clustering3")
    val clustering_join4    = config.getString("join_column.clustering4")
    val columns_to_drop     = config.getStringList("exclude_columns.column_list").asScala
    val joinConditions      = config.getStringList("join_list").asScala

    def updateColumnsToString(df:DataFrame,columnIt:Iterator[String]):DataFrame = {
      def dfHelper(dFrame:DataFrame,col:String):DataFrame = {
        if (columnIt.isEmpty) dFrame
        else dfHelper(dFrame.withColumn(col,concat_ws(", ",dFrame(col))),columnIt.next())
      }
      dfHelper(df,columnIt.next())
    }

    def matchColumnTypes(df:DataFrame,col:String): DataFrame = {
      df.schema(col).dataType match {
        case ArrayType(StructType(_),_) => df.withColumn(col,hash(df(col)))
        case DecimalType() => df.withColumn(col,df(col).cast(DecimalType(10,2)))
        case _ => df
      }
    }

    def updateTypes(df:DataFrame,columnIt:Iterator[String]):DataFrame = {
      def dfHelper(dFrame:DataFrame,col:String):DataFrame = {
        if (columnIt.isEmpty) dFrame
        else dfHelper(matchColumnTypes(dFrame,col),columnIt.next())
      }
      dfHelper(df,columnIt.next())
    }

    val columns = spark.read.format("org.apache.spark.sql.cassandra").options(Map("table" -> config.getString("system_table.table"), "keyspace" -> config.getString("system_table.keyspace"))).load()
    val columnsDropped = columns.filter(!col("column_name").isin(columns_to_drop:_*))
    columnsDropped.createOrReplaceTempView("columns")
    val df = spark.sql(s"""
    SELECT concat('t1.', column_name, ' AS t1_', column_name, ', t2.', column_name, ' AS t2_', column_name, ',') AS select_clause_fields
    FROM columns
    WHERE keyspace_name = '$master_keyspace'
    AND table_name = '$master_table'
    """) //select clause sql
    val select_clause = df.select("select_clause_fields").rdd.collect.mkString.replace("[", "").replace("]"," ")
    val select_clause_trim = select_clause.substring(0,select_clause.length-2)

    val table1 = spark.read.format("org.apache.spark.sql.cassandra").options(Map("table" -> master_table, "keyspace" -> master_keyspace)).load()
    val table2 = spark.read.format("org.apache.spark.sql.cassandra").options(Map("table" -> compare_table, "keyspace" -> compare_keyspace)).load()
    val table1Drop = table1.drop(columns_to_drop:_*)
    val table2Drop = table2.drop(columns_to_drop:_*)
    val table1Hash = table1Drop.withColumn("hash",hash(table1Drop.columns.map(col):_*))
    val table2Hash = table2Drop.withColumn("hash",hash(table2Drop.columns.map(col):_*))

    table1Hash.createOrReplaceTempView("table1Hashed")
    table2Hash.createOrReplaceTempView("table2Hashed")

    val clustering1Select = if (clustering_join1 != "null" && clustering_join1 != "") s""", $clustering_join1""" else ""
    val clustering2Select = if (clustering_join2 != "null" && clustering_join2 != "") s""", $clustering_join2""" else ""
    val clustering3Select = if (clustering_join3 != "null" && clustering_join3 != "") s""", $clustering_join3""" else ""
    val clustering4Select = if (clustering_join4 != "null" && clustering_join4 != "") s""", $clustering_join4""" else ""
    val dfWithHashSelect = s"""$primary_join$clustering1Select$clustering2Select$clustering3Select$clustering4Select"""

    val table1KeyAndHash = spark.sql(s"""SELECT $dfWithHashSelect, hash FROM table1Hashed""")
    val table2KeyAndHash = spark.sql(s"""SELECT $dfWithHashSelect, hash FROM table2Hashed""")

    table1KeyAndHash.createOrReplaceTempView("table1KeyAndHash")
    table2KeyAndHash.createOrReplaceTempView("table2KeyAndHash")

    val Table1ExceptTable2 = spark.sql("SELECT * FROM table1KeyAndHash EXCEPT SELECT * FROM table2KeyAndHash")
    println("Table 1 Except Table 2"+"\r"+Table1ExceptTable2.show(100,false)) //TODO remove
    val Table2ExceptTable1 = spark.sql("SELECT * FROM table2KeyAndHash EXCEPT SELECT * FROM table1KeyAndHash")
    println("Table 2 Except Table 1"+"\r"+Table2ExceptTable1.show(100,false)) //TODO remove

    val ExceptedJoinDf = Table1ExceptTable2.join(Table2ExceptTable1,joinConditions,"fullouter")
    println("Full Outer Join of both EXCEPT tables"+"\r"+ExceptedJoinDf.show(100,false)) //TODO remove

    val table1Rejoined = Table1ExceptTable2.join(table1Drop,joinConditions,"inner")
    println("Table 1 hashed re-joined with original, inner"+"\r"+table1Rejoined.show(100,false)) //TODO remove
    val table2Rejoined = Table2ExceptTable1.join(table2Drop,joinConditions,"inner")
    println("Table 2 hashed re-joined with original, inner"+"\r"+table2Rejoined.show(100,false)) //TODO remove
    table1Rejoined.createOrReplaceTempView("t1")
    table2Rejoined.createOrReplaceTempView("t2")

    val clustering1Join = if (clustering_join1 != "null" && clustering_join1 != "") s""" AND t1.$clustering_join1 = t2.$clustering_join1""" else ""
    val clustering2Join = if (clustering_join2 != "null" && clustering_join2 != "") s""" AND t1.$clustering_join2 = t2.$clustering_join2""" else ""
    val clustering3Join = if (clustering_join3 != "null" && clustering_join3 != "") s""" AND t1.$clustering_join3 = t2.$clustering_join3""" else ""
    val clustering4Join = if (clustering_join4 != "null" && clustering_join4 != "") s""" AND t1.$clustering_join4 = t2.$clustering_join4""" else ""

    val results = spark.sql(s"""SELECT $select_clause_trim FROM t1 FULL OUTER JOIN t2 ON t1.$primary_join = t2.$primary_join$clustering1Join$clustering2Join$clustering3Join$clustering4Join""")
    println("Final Results"+"\r"+results.show(100,false))//TODO remove
    val resultsString = updateColumnsToString(updateTypes(results,results.columns.toIterator),results.columns.toIterator)
    resultsString.coalesce(1).write.option("header","true").option("delimiter", "\t").option("quote", "\u0000").csv(config.getString("csv_path.output_path"))
  }
}
