package org.tribbloid.spookystuff

import org.apache.hadoop.conf.Configuration
import org.apache.spark._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{SQLContext, SchemaRDD}
import org.tribbloid.spookystuff.entity.{Key, KeyLike, PageRow}
import org.tribbloid.spookystuff.sparkbinding.{PageRowRDD, SchemaRDDView, StringRDDView}
import org.tribbloid.spookystuff.utils.Utils

import scala.collection.immutable.{ListMap, ListSet}
import scala.language.implicitConversions
import scala.reflect.ClassTag

import org.apache.spark.SparkContext._

object Metrics {

  private def accumulator[T](initialValue: T, name: String)(implicit param: AccumulatorParam[T]) = {
    new Accumulator(initialValue, param, Some(name))
  }
}

case class Metrics(
                    driverInitialized: Accumulator[Int] = Metrics.accumulator(0, "driverInitialized"),
                    driverReclaimed: Accumulator[Int] = Metrics.accumulator(0, "driverReclaimed"),

                    sessionInitialized: Accumulator[Int] = Metrics.accumulator(0, "sessionInitialized"),
                    sessionReclaimed: Accumulator[Int] = Metrics.accumulator(0, "sessionReclaimed"),

                    DFSReadSuccess: Accumulator[Int] = Metrics.accumulator(0, "DFSReadSuccess"),
                    DFSReadFail: Accumulator[Int] = Metrics.accumulator(0, "DFSReadFail"),

                    DFSWriteSuccess: Accumulator[Int] = Metrics.accumulator(0, "DFSWriteSuccess"),
                    DFSWriteFail: Accumulator[Int] = Metrics.accumulator(0, "DFSWriteFail"),

                    pagesFetched: Accumulator[Int] = Metrics.accumulator(0, "pagesFetched"),
                    pagesFetchedFromWeb: Accumulator[Int] = Metrics.accumulator(0, "pagesFetchedFromWeb"),
                    pagesFetchedFromCache: Accumulator[Int] = Metrics.accumulator(0, "pagesFetchedFromCache")
               ) { //TODO: change name to avoid mingle with Spark UI metrics

  def toJSON: String = {
    val tuples = this.productIterator.flatMap{
      case acc: Accumulator[_] => acc.name.map(_ -> acc.value)
      case _ => None
    }.toSeq

    val map = ListMap(tuples: _*)

    Utils.toJson(map, beautiful = true)
  }
}

case class SpookyContext (
                           @transient sqlContext: SQLContext, //can't be used on executors
                           @transient private val _spookyConf: SpookyConf = new SpookyConf(), //can only be used on executors after broadcast
                           var metrics: Metrics = new Metrics() //accumulators cannot be broadcasted
                           ) {

  def this(sqlContext: SQLContext) {
    this(sqlContext, new SpookyConf(), new Metrics())
  }

  def this(sc: SparkContext) {
    this(new SQLContext(sc))
  }

  def this(conf: SparkConf) {
    this(new SparkContext(conf))
  }

  import org.tribbloid.spookystuff.views._

  var broadcastedSpookyConf = sqlContext.sparkContext.broadcast(_spookyConf)

  def conf = if (_spookyConf == null) broadcastedSpookyConf.value
  else _spookyConf

  def broadcast(): SpookyContext ={
    var broadcastedSpookyConf = sqlContext.sparkContext.broadcast(_spookyConf)
    this
  }

  val broadcastedHadoopConf = if (sqlContext!=null) sqlContext.sparkContext.broadcast(new SerializableWritable(this.sqlContext.sparkContext.hadoopConfiguration))
  else null

  def hadoopConf: Configuration = broadcastedHadoopConf.value.value

  def zeroIn(): SpookyContext ={
    metrics = new Metrics()
    this
  }

//  def newZero: SpookyContext = this.copy(metrics = new Metrics)

  def getContextForNewInput = if (conf.sharedMetrics) this
  else this.copy(metrics = new Metrics)

  @transient lazy val noInput: PageRowRDD = PageRowRDD(this.sqlContext.sparkContext.noInput, spooky = getContextForNewInput)

  implicit def stringRDDToItsView(rdd: RDD[String]): StringRDDView = new StringRDDView(rdd)

  implicit def schemaRDDToItsView(rdd: SchemaRDD): SchemaRDDView = new SchemaRDDView(rdd)

  //  implicit def selfToPageRowRDD(rdd: RDD[PageRow]): PageRowRDD = PageRowRDD(rdd, spooky = this.copy(metrics = new Metrics))

  //every input or noInput will generate a new metrics
  implicit def RDDToPageRowRDD[T: ClassTag](rdd: RDD[T]): PageRowRDD = {
    import org.tribbloid.spookystuff.views._
    import scala.reflect._

    rdd match {
      case rdd: SchemaRDD =>
        val self = new SchemaRDDView(rdd).asMapRDD.map{
          map =>
            new PageRow(
              Option(map)
                .getOrElse(Map())
                .map(tuple => (Key(tuple._1),tuple._2))
            )
        }
        new PageRowRDD(self, keys = ListSet(rdd.schema.fieldNames: _*).map(Key(_)), spooky = getContextForNewInput)
      case _ if classOf[Map[_,_]].isAssignableFrom(classTag[T].runtimeClass) => //use classOf everywhere?
        val canonRdd = rdd.map(
          map =>map.asInstanceOf[Map[_,_]].canonizeKeysToColumnNames
        )

        val jsonRDD = canonRdd.map(
          map =>
            Utils.toJson(map)
        )
        val schemaRDD = sqlContext.jsonRDD(jsonRDD)
        val self = canonRdd.map(
          map =>
            PageRow(map.map(tuple => (Key(tuple._1),tuple._2)), Seq())
        )
        new PageRowRDD(self, keys = ListSet(schemaRDD.schema.fieldNames: _*).map(Key(_)), spooky = getContextForNewInput)
      case _ =>
        val self = rdd.map{
          str =>
            var cells = Map[KeyLike,Any]()
            if (str!=null) cells = cells + (Key("_") -> str)

            new PageRow(cells)
        }
        new PageRowRDD(self, keys = ListSet(Key("_")), spooky = getContextForNewInput)
    }
  }
}