package com.tribbloids.spookystuff.execution

import com.tribbloids.spookystuff.actions.Wget
import com.tribbloids.spookystuff.extractors.impl.Lit
import com.tribbloids.spookystuff.testutils.LocalPathDocsFixture
import com.tribbloids.spookystuff.{QueryException, SpookyEnvFixture, dsl}
import org.apache.spark.HashPartitioner

/**
  * Created by peng on 05/04/16.
  */
class TestExplorePlan extends SpookyEnvFixture with LocalPathDocsFixture {

  import dsl._

  it("ExplorePlan.toString should work") {

    val base = spooky
      .fetch(
        Wget("http://webscraper.io/test-sites/e-commerce/allinone")
      )

    val explored = base
      .explore(S"div.sidebar-nav a", ordinalField = 'index)(
        Wget('A.href),
        depthField = 'depth
      )(
        'A.text ~ 'category,
        S"h1".text ~ 'header
      )

    println(explored.plan.toString)
  }

  it("ExplorePlan should create a new beaconRDD if its upstream doesn't have one"){
    val partitioner = new HashPartitioner(8)

    val src = spooky
      .extract(Lit("abc") ~ 'dummy)

    assert(src.plan.beaconRDDOpt.isEmpty)

    val rdd1 = src
      .explore('dummy)(
        Wget(HTML_URL),
        genPartitioner = GenPartitioners.DocCacheAware(_ => partitioner)
      )()

    assert(rdd1.plan.beaconRDDOpt.get.partitioner.get eq partitioner)
  }


  it("ExplorePlan should inherit old beaconRDD from upstream if exists") {
    val partitioner = new HashPartitioner(8)
    val partitioner2 = new HashPartitioner(16)

    val rdd1 = spooky
      .extract(Lit("abc") ~ 'dummy)
      .explore('dummy)(
        Wget(HTML_URL),
        genPartitioner = GenPartitioners.DocCacheAware(_ => partitioner)
      )()

    assert(rdd1.plan.beaconRDDOpt.get.partitioner.get eq partitioner)
    val beaconRDD = rdd1.plan.beaconRDDOpt.get

    val rdd2 = rdd1
      .explore('dummy)(
        Wget(HTML_URL),
        genPartitioner = GenPartitioners.DocCacheAware(v => partitioner2)
      )()

    assert(rdd2.plan.beaconRDDOpt.get.partitioner.get eq partitioner)
    assert(rdd2.plan.beaconRDDOpt.get eq beaconRDD)
  }

  it("ExplorePlan should work recursively on directory") {

    val resourcePath = DIR_URL

    val df = spooky.create(Seq(resourcePath.toString))
      .fetch{
        Wget('_)
      }
      .explore(S"root directory".attr("path"))(
        Wget('A)
      )()
      .flatExtract(S"root file")(
        'A.ownText ~ 'leaf,
        'A.attr("path") ~ 'fullPath,
        'A.allAttr ~ 'metadata
      )
      .toDF(sort = true)

    df.show(truncate = false)
  }

  it("ExplorePlan will throw an exception if OrdinalField == DepthField") {
    val rdd1 = spooky
      .fetch{
        Wget(HTML_URL)
      }

    intercept[QueryException] {
      rdd1
        .explore(S"root directory".attr("path"), ordinalField = 'dummy)(
          Wget('A),
          depthField = 'dummy
        )()
    }
  }

  it("explore plan will avoid shuffling the latest batch to minimize repeated fetch") {
    val first = spooky
      .wget {
        DEEP_DIR_URL
      }
    val ds = first
      .explore(S"root directory uri".text)(
        Wget('A)
      )()
      .persist()

    assert(ds.squashedRDD.count() == 4)
    assert(ds.spooky.spookyMetrics.pagesFetched.value == 4)

    assert(ds.rdd.count() == 4)
    assert(ds.spooky.spookyMetrics.pagesFetched.value <= 5) //TODO: this can be reduced further
  }
}