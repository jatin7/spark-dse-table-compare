import org.apache.spark.sql.{DataFrame, SparkSession}
import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.functions._

object TableCompare {
  def main(args: Array[String]): Unit = {

    val spark             = SparkSession.builder.appName("Table Compare").getOrCreate()
//    val spark               = SparkSession.builder.master("local").appName("Table Compare").getOrCreate()
    val config              = ConfigFactory.load()
    val master_table        = config.getString("master_table.table")
    val master_keyspace     = config.getString("master_table.keyspace")
    val compare_table       = config.getString("compare_table.table")
    val compare_keyspace    = config.getString("compare_table.keyspace")
    val t1_join             = config.getString("join_column.t1_name")
    val t2_join             = config.getString("join_column.t2_name")
    val masterClustering1   = config.getString("master_table.clustering1")
    val compareClustering1  = config.getString("compare_table.clustering1")
    val masterClustering2   = config.getString("master_table.clustering2")
    val compareClustering2  = config.getString("compare_table.clustering2")

    val columns = spark.read.format("org.apache.spark.sql.cassandra").options(Map("table" -> config.getString("system_table.table"), "keyspace" -> config.getString("system_table.keyspace"))).load()
    columns.createOrReplaceTempView("columns")

    val df = spark.sql(s"""
    SELECT concat('t1.', column_name, ' AS t1_', column_name, ', t2.', column_name, ' AS t2_', column_name, ',') AS select_clause_fields
    FROM columns
    WHERE keyspace_name = '$master_keyspace'
    AND table_name = '$master_table'
    """)

    val select_clause = df.select("select_clause_fields").rdd.collect.mkString.replace("[", "").replace("]"," ")
    val select_clause_trim = select_clause.substring(0,select_clause.length-2)

    val table1 = spark.read.format("org.apache.spark.sql.cassandra").options(Map("table" -> master_table, "keyspace" -> master_keyspace)).load()
    val table2 = spark.read.format("org.apache.spark.sql.cassandra").options(Map("table" -> compare_table, "keyspace" -> compare_keyspace)).load()

    table1.createOrReplaceTempView("table1")
    table2.createOrReplaceTempView("table2")

    val t1 = spark.sql("SELECT * FROM table1 EXCEPT SELECT * FROM table2")
    val t2 = spark.sql("SELECT * FROM table2 EXCEPT SELECT * FROM table1")

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")

    val clustering1Join = if (config.getString("master_table.clustering1") != "null" && config.getString("compare_table.clustering1") != "null") s""" AND t1.$masterClustering1 = t2.$compareClustering1""" else " "
    val clustering2Join = if (config.getString("master_table.clustering2") != "null" && config.getString("compare_table.clustering2") != "null") s""" AND t1.$masterClustering2 = t2.$compareClustering2""" else " "

    val results = spark.sql(s"""SELECT $select_clause_trim FROM t1 FULL OUTER JOIN t2 ON t1.$t1_join = t2.$t2_join $clustering1Join $clustering2Join""")

    def updateColumnTypes(df:DataFrame,columnIt:Iterator[String]):DataFrame = {
      def dfHelper(dFrame:DataFrame,col:String):DataFrame = {
        if (columnIt.isEmpty) dFrame
        else dfHelper(dFrame.withColumn(col,concat_ws(", ",dFrame(col))),columnIt.next())
      }
      dfHelper(df,columnIt.next())
    }

    val resultsString = updateColumnTypes(results,results.columns.toIterator)
    resultsString.coalesce(1).write.option("header","true").option("delimiter", "\t").option("quote", "\u0000").csv(config.getString("csv_path.output_path"))
  }
}