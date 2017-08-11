package com.tribbloids.spookystuff.uav.dsl

import com.tribbloids.spookystuff.actions.TraceView
import com.tribbloids.spookystuff.dsl.GenPartitioner
import com.tribbloids.spookystuff.dsl.GenPartitionerLike.Instance
import com.tribbloids.spookystuff.execution.ExecutionContext
import com.tribbloids.spookystuff.row.BeaconRDD
import com.tribbloids.spookystuff.uav.actions.HasCost
import com.tribbloids.spookystuff.uav.planning.{JSpritSolver, MinimaxSolver}
import org.apache.spark.rdd.RDD

import scala.reflect.ClassTag

/**
  * Created by peng on 31/12/16.
  */
object GenPartitioners {

  case class MinimaxCost(
                          prepartitioner: GenPartitioner = {
                            com.tribbloids.spookystuff.dsl.GenPartitioners.Wide()
                          },

                          // if missing, use ALL OF THEM!
                          numUAVOverride: Option[Int] = None,

                          // how much effort optimizer spend to reduce total length instead of max length
                          cohesiveness: Double = 0.05,
                          solver: MinimaxSolver = JSpritSolver,

                          // for debugging only.
                          solutionPlotPathOpt: Option[String] = None,
                          progressPlotPathOpt: Option[String] = None
                        ) extends GenPartitioner {

    def getInstance[K >: TraceView: ClassTag](ec: ExecutionContext): Instance[K] = {
      Inst[K](ec)
    }

    case class Inst[K >: TraceView](ec: ExecutionContext)(
      implicit val ctg: ClassTag[K]
    ) extends Instance[K] {

      //gather all UAVActions to driver and use a local solver (JSprit) to rearrange them.
      override def groupByKey[V: ClassTag](
                                            rdd: RDD[(K, V)],
                                            beaconRDDOpt: Option[BeaconRDD[K]]
                                          ): RDD[(K, Iterable[V])] = {

        val spooky = ec.spooky

        val preprocessed = prepartitioner.getInstance[K](ec).groupByKey(rdd, beaconRDDOpt)

        val bifurcated: RDD[((Option[TraceView], Option[K]), Iterable[V])] = preprocessed
          .map {
            case (k: TraceView, v) =>
              val c = k.children
              val result: (Option[TraceView], Option[K]) = {
                if (c.exists(_.isInstanceOf[HasCost])) Some(k) -> None
                else None -> Some(k: K)
              }
              result -> v
            case (k, v) =>
              val result: (Option[TraceView], Option[K]) = None -> Some(k)
              result -> v
          }
        //          .mapValues(v => v.toList)

        ec.scratchRDDs.persist(bifurcated)

        val hasCostRDD: RDD[(TraceView, Iterable[V])] = bifurcated
          .flatMap(tt => tt._1._1.map(v => v -> tt._2))

        val realignedRDD: RDD[(K, Iterable[V])] = solver.getRealignedRDD(
          MinimaxCost.this, spooky, hasCostRDD)
          .map(tuple => (tuple._1: K) -> tuple._2)

        val hasNoCostRDD: RDD[(K, Iterable[V])] = bifurcated
          .flatMap(tt => tt._1._2.map(v => v -> tt._2))

        val result = realignedRDD.union(hasNoCostRDD)

        result
      }

      //        val status_traceRDD: RDD[(UAVStatus, Seq[TraceView])] = spooky.sparkContext.parallelize(status_traces)
      //
      //        //if you don't know cogroup preserve sequence, don't use it.
      //        val realignedTraceRDD: RDD[(Seq[TraceView], Link)] = linkRDD.cogroup {
      //          status_traceRDD
      //          //linkRDD doesn't have a partitioner so no need to explicit
      //        }
      //          .values
      //          .map {
      //            tuple =>
      //              assert(tuple._1.size == 1)
      //              assert(tuple._2.size == 1)
      //              tuple._2.head -> tuple._1.head
      //          }
      //
      //        val trace_index_linkRDD: RDD[(K, (Int, Link))] = realignedTraceRDD.flatMap {
      //          tuple =>
      //            tuple._1
      //              .zipWithIndex
      //              .map {
      //                tt =>
      //                  tt._1 -> (tt._2 -> tuple._2)
      //              }
      //        }
      //
      //        val cogroupedRDD: RDD[(K, (Iterable[(Int, Link)], Iterable[V]))] =
      //          trace_index_linkRDD.cogroup {
      //            rdd
      //          }
      //
      //        val kv_index = cogroupedRDD.map {
      //          triplet =>
      //            val is_links = triplet._2._1.toSeq
      //            is_links.size match {
      //              case 0 =>
      //                val k = triplet._1
      //                (k -> triplet._2._2) -> -1
      //              case 1 =>
      //                val k = triplet._1.asInstanceOf[TraceView]
      //                val updatedK = k.copy(children = List(PreferUAV(is_links.head._2)) ++ k.children)
      //                (updatedK -> triplet._2._2) -> is_links.head._1
      //              case _ =>
      //                throw new AssertionError(s"size cannot be ${is_links.size}")
      //            }
      //        }
      //
      //        kv_index.mapPartitions {
      //          itr =>
      //            itr.toSeq.sortBy(_._2).map(_._1).iterator
      //        }
      //
      //        // merge with trace that doesn't contain UAVNavigations
      //        // also carry data with them, by treating realigned link_traceRDD like a beaconRDD.
      //      }
    }
  }
}
