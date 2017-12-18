package net.sansa_stack.examples.spark.ml.clustering

import scala.collection.mutable
import org.apache.spark.sql.SparkSession
import org.apache.log4j.{ Level, Logger }
import org.apache.spark.graphx.GraphLoader
import org.apache.jena.riot.{ Lang, RDFDataMgr }
import java.io.ByteArrayInputStream
import org.apache.spark.graphx._
import org.apache.spark.rdd.RDD
import net.sansa_stack.ml.spark.clustering.{ RDFGraphPICClustering => RDFGraphPICClusteringAlg }

object RDFGraphPIClustering {

  def main(args: Array[String]) {
    parser.parse(args, Config()) match {
      case Some(config) =>
        run(config.in, config.k, config.maxIterations)
      case None =>
        println(parser.usage)
    }
  }

  def run(input: String, k: Int, maxIterations: Int): Unit = {

    val spark = SparkSession.builder
      .appName(s"Power Iteration Clustering example ( $input )")
      .master("local[*]")
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .getOrCreate()

    Logger.getRootLogger.setLevel(Level.ERROR)

    println("============================================")
    println("| Power Iteration Clustering   example     |")
    println("============================================")

    val sparkSession = SparkSession.builder
      .master("local[*]")
      .appName(" Power Iteration Clustering example (" + input + ")")
      .getOrCreate()
    Logger.getRootLogger.setLevel(Level.ERROR)

    // Load the graph
    //val graph = GraphLoader.edgeListFile(sparkSession.sparkContext, input)

    // Load the RDF dataset
    val RDFfile = sparkSession.sparkContext.textFile(input).map(line =>
      RDFDataMgr.createIteratorTriples(new ByteArrayInputStream(line.getBytes), Lang.NTRIPLES, null).next())

    val r = RDFfile.map(f => {
      val s = f.getSubject.getURI
      val p = f.getPredicate.getURI
      val o = f.getObject.getURI

      (s, p, o)
    })

    val v11 = r.map(f => f._1)
    val v22 = r.map(f => f._3)
    val indexedmap = (v11.union(v22)).distinct().zipWithIndex()

    val vertices: RDD[(VertexId, String)] = indexedmap.map(x => (x._2, x._1))
    val _iriToId: RDD[(String, VertexId)] = indexedmap.map(x => (x._1, x._2))

    val tuples = r.keyBy(f => f._1).join(indexedmap).map({
      case (k, ((s, p, o), si)) => (o, (si, p))
    })

    val edgess: RDD[Edge[String]] = tuples.join(indexedmap).map({
      case (k, ((si, p), oi)) => Edge(si, oi, p)
    })

    val graph = org.apache.spark.graphx.Graph(vertices, edgess)

    val model = RDFGraphPICClusteringAlg(sparkSession, graph, k, maxIterations).run()

    val clusters = model.assignments.collect().groupBy(_.cluster).mapValues(_.map(_.id))
    val assignments = clusters.toList.sortBy { case (k, v) => v.length }
    val assignmentsStr = assignments
      .map {
        case (k, v) =>
          s"$k -> ${v.sorted.mkString("[", ",", "]")}"
      }.mkString(",")
    val sizesStr = assignments.map {
      _._2.size
    }.sorted.mkString("(", ",", ")")
    println(s"Cluster assignments: $assignmentsStr\ncluster sizes: $sizesStr")

    spark.stop

  }

  case class Config(in: String = "", k: Int = 3, maxIterations: Int = 50)

  val defaultParams = Config()

  val parser = new scopt.OptionParser[Config]("RDFGraphPIClustering") {

    head("PowerIterationClusteringExample: an example PIC app using concentric circles.")

    opt[String]('i', "input").required().valueName("<path>")
      .text(s"path to file that contains the input files (in N-Triple format)")
      .action((x, c) => c.copy(in = x))

    opt[Int]('k', "k")
      .text(s"number of circles (/clusters), default: ${defaultParams.k}")
      .action((x, c) => c.copy(k = x))

    opt[Int]("maxIterations")
      .text(s"number of iterations, default: ${defaultParams.maxIterations}")
      .action((x, c) => c.copy(maxIterations = x))

    help("help").text("prints this usage text")
  }
}