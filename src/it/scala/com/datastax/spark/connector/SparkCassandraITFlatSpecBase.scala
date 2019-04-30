package com.datastax.spark.connector

import java.util.concurrent.Executors

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

import org.apache.commons.lang3.StringUtils
import org.scalatest._
import org.scalatest.concurrent.Eventually._
import org.scalatest.time.{Seconds, Span}

import com.datastax.bdp.test.ng.DseScalaTestBase
import com.datastax.bdp.transport.client.HadoopBasedClientConfiguration
import com.datastax.driver.core.{ProtocolVersion, Session}
import com.datastax.spark.connector.DseConfiguration._
import com.datastax.spark.connector.cql.CassandraConnector
import com.datastax.spark.connector.embedded.SparkTemplate
import com.datastax.spark.connector.testkit.{AbstractSpec, SharedEmbeddedCassandra}

trait DseITFlatSpecBase extends SparkCassandraITFlatSpecBase {
  HadoopBasedClientConfiguration.setAsClientConfigurationImpl()
  val numThreads: Int = 4
  val sparkConf = defaultConf
    .enableDseSupport()
    .setMaster(s"local[$numThreads]")
}

trait SparkCassandraITFlatSpecBase extends FlatSpec with SparkCassandraITSpecBase {
  override def report(message: String): Unit = info
}

trait SparkCassandraITWordSpecBase extends WordSpec with SparkCassandraITSpecBase

trait SparkCassandraITAbstractSpecBase extends AbstractSpec with SparkCassandraITSpecBase

trait SparkCassandraITSpecBase extends Suite with Matchers with SharedEmbeddedCassandra with SparkTemplate with DseScalaTestBase {

  val originalProps = sys.props.clone()

  def getKsName = {
    val className = this.getClass.getSimpleName
    val suffix = StringUtils.splitByCharacterTypeCamelCase(className.filter(_.isLetterOrDigit)).mkString("_")
    s"test_$suffix".toLowerCase()
  }

  def conn: CassandraConnector = ???

  def pv = conn.withClusterDo(_.getConfiguration.getProtocolOptions.getProtocolVersion)

  def report(message: String): Unit = {}

  val ks = getKsName

  def skipIfProtocolVersionGTE(protocolVersion: ProtocolVersion)(f: => Unit): Unit = {
    if (!(pv.toInt >= protocolVersion.toInt)) f
    else report(s"Skipped Because ProtcolVersion $pv >= $protocolVersion")
  }

  def skipIfProtocolVersionLT(protocolVersion: ProtocolVersion)(f: => Unit): Unit = {
    if (!(pv.toInt < protocolVersion.toInt)) f
    else report(s"Skipped Because ProtocolVersion $pv < $protocolVersion")
  }

  implicit val ec = SparkCassandraITSpecBase.ec

  def awaitAll(units: Future[Unit]*): Unit = {
    Await.result(Future.sequence(units), Duration.Inf)
  }

  def keyspaceCql(name: String = ks) =
    s"""
       |CREATE KEYSPACE IF NOT EXISTS $name
       |WITH REPLICATION = { 'class': 'SimpleStrategy', 'replication_factor': 1 }
       |AND durable_writes = false
       |""".stripMargin

  def createKeyspace(session: Session, name: String = ks): Unit = {
    session.execute(s"DROP KEYSPACE IF EXISTS $name")
    session.execute(keyspaceCql(name))
  }

  /**
    * Ensures that the tables exist in the metadata object for this session. This can be
    * an issue with some schema debouncing.
    */
  def awaitTables(tableNames: String*): Unit = {
    eventually(timeout(Span(2, Seconds))) {
      conn.withSessionDo(session =>
        session
          .getCluster
          .getMetadata
          .getKeyspace(ks)
          .getTables()
          .containsAll(tableNames.asJava)
      )
    }
  }

  def restoreSystemProps(): Unit = {
    sys.props ++= originalProps
    sys.props --= (sys.props.keySet -- originalProps.keySet)
  }

  afterClass {
    clearCache()
    restoreSystemProps()
  }
}

object SparkCassandraITSpecBase {
  val executor = Executors.newFixedThreadPool(100)
  val ec = ExecutionContext.fromExecutor(executor)
}