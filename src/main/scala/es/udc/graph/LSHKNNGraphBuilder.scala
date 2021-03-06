package es.udc.graph

import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.regression.LabeledPoint


// TODO The graph representation that is more suitable for the computations needs to be identified and used
object LSHKNNGraphBuilder {
  val DEFAULT_RADIUS_START = 0.1
}

object GraphMerger extends Serializable {
  def mergeNeighborLists(neighbors1: List[(Long, Double)], neighbors2: List[(Long, Double)], numNeighbors: Int): List[(Long, Double)] = {
    var sNeighbors1 = neighbors1.sortBy(_._2)
    var sNeighbors2 = neighbors2.sortBy(_._2)

    var finalNeighbors: List[(Long, Double)] = Nil

    while (finalNeighbors.size < numNeighbors && (!sNeighbors1.isEmpty || !sNeighbors2.isEmpty)) {
      if (sNeighbors2.isEmpty || (!sNeighbors1.isEmpty && sNeighbors1.head._2 < sNeighbors2.head._2)) {
        if ((finalNeighbors == Nil) || !finalNeighbors.contains(sNeighbors1.head))
          finalNeighbors = sNeighbors1.head :: finalNeighbors
        sNeighbors1 = sNeighbors1.tail
      }
      else {
        if ((finalNeighbors == Nil) || !finalNeighbors.contains(sNeighbors2.head))
          finalNeighbors = sNeighbors2.head :: finalNeighbors
        sNeighbors2 = sNeighbors2.tail
      }
    }
    finalNeighbors
  }

  def mergeGroupedNeighbors(groupedNeighbors1: List[(Int, List[(Long, Double)])], groupedNeighbors2: List[(Int, List[(Long, Double)])], numNeighbors: Int): List[(Int, List[(Long, Double)])] = {
    groupedNeighbors1.zip(groupedNeighbors2).map(
      {
        case ((grId1, l1), (grId2, l2)) => (grId1, mergeNeighborLists(l1, l2, numNeighbors))
      })
  }
}

abstract class LSHKNNGraphBuilder {
  protected final def computeGroupedGraph(data: RDD[(LabeledPoint, Long)], numNeighbors: Int, dimension: Int, hasher: Hasher, startRadius: Double, maxComparisonsPerItem: Int, measurer: DistanceProvider, grouper: GroupingProvider): RDD[(Long, List[(Int, List[(Long, Double)])])] = {
    var fullGraph: RDD[(Long, (Int, List[(Int, List[(Long, Double)])]))] = null //(node, (viewed, List[(groupingId,List[(neighbor,distance))]]))
    var currentData = data.map(_.swap)
    var radius = startRadius
    //5//0.1
    val totalElements = currentData.count()
    val bfOps: Double = totalElements * (totalElements - 1) / 2.0
    var totalOps: Long = 0
    var numBuckets: Long = 2
    var nodesLeft = currentData.count()

    //while(!currentData.isEmpty())
    //while(nodesLeft>numNeighbors)
    while ((numBuckets > 1 || nodesLeft > numNeighbors) && nodesLeft > 1) {
      //Maps each element to numTables (hash, index) pairs with hashes of keyLenght length.
      val hashRDD = currentData.flatMap({ case (index, point) =>
        hasher.getHashes(point.features, index, radius)
      });

      //TODO Should all distances be computed? Maybe there's no point in computing them if we still don't have enough neighbors for an example
      //Should they be stored/cached? It may be enough to store a boolean that records if they have been computed. LRU Cache?

      //Groups elements mapped to the same hash
      //val hashBuckets=hashRDD.groupByKey()
      var hashBuckets: RDD[(Hash, Iterable[Long], Int)] = hashRDD.groupByKey()
        .map({ case (k, l) => (k, l.toSet) })
        .flatMap({ case (k, s) => if (s.size > 1) Some((k, s, s.size)) else None })
      //println("Buckets: "+numBuckets)
      /*
      //Print buckets
      println("Buckets:")
      hashBuckets.foreach({case (hash, indices) => println(hash.values.mkString(",") + " -> " + indices)})
      */

      //println(filteredBuckets.count()+ " buckets ["+filteredBuckets.min()+", "+filteredBuckets.max()+"] elements")
      if (!hashBuckets.isEmpty()) {
        numBuckets = hashBuckets.count()
        /* SPLIT BUCKETS THAT ARE TOO LARGE - TEST
        var maxBucketSize=hashBuckets.map(_._3).max()
        val MAX_BUCKET_SIZE=50
        val RADIUS_STEP=2.5
        var currentRadius=radius/RADIUS_STEP
        val margin=1.5
        val prevStepOps=hashBuckets.map({case (h,s,n) => (n,1)})
                                     .reduceByKey(_+_)
                                     .map({case x => x._2 * x._1 * x._1 /2.0}).sum()
        while (maxBucketSize>MAX_BUCKET_SIZE*margin)
        {
          hashBuckets=splitLargeBuckets(data, hashBuckets, MAX_BUCKET_SIZE, currentRadius, hasher)
          maxBucketSize=hashBuckets.map(_._3).max()
          println("With radius "+currentRadius+" the largest bucket contains "+maxBucketSize+" elements")
          currentRadius=currentRadius/RADIUS_STEP
        }
         */

        val stepOps = hashBuckets.map({ case (h, s, n) => (n, 1) })
          .reduceByKey(_ + _)
        //val postStepOps=stepOps.map({case x => x._2 * x._1 * x._1 /2.0}).sum()

        totalOps = totalOps + stepOps.map({ case x => x._2 * x._1 * (x._1 - 1) / 2.0 }).sum().toLong

        stepOps.sortBy(_._1)
          .foreach({ case x => println(x._2 + " buckets with " + x._1 + " elements => " + (x._2 * x._1 * x._1 / 2.0) + " ops") })

        //println("Changed "+prevStepOps+"ops to "+postStepOps+"ops ("+(postStepOps/prevStepOps)+")")


        //TODO Evaluate bucket size and increase/decrease radius without bruteforcing if necessary.

        var subgraph = getGroupedGraphFromBuckets(data, hashBuckets, numNeighbors, measurer, grouper)

        //subgraph.foreach(println(_))
        //subgraph.sortBy(_._1).foreach({case (e,l) => println((e,l.sortBy(_._1)))})

        //Refine graph by checking neighbors of destination vertices
        //TODO TEST
        //subgraph=refineGraph(data,subgraph,numNeighbors)

        //Merge generate graph with existing one.
        fullGraph = mergeSubgraphs(fullGraph, subgraph, numNeighbors, measurer)
        //fullGraph.filter(_._1<20).sortBy(_._1).foreach(println(_))

        //TODO TEST
        //fullGraph=refineGraph(data,fullGraph,numNeighbors)

        //TODO Check for duplicates
        //Simplify dataset
        val newData = simplifyDataset(currentData, fullGraph, numNeighbors, maxComparisonsPerItem)
        currentData.unpersist(false)
        currentData = newData

        //TODO Possibly repartition
        currentData.cache()

        //Increment radius
        //radius*=1.5
      }
      //else
      //  radius*=2
      radius *= 2
      nodesLeft = currentData.count()

      //DEBUG
      println(" ----- " + nodesLeft + " nodes left. Radius:" + radius)
      //fullGraph.foreach(println(_))
    }
    if (nodesLeft > 0) {
      //println("Elements left:"+currentData.map(_._1).collect().mkString(", "))
      if (fullGraph != null) {

        /*
        //If there are any items left, look in the neighbor's neighbors.
        val neighbors:RDD[Iterable[Long]]=fullGraph.cartesian(currentData)
                                                   .flatMap(
                                                       {
                                                         case ((dest, (viewed,groupedNeig)), (orig,point)) =>
                                                             groupedNeig.flatMap(
                                                                 {
                                                                   case (grId,neig) =>
                                                                     if (neig.map(_._1).contains(orig))
                                                                        Some((orig,(dest :: neig.map(_._1)).toSet))
                                                                      else
                                                                        None
                                                                 }
                                                                 )
                                                        })
                                                   .reduceByKey({case (dl1,dl2) => dl1 ++ dl2})
                                                   .map({case (o,dl) => dl + o})                                                   
        */
        val remainingData = currentData.map({ case (id, point) => (grouper.getGroupId(point), List((id, point))) }).reduceByKey(_ ++ _).collect()
        val bRemainingData = sparkContextSingleton.getInstance().broadcast(remainingData)
        val neighbors: RDD[Iterable[Long]] = fullGraph.flatMap(
          {
            case (dest, (viewed, groupedNeig)) =>
              val rData = bRemainingData.value
              val rDataMap = rData.toMap
              groupedNeig.flatMap(
                {
                  case (grId, neig) =>
                    if (rDataMap.contains(grId))
                      rDataMap(grId).flatMap(
                        {
                          case (idOrig, pointOrig) =>
                            if (neig.map(_._1).contains(idOrig))
                              Some((idOrig, (dest :: neig.map(_._1)).toSet))
                            else
                              None
                        })
                    else
                      None
                }
              )
          })
          .reduceByKey({ case (dl1, dl2) => dl1 ++ dl2 })
          .map({ case (o, dl) => (dl + o).toSet })
        totalOps = totalOps + neighbors.map({ case x => x.size * x.size }).sum().toLong
        val subgraph = getGroupedGraphFromElementIndexLists(data, neighbors, numNeighbors, measurer)

        if (!subgraph.isEmpty()) {
          //subgraph.foreach(println(_))
          fullGraph = mergeSubgraphs(fullGraph, subgraph, numNeighbors, measurer)
          currentData = simplifyDataset(currentData, fullGraph, numNeighbors, maxComparisonsPerItem)
          nodesLeft = currentData.count()
        }
        //println(nodesLeft+" nodes left after first attempt")
      }
      if (nodesLeft > 0) //No solution other than check this points with every other
      {
        val pairs = currentData.cartesian(fullGraph.map({ case (point, neighbors) => point }))
        val subgraph = getGroupedGraphFromPairs(data, pairs, numNeighbors, measurer, grouper)
        fullGraph = mergeSubgraphs(fullGraph, subgraph, numNeighbors, measurer)
        totalOps = totalOps + pairs.count()
      }
    }
    println("Operations wrt bruteforce: " + (totalOps / bfOps))
    //println((totalOps/bfOps)+"#")

    return fullGraph.map({ case (node, (viewed, neighs)) => (node, neighs) })
  }

  protected final def getHashes(data: RDD[(Long, LabeledPoint)], hasher: Hasher, radius: Double): RDD[(Hash, Long)] = {
    data.flatMap({ case (index, point) => hasher.getHashes(point.features, index, radius) });
  }

  def computeBestKeyLength(data: RDD[(LabeledPoint, Long)], keyLength: Int, hasher: Hasher, startRadius: Double): (Int, Double) = {
    val currentData = data.map(_.swap)
    var tmpHasher = hasher
    var radius = startRadius
    var tmpHash = getHashes(currentData, hasher, radius)
    var update = false
    var upFlag = false
    var downFlag = false

    do {
      update = false
      if (tmpHasher.keyLength < hasher.keyLength) {
        tmpHash = tmpHash.map({ case (hash, index) =>
          (hash.cutLen(tmpHasher.keyLength), index)
        });
      } else if (tmpHasher.keyLength != hasher.keyLength) {
        tmpHash = getHashes(currentData, tmpHasher, radius)
      }
      /*
            val hashBuckets = tmpHash.groupByKey()
              .map({ case (k, l) => (k, l.toSet) })
              .map({ case (k, s) => (k, s, s.size) })

            val numBuckets = hashBuckets.count()
            val stepOps = hashBuckets.map({ case (h, s, n) => (n, 1) }).reduceByKey(_ + _)
      */
      val stepOps = tmpHash.aggregateByKey(0)({ case (n, index) => n + 1 }, { case (n1, n2) => n1 + n2 })
        .map({ case (h, n) => (n, 1) }).reduceByKey(_ + _).filter({ case (n1, x) => n1 != 1 })

      val numBuckets = if (stepOps.isEmpty()) 0 else stepOps.reduce({ case ((n1, x), (n2, y)) => (n1 + n2, x + y) })._2
      val maxGroup = if (stepOps.isEmpty()) (0, 0) else stepOps.reduce({ case ((n1, x), (n2, y)) => if (x > y) (n1, x) else (n2, y) })

      //val mean = stepOps.map({ case (n1, x) => n1 * x }).reduce({ case (x1, x2) => x1 + x2 }) / numBuckets
      //val desv = Math.sqrt(stepOps.map({ case (n, x) => (x - mean) * (x - mean) * n }).reduce({ case (d1, d2) => d1 + d2 }) / numBuckets)

      if ((stepOps.isEmpty() || tmpHasher.keyLength < hasher.keyLength / 2) && !upFlag) {
        radius *= 2
        tmpHasher = tmpHasher.update(hasher.keyLength, tmpHasher.numTables)
        tmpHash = getHashes(currentData, tmpHasher, radius)
        update = true
      } else {
        //if (numBuckets * 0.5 < maxGroup._2)
        val elems = stepOps.map({ case (n1, x) => n1 })
        val mean = elems.reduce({ case (n1, n2) => n1 + n2 }) * 1.0 / elems.count()
        val desv = Math.sqrt(elems.map({ case n => (n - mean) * (n - mean) }).reduce({ case (n1, n2) => n1 + n2 }) * 1.0 / elems.count())
        val maxElems = stepOps.reduce({ case ((n1, x), (n2, y)) => if (n1 > n2) (n1, x) else (n2, y) })._1
        if (numBuckets * 0.5 < maxGroup._2 && (maxElems < mean + 3 * desv || desv == 0)) {
          tmpHasher = tmpHasher.update(tmpHasher.keyLength - 2, tmpHasher.numTables)
          downFlag = true
          update = !upFlag
        }
        else if (!downFlag) {
          tmpHasher = tmpHasher.update(tmpHasher.keyLength + 2, tmpHasher.numTables)
          update = true
          upFlag = true
        }
      }

      stepOps.sortBy(_._1).repartition(1)
        .foreach({ case x => println(x._2 + " buckets with " + x._1 + " elements") })

      if (update) {
        println("keyLength update to " + tmpHasher.keyLength + " with radius " + radius)
      } else {
        println("keyLength set to " + tmpHasher.keyLength + " with radius " + radius)
      }


    } while (update)
    (tmpHasher.keyLength, radius)
  }

  protected def log2(n: Double): Double = {
    Math.log10(n) / Math.log10(2)
  }

  def computeGroupedGraph(data: RDD[(LabeledPoint, Long)], numNeighbors: Int, dimension: Int, hasherKeyLength: Int, hasherNumTables: Int, startRadius: Double, maxComparisonsPerItem: Int, measurer: DistanceProvider, grouper: GroupingProvider, factor: Double): RDD[(Long, List[(Int, List[(Long, Double)])])]
  = {
    var hKLength: Int = hasherKeyLength
    var hNTables: Int = hasherNumTables
    var mComparisons: Int = maxComparisonsPerItem
    var radius = startRadius

    if (hKLength <= 0) {
      //hKLength = Math.floor(Math.sqrt(dimension) + Math.sqrt(data.count() / dimension) - 10).toInt
      hKLength = Math.ceil(log2(data.count() / dimension)).toInt + 1
      val (hK, r) = computeBestKeyLength(data, hKLength, new EuclideanLSHasher(dimension, hKLength, 1), radius)
      hKLength = hK
      radius = r
    }
    if (hNTables <= 0) {
      //hNTables = (hKLength / 2 + 1) * (hKLength / 2 + 1)
      hNTables = Math.floor(Math.pow(log2(dimension), 2) * factor).toInt
    }
    if (mComparisons <= 0) {
      //mComparisons = hNTables
      mComparisons = Math.abs(Math.ceil(hNTables * Math.sqrt(factor) * Math.sqrt(log2(data.count()/(dimension*0.1))))).toInt
    }

    println("R0:" + radius + " num_tables:" + hNTables + " keyLength:" + hKLength + " maxComparisons:" + mComparisons)

    return computeGroupedGraph(data,
      numNeighbors,
      dimension,
      new EuclideanLSHasher(dimension, hKLength, hNTables), //Default to an EuclideanHasher
      radius,
      mComparisons,
      measurer,
      grouper)
  }

  def computeGraph(data: RDD[(LabeledPoint, Long)], numNeighbors: Int, dimension: Int, hasherKeyLength: Int, hasherNumTables: Int, startRadius: Double, maxComparisonsPerItem: Int, measurer: DistanceProvider, factor: Double): RDD[(Long, List[(Long, Double)])] = {
    val graph = computeGroupedGraph(data, numNeighbors, dimension, hasherKeyLength, hasherNumTables, startRadius, maxComparisonsPerItem, measurer, new DummyGroupingProvider(), factor)
    return graph.map(
      {
        case (index, groupedNeighs) => (index, groupedNeighs.head._2)
      })
  }

  def computeGraph(data: RDD[(LabeledPoint, Long)], numNeighbors: Int, hasherKeyLength: Int, hasherNumTables: Int, startRadius: Double, maxComparisonsPerItem: Int, measurer: DistanceProvider, factor: Double): RDD[(Long, List[(Long, Double)])]
  = computeGraph(data,
    numNeighbors,
    data.map({ case (point, index) => point.features.size }).max(), //Get dimension from dataset
    hasherKeyLength,
    hasherNumTables,
    startRadius,
    maxComparisonsPerItem,
    measurer,
    factor)

  def computeGroupedGraph(data: RDD[(LabeledPoint, Long)], numNeighbors: Int, hasherKeyLength: Int, hasherNumTables: Int, startRadius: Double, maxComparisonsPerItem: Int, measurer: DistanceProvider, grouper: GroupingProvider, factor: Double): RDD[(Long, List[(Int, List[(Long, Double)])])]
  = computeGroupedGraph(data,
    numNeighbors,
    data.map({ case (point, index) => point.features.size }).max(), //Get dimension from dataset
    hasherKeyLength,
    hasherNumTables,
    startRadius,
    maxComparisonsPerItem,
    measurer,
    grouper,
    factor)

  protected def splitLargeBuckets(data: RDD[(LabeledPoint, Long)], hashBuckets: RDD[(Hash, Iterable[Long], Int)], maxBucketSize: Int, radius: Double, hasher: Hasher): RDD[(Hash, Iterable[Long], Int)]

  protected def getGroupedGraphFromBuckets(data: RDD[(LabeledPoint, Long)], hashBuckets: RDD[(Hash, Iterable[Long], Int)], numNeighbors: Int, measurer: DistanceProvider, grouper: GroupingProvider): RDD[(Long, (Int, List[(Int, List[(Long, Double)])]))]

  protected def getGroupedGraphFromElementIndexLists(data: RDD[(LabeledPoint, Long)], elementIndexLists: RDD[Iterable[Long]], numNeighbors: Int, measurer: DistanceProvider): RDD[(Long, (Int, List[(Int, List[(Long, Double)])]))]

  protected def getGroupedGraphFromPairs(data: RDD[(LabeledPoint, Long)], pairs: RDD[((Long, LabeledPoint), Long)], numNeighbors: Int, measurer: DistanceProvider, grouper: GroupingProvider): RDD[(Long, (Int, List[(Int, List[(Long, Double)])]))]

  protected def getGroupedGraphFromIndexPairs(data: RDD[(LabeledPoint, Long)], pairs: RDD[(Long, Long)], numNeighbors: Int, measurer: DistanceProvider, grouper: GroupingProvider): RDD[(Long, (Int, List[(Int, List[(Long, Double)])]))]

  private def simplifyDataset(dataset: RDD[(Long, LabeledPoint)], currentGraph: RDD[(Long, (Int, List[(Int, List[(Long, Double)])]))], numNeighbors: Int, maxComparisonsPerItem: Int): RDD[(Long, LabeledPoint)] = {
    //TODO More advanced simplifications can be done, such as removing only elements that are very "surrounded" (i.e. they landed in various large buckets)
    //Remove only elements that already have all their neighbors
    //fullGraph.foreach(println(_))
    val completeNodes = if (maxComparisonsPerItem > 0)
      currentGraph.filter({ case (index, (viewed, groupedList)) => viewed > maxComparisonsPerItem })
    else
      currentGraph.filter({ case (index, (viewed, groupedList)) => groupedList.forall({ case (grId, list) => list.toSet.size >= numNeighbors }) })

    //println("Complete nodes:")
    //completeNodes.filter(_._1<20).sortBy(_._1).foreach(println)
    //Still have to filter out those that link to an element that will be removed
    val deletableElements = completeNodes
    /*???????
    val deletableElements=completeNodes.flatMap({case (index, list) =>
                                                   list.map({case (dest, dist) => (dest, index)})
                                                })
                                      //Join with the full graph, to use edges that are in there (TODO CHECK)
                                      .join(fullGraph)
                                      .flatMap({case (index, (from, list)) => 
                                                  list.map({case (v, dist) => (index, v)})
                                               })
                                      .leftOuterJoin(completeNodes)
                                      .flatMap({case (dest, (origin, list)) =>
                                                  if (list==None)
                                                    Some((origin, 1))
                                                  else
                                                    None
                                                })
                                      .reduceByKey({case (n1, n2) => 1})
                                      
    */
    //println("To be removed:")
    //if (deletableElements.count()>1)
    //  deletableElements.foreach(println)
    //Remove deletable elements from dataset
    return dataset.leftOuterJoin(deletableElements).flatMap({ case (index, (neighbors1, n)) =>
      if (n == None)
        Some((index, neighbors1))
      else
        None
    })
  }

  private def mergeSubgraphs(g1: RDD[(Long, (Int, List[(Int, List[(Long, Double)])]))], g2: RDD[(Long, (Int, List[(Int, List[(Long, Double)])]))], numNeighbors: Int, measurer: DistanceProvider): RDD[(Long, (Int, List[(Int, List[(Long, Double)])]))] = {
    if (g1 == null) return g2
    if (g2 == null) return g1
    return g1.union(g2).reduceByKey({ case ((viewed1, groupedNeighbors1), (viewed2, groupedNeighbors2)) =>
      (viewed1 + viewed2, GraphMerger.mergeGroupedNeighbors(groupedNeighbors1, groupedNeighbors2, numNeighbors))
    })
  }

  /**
    * @param g
    * @return Resulting graph of searching for closer neighbors in the neighbors of the neighbors for each node
    */
  /*private */ def refineGraph(data: RDD[(LabeledPoint, Long)], g: RDD[(Long, (Int, List[(Long, Double)]))], numNeighbors: Int, measurer: DistanceProvider): RDD[(Long, (Int, List[(Long, Double)]))] = {
    val grouper = new DummyGroupingProvider()
    val grId = grouper.getGroupId(data.first()._1)
    val graph = refineGroupedGraph(data, g.map({ case (index, (comps, neighs)) => (index, (comps, List((grId, neighs)))) }), numNeighbors, measurer, grouper)
    return graph.map({ case (i1, (comps1, groupedNeighbors)) => (i1, (comps1, groupedNeighbors.head._2)) })
  }

  /*private */ def refineGroupedGraph(data: RDD[(LabeledPoint, Long)], g: RDD[(Long, (Int, List[(Int, List[(Long, Double)])]))], numNeighbors: Int, measurer: DistanceProvider, grouper: GroupingProvider): RDD[(Long, (Int, List[(Int, List[(Long, Double)])]))] = {
    var pairsWithNewNeighbors: RDD[(Long, Long)] = g.flatMap(
      {
        case (node, (viewed, groupedNeighbors)) =>
          groupedNeighbors.flatMap(
            {
              case (grId, neighbors) => neighbors.map({ case (dest, dist) => (dest, node) })
            })
      })
      .join(g)
      .flatMap({ case (dest, (node, (v, neighsGroupedNeighs))) => neighsGroupedNeighs.flatMap(
        {
          case (grId, neighsNeighs) =>
            neighsNeighs.flatMap(
              { case (d, dist) => if (node != d)
                Some((node, d))
              else
                None
              })
        })
      })
    println("Added " + pairsWithNewNeighbors.count() + " comparisons")
    pairsWithNewNeighbors = pairsWithNewNeighbors.groupByKey().flatMap({ case (d, neighs) => neighs.toSet.toArray.map({ case x => (d, x) }) })
    println("Reduced to " + pairsWithNewNeighbors.count() + " comparisons")
    var subgraph = getGroupedGraphFromIndexPairs(data,
      pairsWithNewNeighbors,
      numNeighbors,
      measurer,
      grouper)
    return mergeSubgraphs(g, subgraph, numNeighbors, measurer)
  }
}

class LSHLookupKNNGraphBuilder(data: RDD[(LabeledPoint, Long)]) extends LSHKNNGraphBuilder {
  var lookup: BroadcastLookupProvider = new BroadcastLookupProvider(data)

  override def splitLargeBuckets(data: RDD[(LabeledPoint, Long)], hashBuckets: RDD[(Hash, Iterable[Long], Int)], maxBucketSize: Int, radius: Double, hasher: Hasher): RDD[(Hash, Iterable[Long], Int)] = {
    val l = lookup
    hashBuckets.flatMap({ case (k, s, n) => s.map({ x => (k, x, n) }) })
      .flatMap({ case (k, x, bucketSize) => if (bucketSize < maxBucketSize) Some((k, x))
      else hasher.getHashes(l.lookup(x).features, x, radius).map({ case (nk, i) => (k.concat(nk), i) })
      }) //Concat new key
      .groupByKey()
      .map({ case (k, l) => (k, l.toSet) })
      .flatMap({ case (k, s) => if (s.size > 1) Some((k, s, s.size)) else None })
  }

  override def getGroupedGraphFromBuckets(data: RDD[(LabeledPoint, Long)], hashBuckets: RDD[(Hash, Iterable[Long], Int)], numNeighbors: Int, measurer: DistanceProvider, grouper: GroupingProvider): RDD[(Long, (Int, List[(Int, List[(Long, Double)])]))] = {
    val l = lookup
    //Discard single element hashes and for the rest get every possible pairing to build graph
    val graph = hashBuckets.filter(_._2.size > 1)
      //TODO Possibly repartition after filter
      //.repartition
      .flatMap({ case (hash, indices, size) =>
      //Remove duplicates from indices
      val arrayIndices = indices.toSet.toArray
      if (arrayIndices.length > 1)
        BruteForceKNNGraphBuilder.computeGroupedGraph(arrayIndices, l, numNeighbors, measurer, grouper)
      else
        Nil
    })
      //Merge neighbors found for the same element in different hash buckets
      .reduceByKey({ case ((viewed1, groupedNeighbors1), (viewed2, groupedNeighbors2)) => (viewed1 + viewed2, GraphMerger.mergeGroupedNeighbors(groupedNeighbors1, groupedNeighbors2, numNeighbors))
    })
    graph
  }

  override def getGroupedGraphFromElementIndexLists(data: RDD[(LabeledPoint, Long)], elementIndexLists: RDD[Iterable[Long]], numNeighbors: Int, measurer: DistanceProvider): RDD[(Long, (Int, List[(Int, List[(Long, Double)])]))] = {
    val l = lookup
    //Discard single element hashes and for the rest get every possible pairing to build graph
    val graph = elementIndexLists.filter(_.size > 1)
      //TODO Possibly repartition after filter
      //.repartition
      .flatMap({ case (indices) =>
      //Remove duplicates from indices
      val arrayIndices = indices.toSet.toArray
      if (arrayIndices.length > 1)
        BruteForceKNNGraphBuilder.computeGroupedGraph(arrayIndices, l, numNeighbors, measurer)
      else
        Nil
    })
      //Merge neighbors found for the same element in different hash buckets
      .reduceByKey({ case ((viewed1, groupedNeighbors1), (viewed2, groupedNeighbors2)) =>
      (viewed1 + viewed2, GraphMerger.mergeGroupedNeighbors(groupedNeighbors1, groupedNeighbors2, numNeighbors))
    })

    graph
  }

  override def getGroupedGraphFromPairs(data: RDD[(LabeledPoint, Long)], pairs: RDD[((Long, LabeledPoint), Long)], numNeighbors: Int, measurer: DistanceProvider, grouper: GroupingProvider): RDD[(Long, (Int, List[(Int, List[(Long, Double)])]))] = {
    val l = lookup
    //Discard single element hashes and for the rest get every possible pairing to build graph
    val graph = pairs.map(
      {
        case ((i1, p1), i2) =>
          val p2 = l.lookup(i2)
          ((i1, grouper.getGroupId(p2)), (1, List((i2, measurer.getDistance(p1, p2)))))
      })
      //Merge neighbors found for the same element in different hash buckets
      .reduceByKey({ case ((v1, neighbors1), (v2, neighbors2)) =>
      (v1 + v2, GraphMerger.mergeNeighborLists(neighbors1, neighbors2, numNeighbors))
    })
      .map(
        {
          case ((i1, grId2), (v, neighborList)) => (i1, (v, List((grId2, neighborList))))
        }
      )
      .reduceByKey(
        {
          case ((v1, groupedNeighbors1), (v2, groupedNeighbors2)) =>
            (v1 + v2, GraphMerger.mergeGroupedNeighbors(groupedNeighbors1, groupedNeighbors2, numNeighbors))
        }
      )

    graph
  }

  override def getGroupedGraphFromIndexPairs(data: RDD[(LabeledPoint, Long)], pairs: RDD[(Long, Long)], numNeighbors: Int, measurer: DistanceProvider, grouper: GroupingProvider): RDD[(Long, (Int, List[(Int, List[(Long, Double)])]))] = {
    val l = lookup
    //Discard single element hashes and for the rest get every possible pairing to build graph
    val graph = pairs.map({ case (i1, i2) =>
      var p2 = l.lookup(i2)
      ((i1, grouper.getGroupId(p2)), (1, List((i2, measurer.getDistance(l.lookup(i1), p2)))))
    })
      //Merge neighbors found for the same element in different hash buckets
      .reduceByKey({ case ((v1, neighbors1), (v2, neighbors2)) =>
      (v1 + v2, GraphMerger.mergeNeighborLists(neighbors1, neighbors2, numNeighbors))
    })
      .map(
        {
          case ((i1, grId2), (v, neighborList)) => (i1, (v, List((grId2, neighborList))))
        }
      )
      .reduceByKey(
        {
          case ((v1, groupedNeighbors1), (v2, groupedNeighbors2)) =>
            (v1 + v2, GraphMerger.mergeGroupedNeighbors(groupedNeighbors1, groupedNeighbors2, numNeighbors))
        }
      )

    graph
  }
}

/*object LSHGraphXKNNGraphBuilder// extends LSHKNNGraphBuilder
{
  def getGraph(data:RDD[(LabeledPoint,Long)], numNeighbors:Int, dimension:Int)=
  {
    val radius=0.5
    val hasher=new EuclideanLSHasher(dimension)
    val hashRDD=data.flatMap({case (point, index) =>
                              hasher.getHashes(point.features, index, radius)
                            });
    val hashBuckets=hashRDD.groupByKey()
    val closeEdges=hashBuckets.filter(_._2.size>1)
                           //.repartition
                           .flatMap({case (hash, indices) =>
                                       //Remove duplicates from indices
                                       val arrayIndices=indices.toSet.toArray
                                       if (arrayIndices.length>1)
                                       {
                                         var list:List[Pair[Long, Long]]=List()
                                         //Cartesian product
                                         for (i <- 0 until arrayIndices.length)
                                           for (j <- i+1 until arrayIndices.length)
                                           {
                                             list=(arrayIndices(i), arrayIndices(j)) :: (arrayIndices(j), arrayIndices(i)) :: list
                                           }
                                         
                                         list
                                       }
                                       else
                                         Nil
                                       })
                                      
      val graph=GraphUtils.calculateNearest(data,
                                            numNeighbors,
                                            {case (x,y) => Vectors.sqdist(x.features, y.features)},
                                            closeEdges)
                                            
      graph
  }
  /*override def getGraphFromBuckets(data:RDD[(LabeledPoint,Long)], hashBuckets:RDD[(Hash, Iterable[Long])], numNeighbors:Int):RDD[(Long, List[(Long, Double)])]=
  {
    
  }*/
}*/