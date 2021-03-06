package es.udc.graph

import org.apache.spark.SparkContext
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD.rddToPairRDDFunctions
import org.apache.hadoop.fs.Path
import java.io._

import Array._
import org.apache.spark.mllib.linalg.Vectors
import org.apache.log4j.{Level, Logger}


import sys.process._


object sparkContextSingleton {
  @transient private var instance: SparkContext = _
  final val conf: SparkConf = new SparkConf().setAppName("KNiNe")
    //.setMaster("local[4]")
    .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    .set("spark.broadcast.factory", "org.apache.spark.broadcast.HttpBroadcastFactory")
    .set("spark.kryoserializer.buffer.max", "512")
    .set("spark.driver.maxResultSize", "2048")

  def getInstance(): SparkContext = {
    if (instance == null)
      instance = SparkContext.getOrCreate(conf) //new SparkContext(conf)
    instance
  }
}

object KNiNeConfiguration {
  def getConfigurationFromOptions(options: Map[String, Any]): KNiNeConfiguration = {
    val radius0 = if (options.exists(_._1 == "radius_start"))
      options("radius_start").asInstanceOf[Double]
    else
      -1.0
    val numTables = if (options.exists(_._1 == "num_tables"))
      options("num_tables").asInstanceOf[Double].toInt
    else
      -1
    val keyLength = if (options.exists(_._1 == "key_length"))
      options("key_length").asInstanceOf[Double].toInt
    else
      -1
    val maxComparisons: Int = if (options.exists(_._1 == "max_comparisons"))
      options("max_comparisons").asInstanceOf[Double].toInt
    else
      -1
    val factor: Double = if (options.exists(_._1 == "factor"))
      options("factor").asInstanceOf[Double].toDouble
    else
      1
    return new KNiNeConfiguration(numTables, keyLength, maxComparisons, radius0, factor)
  }
}

class KNiNeConfiguration(val numTables: Int, val keyLength: Int, val maxComparisons: Int, val radius0: Double, val factor: Double) {
  def this() = this(-1, -1, -1, LSHKNNGraphBuilder.DEFAULT_RADIUS_START, 1)

  override def toString(): String = {
    return "R0=" + this.radius0 + ";NT=" + this.numTables + ";KL=" + this.keyLength + ";MC=" + this.maxComparisons
  }
}

object KNiNe {
  val DEFAULT_METHOD = "lsh"
  val DEFAULT_K = 10

  def showUsageAndExit() = {
    println(
      """Usage: KNiNe dataset output_file [options]
    Dataset must be a libsvm or text file
Options:
    -k    Number of neighbors (default: """ + KNiNe.DEFAULT_K +
        """)
    -m    Method used to compute the graph. Valid values: lsh, brute (default: """ + KNiNe.DEFAULT_METHOD +
        """)
    -r    Starting radius (default: """ + LSHKNNGraphBuilder.DEFAULT_RADIUS_START +
        """)
    -t    Maximum comparisons per item (default: auto)
    -c    File containing the graph to compare to (default: nothing)
    -g    Redirect stdout and stderr to a file (default: disabled)
    -p    Number of cores for a local execution (default: disabled)

Advanced LSH options:
    -f    Accuracy factor, values greater than 1 increase accuracy and values
            between 0 and 1 reduce the number of operations and execution time. (default: 1)
    -n    Number of hashes per item (default: auto)
    -l    Hash length (default: auto)""")
    System.exit(-1)
  }

  def parseParams(p: Array[String]): Map[String, Any] = {
    val m = scala.collection.mutable.Map[String, Any]("num_neighbors" -> KNiNe.DEFAULT_K.toDouble,
      "method" -> KNiNe.DEFAULT_METHOD,
      "radius_start" -> LSHKNNGraphBuilder.DEFAULT_RADIUS_START)
    if (p.length <= 1)
      showUsageAndExit()

    m("dataset") = p(0)
    m("output") = p(1)

    var i = 2
    while (i < p.length) {
      if ((i >= p.length - 1) || (p(i).charAt(0) != '-')) {
        println("Unknown option: " + p(i))
        showUsageAndExit()
      }
      val readOptionName = p(i).substring(1)
      val option = readOptionName match {
        case "k" => "num_neighbors"
        case "m" => "method"
        case "r" => "radius_start"
        case "n" => "num_tables"
        case "l" => "key_length"
        case "t" => "max_comparisons"
        case "c" => "compare"
        case "g" => "log"
        case "p" => "cores"
        case "f" => "factor"
        case somethingElse => readOptionName
      }
      if (!m.keySet.exists(_ == option) && option == readOptionName) {
        println("Unknown option:" + readOptionName)
        showUsageAndExit()
      }
      if (option == "log") {
        m(option) = p(i + 1)
      } else if (option == "method") {
        if (p(i + 1) == "lsh" || p(i + 1) == "brute")
          m(option) = p(i + 1)
        else {
          println("Unknown method:" + p(i + 1))
          showUsageAndExit()
        }
      } else {
        if (option == "compare")
          m(option) = p(i + 1)
        else
          m(option) = p(i + 1).toDouble
      }

      i = i + 2
    }
    return m.toMap
  }

  def main(args: Array[String]) {
    if (args.length <= 0) {
      showUsageAndExit()
      return
    }

    val options = parseParams(args)

    val datasetFile = options("dataset").asInstanceOf[String]

    val fileParts = datasetFile.split("/")
    var justFileName = fileParts(fileParts.length - 1).split("\\.")(0)
    //val file="/home/eirasf/Escritorio/kNNTEMP/car-dopado.libsvm"
    val numNeighbors = options("num_neighbors").asInstanceOf[Double].toInt
    val method = options("method")
    val format = if ((datasetFile.length() > 7) && (datasetFile.substring(datasetFile.length() - 7) == ".libsvm"))
      "libsvm"
    else
      "text"

    val compareFile = if (options.exists(_._1 == "compare"))
      options("compare").asInstanceOf[String]
    else
      null

    val kNiNeConf = KNiNeConfiguration.getConfigurationFromOptions(options)

    //println("Using "+method+" to compute "+numNeighbors+"NN graph for dataset "+justFileName)
    //println("R0:"+radius0+(if (numTables!=null)" num_tables:"+numTables else "")+(if (keyLength!=null)" keyLength:"+keyLength else "")+(if (maxComparisons!=null)" maxComparisons:"+maxComparisons else ""))

    if (options.exists(_._1 == "cores")) {
      sparkContextSingleton.conf.setMaster("local[" + options("cores").asInstanceOf[Double].toInt + "]")
    }

    //Set up Spark Context
    val sc = sparkContextSingleton.getInstance()

    if (options.exists(_._1 == "log")) {
      val log = options("log").asInstanceOf[String]
      val path = new org.apache.hadoop.fs.Path(log + "." + sc.applicationId)
      val logstream = org.apache.hadoop.fs.FileSystem.get(sc.hadoopConfiguration).create(path)
      System.setOut(new java.io.PrintStream(logstream, true))
      System.setErr(new java.io.PrintStream(logstream, true))
    }

    //Stop annoying INFO messages
    val rootLogger = Logger.getRootLogger()
    rootLogger.setLevel(Level.WARN)

    //Load data from file
    val data: RDD[(LabeledPoint, Long)] = if (format == "libsvm")
      MLUtils.loadLibSVMFile(sc, datasetFile).zipWithIndex()
    else {
      val rawData = sc.textFile(datasetFile)
      rawData.map({ line =>
        val values = line.split(";")
        (new LabeledPoint(0.0, Vectors.dense(values.slice(1, values.length).map { x => x.toDouble })), values(0).toLong - 1)
      })
    }

    /* DATASET INSPECTION - DEBUG
    val summary=data.map({case x => (x._1.features.toArray,x._1.features.toArray,x._1.features.toArray)}).reduce({case ((as,aM,am),(bs,bM,bm)) => (as.zip(bs).map({case (ea,eb) => ea+eb}),aM.zip(bM).map({case (ea,eb) => Math.max(ea,eb)}),am.zip(bm).map({case (ea,eb) => Math.min(ea,eb)}))})
    val total=data.count()
    val medias=summary._1.map({ x => x/total })
    val spans=summary._2.zip(summary._3).map({case (a,b) => (a-b)})
    println(Vectors.dense(medias))
    println(Vectors.dense(spans))
    val stddevs=data.map(_._1.features.toArray.zip(medias).map({case (x,u) => (x-u)*(x-u) })).reduce({case (a,b) => a.zip(b).map({case (ea,eb) => ea+eb})}).map({ x => Math.sqrt(x/total) })
    println(Vectors.dense(stddevs))
    println(stddevs.max)
    println(stddevs.min)
    println(stddevs.sum/stddevs.length)
    System.exit(0)
    */

    //val n=data.count()
    //println("Dataset has "+n+" elements")

    /* GRAPH VERSION 
    
    val graph=LSHGraphXKNNGraphBuilder.getGraph(data, numNeighbors, dimension)
    println("There goes the graph:")
    graph.foreach(println(_))
    
    */
    val timeStart = System.currentTimeMillis();
    var builder: LSHLookupKNNGraphBuilder = null
    val (graph, lookup) = if (method == "lsh") {
      /* LOOKUP VERSION */
      builder = new LSHLookupKNNGraphBuilder(data)
      (builder.computeGraph(data, numNeighbors, kNiNeConf.keyLength, kNiNeConf.numTables, kNiNeConf.radius0, kNiNeConf.maxComparisons, new EuclideanDistanceProvider(), kNiNeConf.factor), builder.lookup)
    }
    else
    /* BRUTEFORCE VERSION */
      BruteForceKNNGraphBuilder.parallelComputeGraph(data, numNeighbors)

    //Print graph
    /*println("There goes the graph:")
    graph.foreach({case (elementIndex, neighbors) =>
                    for(n <- neighbors)
                      println(elementIndex+"->"+n._1+"("+n._2+")")
                  })
    */

    val edges = graph.flatMap({ case (index, neighbors) => neighbors.map({ case (destination, distance) => (index, destination, distance) }).toSet })

    //DEBUG
    var counted = edges.map({ case x => (x._1, 1) }).reduceByKey(_ + _).sortBy(_._1)
    var forCount = counted.map(_._2)
    println("Obtained " + forCount.sum() + " edges for " + forCount.count() + " nodes in " + (System.currentTimeMillis() - timeStart) + " milliseconds")

    //Save to file
    var fileName = options("output").asInstanceOf[String]
    var fileNameOriginal = fileName
    var i = 0

    //while (java.nio.file.Files.exists(java.nio.file.Paths.get(fileName))) {
    while (org.apache.hadoop.fs.FileSystem.get(sc.hadoopConfiguration).exists(new Path(fileName))) {
      i = i + 1
      fileName = fileNameOriginal + "-" + i
    }
    edges
      //.sortBy(_._1) //TEMP
      .saveAsTextFile(fileName)

    /*DEBUG*/

    if (compareFile != null) {
      //TEMP - Compare with ground truth
      var result = getFullResultFile(fileName, sc)
      var firstComparison = CompareGraphs.compare(compareFile, result)
      //CompareGraphs.comparePositions(compareFile.replace(numNeighbors + "", "128"), result)
      CompareGraphs.comparePositions(compareFile, result)

      if (method == "lsh") {
        var refinedGraph = graph.map({ case (v, listNeighs) => (v, (0, listNeighs)) })
        for (i <- 0 until 1) {
          println("Refined " + i)
          val timeStartR = System.currentTimeMillis();
          refinedGraph = builder.refineGraph(data, refinedGraph, numNeighbors, new EuclideanDistanceProvider())
          val fileNameR = fileName + "refined" + i
          val edgesR = refinedGraph.flatMap({ case (index, (c, neighbors)) =>
            neighbors.map({ case (destination, distance) =>
              (index, destination, distance)
            }).toSet
          })
          val totalElements = data.count()
          val e = edgesR.first()
          println("Added " + (System.currentTimeMillis() - timeStartR) + " milliseconds")

          edgesR
            //.sortBy(_._1) //TEMP
            .saveAsTextFile(fileNameR)

          //TEMP - Compare with ground truth
          result = getFullResultFile(fileNameR, sc)
          var secondComparison = CompareGraphs.compare(compareFile, result)
          //CompareGraphs.comparePositions(compareFile.replace(numNeighbors + "", "128"), result)
          CompareGraphs.comparePositions(compareFile, result)

          /* //DEBUG - Show how the graph has improved
          firstComparison.join(secondComparison)
                         .flatMap({case (element,((a,b,furthest,list), (a2,b2,furthest2,list2))) => if (b!=b2 || list!=list2)
                                                                                                      Some(element, b.diff(b2), b2.diff(b))
                                                                                                    else
                                                                                                      None})
                         .sortBy(_._1)
                         .foreach(println(_))
          */
        }
      }
    }
    /**/
    //Stop the Spark Context
    System.out.close()
    sc.stop()
  }

  //def getDefaultConfiguration():

  def getFullResultFile(fileName: String, sc: SparkContext): String = {
    if (new Path(fileName).getFileSystem(sc.hadoopConfiguration)
      .isInstanceOf[org.apache.hadoop.fs.LocalFileSystem]) {
      val out = new FileOutputStream(fileName + "/joined")
      val buffer = Array.fill[Byte](1024 * 1024)(0)
      var len = 0
      for (file <- new File(fileName).list().sorted.filter({ case s => s.startsWith("part-") })) {
        val in = new FileInputStream(fileName + "/" + file)
        len = in.read(buffer)
        while (len != -1) {
          out.write(buffer, 0, len)
          len = in.read(buffer)
        }
        in.close()
      }
      out.close()
      return fileName + "/joined"
    } else {
      var fs = org.apache.hadoop.fs.FileSystem.get(sc.hadoopConfiguration)
      var fileNameAbs = fs.getFileStatus(new org.apache.hadoop.fs.Path(fileName)).getPath.toString
      ("hdfs dfs -getmerge " + fileNameAbs + " " + fileName).!
      fs.copyFromLocalFile(new Path(fileName), new Path(fileNameAbs + "/joined"))
      ("rm -f " + fileName).!
      return fileName + "/joined"
    }
  }
}