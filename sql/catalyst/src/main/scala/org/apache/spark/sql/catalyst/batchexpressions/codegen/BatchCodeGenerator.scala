/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.batchexpressions.codegen

import com.google.common.cache.{CacheLoader, CacheBuilder}

import org.apache.spark.Logging
import org.apache.spark.sql.catalyst.batchexpressions._
import org.apache.spark.sql.catalyst.expressions
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.types._

abstract class BatchCodeGenerator[InType <: AnyRef, OutType <: AnyRef] extends Logging {
  import scala.reflect.runtime.universe._
  import scala.reflect.runtime.{universe => ru}
  import scala.tools.reflect.ToolBox

  protected val toolBox = runtimeMirror(getClass.getClassLoader)
      .mkToolBox(/*options = "-Ydump-classes /Users/yijie/abc/"*/)

  protected val rowBatchType = typeOf[RowBatch]
  protected val rbProjectionType = typeOf[RBProjection]

  protected val doubleLiteralType = typeOf[DoubleLiteral]
  protected val longLiteralType = typeOf[LongLiteral]
  protected val intLiteralType = typeOf[IntLiteral]
  protected val stringLiteralType = typeOf[StringLiteral]
  protected val booleanLiteralType = typeOf[BooleanLiteral]


  protected val columnVectorObj = reify(ColumnVector)

  private val curId = new java.util.concurrent.atomic.AtomicInteger()
  private val javaSeparator = "$"

  /**
   * Generates a class for a given input expression.  Called when there is not cached code
   * already available.
   */
  protected def create(in: InType): OutType

  /**
   * Canonicalizes an input expression. Used to avoid double caching expressions that differ only
   * cosmetically.
   */
  protected def canonicalize(in: InType): InType

  /** Binds an input expression to a given input schema,
    *  i.e. transform expressions with AttributeReference into exprs with BoundReference*/
  protected def bind(in: InType, inputSchema: Seq[Attribute]): InType

  /**
   * A cache of generated classes.
   *
   * From the Guava Docs: A Cache is similar to ConcurrentMap, but not quite the same. The most
   * fundamental difference is that a ConcurrentMap persists all elements that are added to it until
   * they are explicitly removed. A Cache on the other hand is generally configured to evict entries
   * automatically, in order to constrain its memory footprint
   */
  protected val cache = CacheBuilder.newBuilder()
    .maximumSize(1000)
    .build(
      new CacheLoader[InType, OutType]() {
        override def load(in: InType): OutType = globalLock.synchronized {
          val startTime = System.nanoTime()
          val result = create(in)
          val endTime = System.nanoTime()
          def timeMs = (endTime - startTime).toDouble / 1000000
          logInfo(s"Code generated expression $in in $timeMs ms")
          result
        }
      })

  /** Generates the requested evaluator binding the given expression(s) to the inputSchema. */
  def apply(expressions: InType, inputSchema: Seq[Attribute]): OutType =
    apply(bind(expressions, inputSchema))

  /** Generates the requested evaluator given already bound expression(s). */
  def apply(expressions: InType): OutType = cache.get(canonicalize(expressions))

  /**
   * Returns a term name that is unique within this instance of a `CodeGenerator`.
   *
   * (Since we aren't in a macro context we do not seem to have access to the built in `freshName`
   * function.)
   */
  protected def freshName(prefix: String): TermName = {
    newTermName(s"$prefix$javaSeparator${curId.getAndIncrement}")
  }

  /**
   * Scala ASTs for evaluating an
   *
   * @param code The sequence of statements required to evaluate the expression.
   * @param notNullArrayTerm
   * @param cvTerm
   */
  protected case class EvaluatedExpression(
      code: Seq[Tree],
      notNullArrayTerm: TermName,
      cvTerm: TermName)

  def expressionEvaluator(e: Expression): EvaluatedExpression = {
    val notNullArrayTerm = freshName("notNullArrayTerm") //result cv's notnullarry
    val cvTerm = freshName("cvTerm") //result cv
    val inputRowBatch = newTermName(s"input")

    implicit class Evaluate1(e: Expression) {
      def cast(f: Tree => Tree, resultType: DataType): Seq[Tree] = {
        val eval = expressionEvaluator(e)

        val dt = reify(resultType)
        val cv = eval.cvTerm
        val nna = eval.notNullArrayTerm

        val setter = mutatorForType(resultType)
        val getter = accessorForType(e.dataType)

        val bitmap = freshName("bitmap")
        val selector = freshName("selector")
        val bmIter = freshName("bmIter")
        val i = freshName("i")
        val rowNum = freshName("curRowNum")

        val castCode = f(q"$cv.$getter($i)")

        eval.code ++
        q"""
          val $cvTerm = $columnVectorObj.apply($dt, $inputRowBatch.curRowNum)
          val $notNullArrayTerm = if($nna != null) $nna.copy else null
          val $selector = $inputRowBatch.curSelector
          val $bitmap = ${andWithNull(nna, selector, true)}

          if ($bitmap != null) {
            $bitmap.availableBits = $inputRowBatch.curRowNum
            val $bmIter = $bitmap.iterator
            var $i = 0
            while ($bmIter.hasNext) {
              $i = $bmIter.next()
              $cvTerm.$setter($i, $castCode)
            }
          } else {
            val $rowNum = $inputRowBatch.curRowNum
            var $i = 0
            while ($i < $rowNum) {
              $cvTerm.$setter($i, $castCode)
              $i += 1
            }
          }
          $cvTerm.notNullArray = $notNullArrayTerm
        """.children
      }
    }

    implicit class Evaluate2(expressions: (Expression, Expression)) {
      def evaluate(f: (Tree, Tree) => Tree): Seq[Tree] =
        evaluateAs(expressions._1.dataType)(f)

      def evaluateAs(resultType: DataType)(f: (Tree, Tree) => Tree): Seq[Tree] = {
        val eval1 = expressionEvaluator(expressions._1)
        val eval2 = expressionEvaluator(expressions._2)

        val dt = reify(resultType)
        val lcv = eval1.cvTerm
        val rcv = eval2.cvTerm
        val lnna = eval1.notNullArrayTerm
        val rnna = eval2.notNullArrayTerm

        val setter = mutatorForType(resultType)
        val getter = accessorForType(expressions._1.dataType)

        val bitmap = freshName("bitmap")
        val selector = freshName("selector")
        val bmIter = freshName("bmIter")
        val i = freshName("i")
        val rowNum = freshName("curRowNum")

        val calculationCode = f(q"$lcv.$getter($i)", q"$rcv.$getter($i)")

        eval1.code ++ eval2.code ++
          q"""
          val $cvTerm = $columnVectorObj.apply($dt, $inputRowBatch.curRowNum)
          val $notNullArrayTerm = ${andWithNull(lnna, rnna, true)}
          val $selector = $inputRowBatch.curSelector
          val $bitmap = ${andWithNull(notNullArrayTerm, selector, false)}

          if ($bitmap != null) {
            $bitmap.availableBits = $inputRowBatch.curRowNum
            val $bmIter = $bitmap.iterator
            var $i = 0
            while ($bmIter.hasNext) {
              $i = $bmIter.next()
              $cvTerm.$setter($i, $calculationCode)
            }
          } else {
            val $rowNum = $inputRowBatch.curRowNum
            var $i = 0
            while ($i < $rowNum) {
              $cvTerm.$setter($i, $calculationCode)
              $i += 1
            }
          }
          $cvTerm.notNullArray = $notNullArrayTerm
        """.children
      }
    }

    val primitiveEvaluation: PartialFunction[Expression, Seq[Tree]] = {

      case b @ BoundReference(ordinal, dataType, nullable) =>
        q"""
          val $cvTerm = ${getCV(inputRowBatch, ordinal)}.asInstanceOf[${getCVType(dataType)}]
          val $notNullArrayTerm = $cvTerm.notNullArray
        """.children

      //TODO: Literal handling
      //TODO: Cast handling(Binary2String, timestamp etc)
      //TODO: remainder(%)

      case expressions.Literal(value: Boolean, dataType) =>
        q"""
          val $cvTerm = new $booleanLiteralType($value)
          val $notNullArrayTerm = $cvTerm.notNullArray
         """.children

      case expressions.Literal(value: String, dataType) =>
        q"""
          val $cvTerm = new $stringLiteralType($value)
          val $notNullArrayTerm = $cvTerm.notNullArray
         """.children

      case expressions.Literal(value: Int, dataType) =>
        q"""
          val $cvTerm = new $intLiteralType($value)
          val $notNullArrayTerm = $cvTerm.notNullArray
         """.children

      case expressions.Literal(value: Long, dataType) =>
        q"""
          val $cvTerm = new $longLiteralType($value)
          val $notNullArrayTerm = $cvTerm.notNullArray
         """.children

      case expressions.Literal(value: Double, dataType) =>
        q"""
          val $cvTerm = new $doubleLiteralType($value)
          val $notNullArrayTerm = $cvTerm.notNullArray
         """.children

      case Cast(child @ NumericType(), IntegerType) =>
        child.cast(c => q"$c.toInt", IntegerType)

      case Cast(child @ NumericType(), LongType) =>
        child.cast(c => q"$c.toLong", LongType)

      case Cast(child @ NumericType(), DoubleType) =>
        child.cast(c => q"$c.toDouble", DoubleType)

      case Cast(child @ NumericType(), FloatType) =>
        child.cast(c => q"$c.toFloat", IntegerType)

      case Add(e1, e2) =>      (e1, e2) evaluate { (v1, v2) => q"$v1 + $v2" }
      case Subtract(e1, e2) => (e1, e2) evaluate { (v1, v2) => q"$v1 - $v2" }
      case Multiply(e1, e2) => (e1, e2) evaluate { (v1, v2) => q"$v1 * $v2" }
      case Divide(e1, e2) =>   (e1, e2) evaluate { (v1, v2) => q"$v1 / $v2" }
      case Remainder(e1 @ IntegerType(), e2 @ IntegerType()) =>
        (e1, e2) evaluate { (v1, v2) => q"$v1 % $v2" }

      case EqualTo(e1, e2) =>
        (e1, e2).evaluateAs (BooleanType) {(v1, v2) => q"$v1 == $v2"}
      case GreaterThan(e1 @ NumericType(), e2 @ NumericType()) =>
        (e1, e2).evaluateAs (BooleanType) {(v1, v2) => q"$v1 > $v2"}
      case GreaterThanOrEqual(e1 @ NumericType(), e2 @ NumericType()) =>
        (e1, e2).evaluateAs (BooleanType) {(v1, v2) => q"$v1 >= $v2"}
      case LessThan(e1 @ NumericType(), e2 @ NumericType()) =>
        (e1, e2).evaluateAs (BooleanType) {(v1, v2) => q"$v1 < $v2"}
      case LessThanOrEqual(e1 @ NumericType(), e2 @ NumericType()) =>
        (e1, e2).evaluateAs (BooleanType) {(v1, v2) => q"$v1 <= $v2"}


      case And(e1, e2) =>
        val eval1 = expressionEvaluator(e1)
        val eval2 = expressionEvaluator(e2)

        val lcv = eval1.cvTerm
        val rcv = eval2.cvTerm
        val lnna = eval1.notNullArrayTerm
        val rnna = eval2.notNullArrayTerm

        val selector = freshName("selector")
        val usefulPosArray = freshName("usefulPosArray")
        val lbm = freshName("leftBitmap")
        val rbm = freshName("rightBitmap")
        val tbm = freshName("tmpBitmap")
        val resultBm = freshName("resultBitmap")

        eval1.code ++ eval2.code ++
          q"""
          val $selector = $inputRowBatch.curSelector
          val $notNullArrayTerm = ${andWithNull(lnna, rnna, true)}
          val $usefulPosArray = ${andWithNull(notNullArrayTerm, selector, false)}
          val $lbm = $lcv.asInstanceOf[${typeOf[BooleanColumnVector]}].bs
          val $rbm = $lcv.asInstanceOf[${typeOf[BooleanColumnVector]}].bs
          val $tbm = $lbm & $rbm
          val $resultBm = ${andWithNull(usefulPosArray, tbm, false)}
          val $cvTerm = new ${typeOf[BooleanColumnVector]}($inputRowBatch.curRowNum, $resultBm)
          $cvTerm.notNullArray = $notNullArrayTerm
         """.children

      case Or(e1, e2) =>
        val eval1 = expressionEvaluator(e1)
        val eval2 = expressionEvaluator(e2)

        val lcv = eval1.cvTerm
        val rcv = eval2.cvTerm
        val lnna = eval1.notNullArrayTerm
        val rnna = eval2.notNullArrayTerm

        val selector = freshName("selector")
        val usefulPosArray = freshName("usefulPosArray")
        val lbm = freshName("leftBitmap")
        val rbm = freshName("rightBitmap")
        val tbm = freshName("tmpBitmap")
        val resultBm = freshName("resultBitmap")

        eval1.code ++ eval2.code ++
          q"""
          val $selector = $inputRowBatch.curSelector
          val $notNullArrayTerm = ${andWithNull(lnna, rnna, true)}
          val $usefulPosArray = ${andWithNull(notNullArrayTerm, selector, false)}
          val $lbm = $lcv.asInstanceOf[${typeOf[BooleanColumnVector]}].bs
          val $rbm = $lcv.asInstanceOf[${typeOf[BooleanColumnVector]}].bs
          val $tbm = $lbm | $rbm
          val $resultBm = ${andWithNull(usefulPosArray, tbm, false)}
          val $cvTerm = new ${typeOf[BooleanColumnVector]}($inputRowBatch.curRowNum, $resultBm)
          $cvTerm.notNullArray = $notNullArrayTerm
         """.children

      case Not(e) =>
        val eval = expressionEvaluator(e)

        val cv = eval.cvTerm
        val nna = eval.notNullArrayTerm

        val selector = freshName("selector")
        val bitmap = freshName("bitmap")
        val resultCompl = freshName("resultCompl")
        val resultBm = freshName("resultBitmap")

        eval.code ++
          q"""
           val $selector = $inputRowBatch.curSelector
           val $bitmap = ${andWithNull(selector, nna, false)}

           val $resultCompl = $cv.asInstanceOf[${typeOf[BooleanColumnVector]}].bs.complement
           val $resultBm = ${andWithNull(bitmap, resultCompl, false)}
           val $cvTerm = new ${typeOf[BooleanColumnVector]}($inputRowBatch.curRowNum, $resultBm)
           $cvTerm.notNullArray = $notNullArrayTerm
          """.children
    }

    val code: Seq[Tree] =
      primitiveEvaluation.apply(e)

    EvaluatedExpression(code, notNullArrayTerm, cvTerm)
  }

  protected def getCV(inputRB: TermName, ordinal: Int) = {
    q"$inputRB.vectors($ordinal)"
  }

  protected def setCV(outputRB: TermName, ordinal: Int, value: TermName) = {
    q"$outputRB.vectors($ordinal) = $value"
  }

  protected def accessorForType(dt: DataType) = newTermName(s"get${primitiveForType(dt)}")
  protected def mutatorForType(dt: DataType) = newTermName(s"set${primitiveForType(dt)}")

  protected def primitiveForType(dt: DataType) = dt match {
    case IntegerType => "Int"
    case LongType => "Long"
    case ShortType => "Short"
    case ByteType => "Byte"
    case DoubleType => "Double"
    case FloatType => "Float"
    case BooleanType => "Boolean"
    case StringType => "String"
  }

  protected def getCVType(dt: DataType) = dt match {
    case LongType => typeOf[LongColumnVector]
    case IntegerType => typeOf[IntColumnVector]
    case ShortType => typeOf[ShortColumnVector]
    case ByteType => typeOf[ByteColumnVector]
    case DoubleType => typeOf[DoubleColumnVector]
    case FloatType => typeOf[FloatColumnVector]
    case BooleanType => typeOf[BooleanColumnVector]
    case StringType => typeOf[StringColumnVector]
  }

  protected def andWithNull(bs1: TermName, bs2: TermName, cp: Boolean): Tree = {
    q"""
      if ($bs1 != null && $bs2 != null) {
      $bs1 & $bs2
    } else if ($bs1 != null && $bs2 == null) {
      if($cp) $bs1.copy else $bs1
    } else if ($bs2 != null && $bs1 == null) {
      if($cp) $bs2.copy else $bs2
    } else {
      null
    }
     """
  }
}
