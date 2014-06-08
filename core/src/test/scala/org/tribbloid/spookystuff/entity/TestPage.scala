package org.tribbloid.spookystuff.entity

import org.scalatest.{Tag, FunSuite}
import org.tribbloid.spookystuff.entity.Visit

/**
 * Created by peng on 05/06/14.
 */

class TestPage extends FunSuite {

  object pageBuilderTag extends Tag("PageBuilder")

  test("visit and snapshot", pageBuilderTag) {
    val builder = new PageBuilder()
    builder.interact(Visit("http://www.google.com"))
    val page = builder.getSnapshot
    //    val url = builder.getUrl

    assert(page.content.startsWith("<!DOCTYPE html>"))
    assert(page.content.contains("<title>Google</title>"))

    assert(page.resolvedUrl.startsWith("http://www.google.ca/?gfe_rd=cr&ei="))
    //    assert(url === "http://www.google.com")
  }

  test("visit, input submit and snapshot", pageBuilderTag) {
    val builder = new PageBuilder()
    builder.interact(Visit("https://www.linkedin.com/"))
    builder.interact(Input("input#first","Adam"))
    builder.interact(Input("input#last","Muise"))
    builder.interact(Submit("input[name=\"search\"]"))
    val page = builder.getSnapshot
    //    val url = builder.getUrl

    assert(page.content.contains("<title>Adam Muise profiles | LinkedIn</title>"))
    assert(page.resolvedUrl === "https://www.linkedin.com/pub/dir/?first=Adam&last=Muise")
    //    assert(url === "https://www.linkedin.com/ Input(input#first,Adam) Input(input#last,Muise) Submit(input[name=\"search\"])")
  }

  test("resolve", pageBuilderTag) {
    val results = PageBuilder.resolve(
      Visit("https://www.linkedin.com/"),
      Snapshot(),
      Input("input#first","Adam"),
      Input("input#last","Muise"),
      Submit("input[name=\"search\"]"),
      Snapshot("after_search")
    )

    val resultsList = results.toArray
    assert(resultsList.size === 2)
    val res1 = resultsList(0)
    val res2 = resultsList(1)

    val id1 = Seq[Interaction](Visit("https://www.linkedin.com/"))
    assert(res1._1 === id1)
    assert(res1._2.content.contains("<title>World's Largest Professional Network | LinkedIn</title>"))
    assert(res1._2.resolvedUrl === "https://www.linkedin.com/")
    assert(res1._3.size === 2)

    val id2 = Seq[Interaction](Visit("https://www.linkedin.com/"),Input("input#first","Adam"),Input("input#last","Muise"),Submit("input[name=\"search\"]"))
    assert(res2._2.content.contains("<title>Adam Muise profiles | LinkedIn</title>"))
    assert(res2._2.resolvedUrl === "https://www.linkedin.com/pub/dir/?first=Adam&last=Muise")
    assert(res1._3.size === 7)
  }
}