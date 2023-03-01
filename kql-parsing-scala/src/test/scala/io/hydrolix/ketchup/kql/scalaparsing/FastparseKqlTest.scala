package io.hydrolix.ketchup.kql.scalaparsing

import fastparse._
import io.hydrolix.ketchup.kql.scalaparsing.FastparseKql.pProgram
import io.hydrolix.ketchup.model.expr.{BooleanLiteral, DoubleLiteral, StringLiteral}
import io.hydrolix.ketchup.model.kql.KqlTestCase
import io.hydrolix.ketchup.util.JSON
import org.junit.jupiter.api.Assertions.{assertEquals, fail}
import org.junit.jupiter.api.{Disabled, Test}

import java.io.{FileInputStream, InputStream}
import java.nio.file.{Files, Path}
import scala.jdk.Accumulator
import scala.jdk.StreamConverters._
import scala.util.Using

//noinspection SpellCheckingInspection
// TODO compare parse output with expected IR trees, don't just assert successful parsing
class FastparseKqlTest {
  @Test
  def `should "parse all saved kql cases`(): Unit = {
    val files = Files.find(Path.of("../kql-testing/src/main/resources/parserTestCases"), 1, { (path, _) =>
      path.toFile.getName.endsWith(".json")
    }).toScala(Accumulator).toList.sortBy(_.getFileName.toString)

    for ((file, i) <- files.zipWithIndex) {
      Using.resource(new FileInputStream(file.toFile)) { is =>
        runFile(i, file.toFile.getAbsolutePath, is)
      }
    }
  }

  private def mustParse(s: String): Unit = {
    fastparse.parse(s, pProgram(_), verboseFailures=true) match {
      case Parsed.Success(got, _) => println(got)
      case f: Parsed.Failure => fail(f.longMsg)
    }
  }

  @Test
  @Disabled("AND inference isn't supported with the grammar cribbed from OpenSearch Dashboards")
  def `AND inference`(): Unit = {
    val queryString = "foo:bar baz:/var/log/quux"
    mustParse(queryString)
  }

  @Test
  def `uuid`(): Unit = {
    val queryString = "\"f9a5fe9e-944b-41a3-93f8-7b2b4da21e2d\""
    mustParse(queryString)
  }

  @Test
  def `multiple unquoted 2`(): Unit = {
    val queryString = "foo bar"
    mustParse(queryString)
  }

  @Test
  def `Lucene-style comparisons`(): Unit = {
    val queryString = "http_status:>=500 OR http_status:<=399"
    mustParse(queryString)
  }

  @Test
  def `Wildcard field`(): Unit = {
    val queryString = "Title:*Outback*"
    mustParse(queryString)
  }

  @Test
  def `literals`(): Unit = {
    val matches = Map(
      "foo" -> new StringLiteral("foo"),
      "true" -> BooleanLiteral.getTrue,
      "false" -> BooleanLiteral.getFalse,
      "42" -> new DoubleLiteral(42.0),
      ".3" -> new DoubleLiteral(0.3),
      ".36" -> new DoubleLiteral(0.36),
      ".00001" -> new DoubleLiteral(0.00001),
      "3" -> new DoubleLiteral(3.0),
      "-4" -> new DoubleLiteral(-4.0),
      "0" -> new DoubleLiteral(0.0),
      "2.0" -> new DoubleLiteral(2.0),
      "0.8" -> new DoubleLiteral(0.8),
      "790.9" -> new DoubleLiteral(790.9),
      "0.0001" -> new DoubleLiteral(0.0001),
      "96565646732345" -> new DoubleLiteral(96565646732345.0),
      "..4" -> new StringLiteral("..4"),
      ".3text" -> new StringLiteral(".3text"),
      "text" -> new StringLiteral("text"),
      "." -> new StringLiteral("."),
      "-" -> new StringLiteral("-"),
      "001" -> new StringLiteral("001"),
      "00.2" -> new StringLiteral("00.2"),
      "0.0.1" -> new StringLiteral("0.0.1"),
      "3." -> new StringLiteral("3."),
      "--4" -> new StringLiteral("--4"),
      "-.4" -> new StringLiteral("-.4"),
      "-0" -> new StringLiteral("-0"),
      "00949" -> new StringLiteral("00949"),
      // TODO fix this """"\\\\"""" -> new StringLiteral("""\\"""),
      // TODO fix this """\\\(\)\:\<\>\"\*""" -> new StringLiteral("""\():<>"*""")
    )

    for ((kql, expected) <- matches) {
      parse(kql, FastparseKql.pLiteral(_)) match {
        case Parsed.Success(res, _) => assertEquals(expected, res)
        case _: Parsed.Failure => fail(kql)
      }
    }
  }

  private def runFile(i: Int, name: String, is: InputStream): Unit = {
    println(s"Test case #${i + 1} ($name):")
    val json = new String(is.readAllBytes(), "UTF-8")
    val kqlCase = JSON.INSTANCE.getObjectMapper.readValue(json, classOf[KqlTestCase])
    println(s"  Name: ${kqlCase.getName}")
    println(s"   KQL: ${kqlCase.getKql}")

    try {
      parse(kqlCase.getKql, FastparseKql.pProgram(_)) match {
        case Parsed.Success(ast, _) =>
          val json = JSON.INSTANCE.getObjectMapper.writeValueAsString(ast)
          if (ast == kqlCase.getParsed) {
            System.out.println(s"  [SUCCESS] $json")
          } else {
            System.err.println(s"  [MISMATCH] $json")
            fail("mismatch")
          }
        case f: Parsed.Failure =>
          System.err.println(s"  [PARSEFAIL] ${f.longMsg}")
          fail(f.longMsg)
      }
    } catch {
      case e: Exception =>
        System.err.println(s"  [THROWN] ${e.getMessage}")
        fail(e)
    }
  }
}
