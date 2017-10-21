package com.tribbloids.spookystuff.uav.actions.mixin

import com.tribbloids.spookystuff.row.DataRowSchema
import com.tribbloids.spookystuff.uav.actions.UAVAction
import com.tribbloids.spookystuff.uav.planning.Constraint
import com.tribbloids.spookystuff.uav.spatial.point.Location
import org.apache.spark.mllib.uav.Vec

/**
  * unless mixin, assume cost is 0
  *
  * used in SGD-like algorithm to update delta
  * with mllib Updater
  * has built-in 'stiffness' that affects each dimension's tolerance to change
  */
trait HasLocation {
  self: UAVAction =>

  /**
    * reserved for further use
    */
  //TODO: change to Option
  def getLocation(schema: DataRowSchema): Location

//  def vector(trace: Trace, schema: DataRowSchema): DenseVector[Double] = {
//    val location = getLocation(trace, schema)
//    val home = schema.ec.spooky.getConf[UAVConf].home
//    location.getCoordinate(NED, home)
//      .get.vector
//  }

  def getStart = getLocation _
  def getEnd = getLocation _

  final val vectorDim = 3

  def shift(vector: Vec): this.type = this

  def constraint: Option[Constraint] = None
  // subclasses generally don't have to touch this part
//  override def rewrite(v: Vec, schema: DataRowSchema): Vec = v
}
