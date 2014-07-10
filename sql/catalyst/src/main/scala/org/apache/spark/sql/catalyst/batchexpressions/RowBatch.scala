package org.apache.spark.sql.catalyst.batchexpressions

import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference, Row}
import org.apache.spark.sql.catalyst.types._

import scala.collection.mutable.Map

class RowBatch(val rowNum: Int) {

  // the columns come from table or as result tuple
  val name2Vector = Map.empty[String, ColumnVector]

  // selector for the current rowbatch
  var curSelector: BitSet = null

  val memPool = new OffHeapMemoryPool(rowNum)

  def getMemory(dt: DataType): Memory = {
    dt match {
      case LongType | DoubleType => getTmpMemory(8)
      case IntegerType | FloatType => getTmpMemory(4)
      case ShortType => getTmpMemory(2)
      case ByteType => getTmpMemory(1)
      case StringType => new StringMemory(new Array[String](rowNum), rowNum)
      case BooleanType => new BooleanMemory(new BitSet(rowNum), rowNum)
      case BinaryType => NullMemory
    }
  }

  def getTmpMemory(width: Int): OffHeapMemory =
    memPool.borrowMemory(width)

  def returnMemory(width: Int, mem: OffHeapMemory) =
    memPool.returnMemory(width, mem)

  def free() = {
    name2Vector.values.foreach(_.content.free())
    memPool.free()
  }

  def expand: Array[Row] = ???

}

object RowBatch {

  def buildFromAttributes(attrs: Seq[Attribute], rowNum: Int): RowBatch = {
    val rowBatch = new RowBatch(rowNum)
    attrs.foreach { attr =>
      val ar = attr.asInstanceOf[AttributeReference]
      val mem = rowBatch.getMemory(ar.dataType)
      val cv = ColumnVector.getNewCV(ar.dataType, mem, false)
      rowBatch.name2Vector(ar.name) = cv
    }
    rowBatch
  }

}
