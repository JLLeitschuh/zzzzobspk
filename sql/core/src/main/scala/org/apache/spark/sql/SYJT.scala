package org.apache.spark.sql

import org.apache.spark.sql.test.TestSQLContext._
import org.apache.spark.sql.test._

case class SYJT(key: String, a: Int, b: Int, c: Int)

object SYJT {
  val t1: SchemaRDD = TestSQLContext.sparkContext.parallelize(
    (1 to 100).map(i => SYJT(s"val_$i", i+ 100, i + 200, i + 300)))
  t1.registerAsTable("t1")

  def main(args: Array[String]) {
    val srdd = sql("SELECT key, a, b+c FROM t1")
    srdd.collect()
    println(
      s"""
         |== Logical Plan ==
         |${srdd.logicalPlan}
         |== Analyzed Plan ==
         |${srdd.queryExecution.analyzed}
         |== Physical Plan ==
         |${srdd.queryExecution.executedPlan}
       """.stripMargin)
  }

}
