package es.udc.graph

import Array._
import scala.util.Random
import scala.util.control.Breaks._
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.mllib.linalg.SparseVector
import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.regression.LabeledPoint

class Hash(var values: Array[Integer]) extends Serializable {
  override val hashCode = values.deep.hashCode

  override def equals(obj: Any) = obj.isInstanceOf[Hash] && obj.asInstanceOf[Hash].values.deep == this.values.deep

  def concat(other: Hash): Hash = {
    return new Hash(this.values ++ other.values)
  }

  def cutLen(len: Int): Hash = {
    return new Hash(this.values.slice(0, len))
  }
}

abstract class Hasher(kLength: Int, nTables: Int) extends Serializable {
  protected def _init(): Unit

  this._init()
  val numTables = nTables
  val keyLength = kLength

  def getHashes(point: Vector, index: Long, radius: Double): List[(Hash, Long)]

  def update(kLength: Int, nTables: Int): Hasher
}

object Hasher {
  val DEFAULT_NUM_TABLES = 5
  val DEFAULT_KEY_LENGTH = 3
}

class EuclideanLSHasher(dimension: Int, kLength: Int, nTables: Int) extends Hasher(kLength, nTables) {
  private val OptimalW = 4
  val w = OptimalW

  val gaussianVectors = ofDim[Double](numTables, keyLength, dimension)
  val b = ofDim[Double](numTables, keyLength)

  override def update(kLength: Int, nTables: Int): Hasher = {
    new EuclideanLSHasher(dimension, kLength, nTables)
  }

  override protected def _init(): Unit = {
    val randomGenerator = new Random()
    for (i <- 0 until numTables)
      for (j <- 0 until keyLength) {
        for (k <- 0 until dimension)
          gaussianVectors(i)(j)(k) = randomGenerator.nextGaussian()
        b(i)(j) = randomGenerator.nextDouble * w
      }
  }

  this._init()

  override def getHashes(point: Vector, index: Long, radius: Double): List[(Hash, Long)] = {
    var hashes = List[(Hash, Long)]()
    for (i <- 0 until numTables) {
      val hash = new Array[Integer](keyLength)
      for (j <- 0 until keyLength) {
        var dotProd: Double = 0
        //TODO Take dot product to a function or use a prebuilt one
        if (point.isInstanceOf[DenseVector]) {
          for (k <- 0 until dimension)
            dotProd += point(k) * gaussianVectors(i)(j)(k)
        }
        else //SparseVector
        {
          val sparse = point.asInstanceOf[SparseVector]
          val indices = sparse.indices
          val values = sparse.values

          for (k <- 0 until indices.length) {
            //if (indices(k)>=dimension)
            //  break
            if (indices(k) < dimension)
              dotProd += values(k) * gaussianVectors(i)(j)(indices(k))
          }
        }
        dotProd /= radius
        hash(j) = math.floor((dotProd + b(i)(j)) / w).toInt
      }
      hashes = (new Hash(hash), index) :: hashes
    }
    return hashes
  }
}