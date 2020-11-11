package com.github.j5ik2o.akka.persistence.dynamodb.journal.dao.v1

import java.io.IOException

import akka.NotUsed
import akka.actor.ActorSystem
import akka.dispatch.Dispatchers
import akka.stream.javadsl.{ Flow => JavaFlow }
import akka.stream.scaladsl.{ Concat, Flow, RestartFlow, Source }
import com.amazonaws.services.dynamodbv2.model.{ AttributeValue, QueryRequest, QueryResult }
import com.amazonaws.services.dynamodbv2.{ AmazonDynamoDB, AmazonDynamoDBAsync }
import com.github.j5ik2o.akka.persistence.dynamodb.config.{ JournalPluginBaseConfig, PluginConfig }
import com.github.j5ik2o.akka.persistence.dynamodb.journal.JournalRow
import com.github.j5ik2o.akka.persistence.dynamodb.journal.dao.JournalRowReadDriver
import com.github.j5ik2o.akka.persistence.dynamodb.metrics.MetricsReporter
import com.github.j5ik2o.akka.persistence.dynamodb.model.{ PersistenceId, SequenceNumber }
import com.github.j5ik2o.akka.persistence.dynamodb.utils.CompletableFutureUtils._
import com.github.j5ik2o.akka.persistence.dynamodb.utils.{ DispatcherUtils, ExecutorServiceUtils }
import com.github.j5ik2o.akka.persistence.dynamodb.utils.DispatcherUtils._
import org.slf4j.LoggerFactory

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ ExecutionContext, ExecutionContextExecutorService }
import scala.jdk.CollectionConverters._

final class V1JournalRowReadDriver(
    val system: ActorSystem,
    val asyncClient: Option[AmazonDynamoDBAsync],
    val syncClient: Option[AmazonDynamoDB],
    val pluginConfig: JournalPluginBaseConfig,
    val metricsReporter: Option[MetricsReporter]
)(implicit ec: ExecutionContext)
    extends JournalRowReadDriver {
  (asyncClient, syncClient) match {
    case (None, None) =>
      throw new IllegalArgumentException("aws clients is both None")
    case _ =>
  }

  private val logger = LoggerFactory.getLogger(getClass)

  override def getJournalRows(
      persistenceId: PersistenceId,
      toSequenceNr: SequenceNumber,
      deleted: Boolean
  ): Source[Seq[JournalRow], NotUsed] = {
    val queryRequest = createGSIRequest(persistenceId, toSequenceNr, deleted)
    recursiveQuery(queryRequest, None)
      .mapConcat { response =>
        Option(response.getItems).map(_.asScala.map(_.asScala.toMap).toVector).getOrElse(Vector.empty)
      }
      .map(convertToJournalRow)
      .fold(ArrayBuffer.empty[JournalRow])(_ += _)
      .map(_.toVector)
      .withAttributes(logLevels)
  }

  override def getJournalRows(
      persistenceId: PersistenceId,
      fromSequenceNr: SequenceNumber,
      toSequenceNr: SequenceNumber,
      max: Long,
      deleted: Option[Boolean] = Some(false)
  ): Source[JournalRow, NotUsed] = {
    if (max == 0L || fromSequenceNr > toSequenceNr)
      Source.empty
    else {
      val queryRequest = createGSIRequest(
        persistenceId,
        fromSequenceNr,
        toSequenceNr,
        deleted,
        pluginConfig.queryBatchSize
      )
      recursiveQuery(queryRequest, Some(max))
        .mapConcat { response =>
          Option(response.getItems).map(_.asScala.map(_.asScala.toMap).toVector).getOrElse(Vector.empty)
        }
        .map(convertToJournalRow)
        .take(max)
        .withAttributes(logLevels)
    }
  }

  override def highestSequenceNr(
      persistenceId: PersistenceId,
      fromSequenceNr: Option[SequenceNumber],
      deleted: Option[Boolean]
  ): Source[Long, NotUsed] = {
    val queryRequest = createHighestSequenceNrRequest(persistenceId, fromSequenceNr, deleted)
    Source
      .single(queryRequest)
      .via(queryFlow)
      .flatMapConcat { response =>
        if (response.getSdkHttpMetadata.getHttpStatusCode == 200) {
          val result = Option(response.getItems)
            .map(_.asScala).map(_.map(_.asScala))
            .getOrElse(Seq.empty).toVector.headOption.map { head =>
              head(pluginConfig.columnsDefConfig.sequenceNrColumnName).getN.toLong
            }.getOrElse(0L)
          Source.single(result)
        } else {
          val statusCode = response.getSdkHttpMetadata.getHttpStatusCode
          Source.failed(new IOException(s"statusCode: $statusCode"))
        }
      }
  }.withAttributes(logLevels)

  private def createHighestSequenceNrRequest(
      persistenceId: PersistenceId,
      fromSequenceNr: Option[SequenceNumber] = None,
      deleted: Option[Boolean] = None
  ): QueryRequest = {
    new QueryRequest()
      .withTableName(pluginConfig.tableName)
      .withIndexName(pluginConfig.getJournalRowsIndexName)
      .withKeyConditionExpression(
        fromSequenceNr.map(_ => "#pid = :id and #snr >= :nr").orElse(Some("#pid = :id")).orNull
      )
      .withFilterExpression(deleted.map(_ => "#d = :flg").orNull)
      .withExpressionAttributeNames(
        (Map(
          "#pid" -> pluginConfig.columnsDefConfig.persistenceIdColumnName
        ) ++ deleted
          .map(_ => Map("#d" -> pluginConfig.columnsDefConfig.deletedColumnName)).getOrElse(Map.empty) ++
        fromSequenceNr
          .map(_ => Map("#snr" -> pluginConfig.columnsDefConfig.sequenceNrColumnName)).getOrElse(Map.empty)).asJava
      )
      .withExpressionAttributeValues(
        (Map(
          ":id" -> new AttributeValue().withS(persistenceId.asString)
        ) ++ deleted
          .map(d => Map(":flg" -> new AttributeValue().withBOOL(d))).getOrElse(Map.empty) ++ fromSequenceNr
          .map(nr => Map(":nr" -> new AttributeValue().withN(nr.asString))).getOrElse(Map.empty)).asJava
      ).withScanIndexForward(false)
      .withLimit(1)
  }

  private def recursiveQuery(
      queryRequest: QueryRequest,
      maxOpt: Option[Long],
      lastEvaluatedKey: Option[Map[String, AttributeValue]] = None,
      acc: Source[QueryResult, NotUsed] = Source.empty,
      count: Long = 0,
      index: Int = 1
  ): Source[QueryResult, NotUsed] = {
    val newQueryRequest = lastEvaluatedKey match {
      case None => queryRequest
      case Some(_) =>
        queryRequest.withExclusiveStartKey(lastEvaluatedKey.map(_.asJava).orNull)
    }
    Source
      .single(newQueryRequest).via(queryFlow).flatMapConcat { response =>
        if (response.getSdkHttpMetadata.getHttpStatusCode == 200) {
          val lastEvaluatedKey = Option(response.getLastEvaluatedKey).map(_.asScala.toMap)
          val combinedSource   = Source.combine(acc, Source.single(response))(Concat(_))
          if (lastEvaluatedKey.nonEmpty && maxOpt.fold(true) { max => (count + response.getCount) < max }) {
            logger.debug("next loop: count = {}, response.count = {}", count, response.getCount)
            recursiveQuery(queryRequest, maxOpt, lastEvaluatedKey, combinedSource, count + response.getCount, index + 1)
          } else
            combinedSource
        } else {
          val statusCode = response.getSdkHttpMetadata.getHttpStatusCode
          Source.failed(new IOException(s"statusCode: $statusCode"))
        }
      }
  }

  private def queryFlow: Flow[QueryRequest, QueryResult, NotUsed] = {
    val flow =
      ((asyncClient, syncClient) match {
        case (Some(c), None) =>
          implicit val executor = DispatcherUtils.newV1Executor(pluginConfig, system)
          JavaFlow
            .create[QueryRequest]().mapAsync(1, { request => c.queryAsync(request).toCompletableFuture }).asScala
        case (None, Some(c)) =>
          Flow[QueryRequest].map { request => c.query(request) }.withV1Dispatcher(pluginConfig)
        case _ =>
          throw new IllegalStateException("invalid state")
      }).withV1Dispatcher(pluginConfig)
        .log("queryFlow")
    if (pluginConfig.readBackoffConfig.enabled)
      RestartFlow
        .withBackoff(
          minBackoff = pluginConfig.readBackoffConfig.minBackoff,
          maxBackoff = pluginConfig.readBackoffConfig.maxBackoff,
          randomFactor = pluginConfig.readBackoffConfig.randomFactor,
          maxRestarts = pluginConfig.readBackoffConfig.maxRestarts
        ) { () => flow }
    else flow
  }

  private def createGSIRequest(
      persistenceId: PersistenceId,
      toSequenceNr: SequenceNumber,
      deleted: Boolean
  ): QueryRequest = {
    new QueryRequest()
      .withTableName(pluginConfig.tableName)
      .withIndexName(pluginConfig.getJournalRowsIndexName)
      .withKeyConditionExpression("#pid = :pid and #snr <= :snr")
      .withFilterExpression("#d = :flg")
      .withExpressionAttributeNames(
        Map(
          "#pid" -> pluginConfig.columnsDefConfig.persistenceIdColumnName,
          "#snr" -> pluginConfig.columnsDefConfig.sequenceNrColumnName,
          "#d"   -> pluginConfig.columnsDefConfig.deletedColumnName
        ).asJava
      )
      .withExpressionAttributeValues(
        Map(
          ":pid" -> new AttributeValue().withS(persistenceId.asString),
          ":snr" -> new AttributeValue().withN(toSequenceNr.asString),
          ":flg" -> new AttributeValue().withBOOL(deleted)
        ).asJava
      )
      .withLimit(pluginConfig.queryBatchSize)
  }

  private def createGSIRequest(
      persistenceId: PersistenceId,
      fromSequenceNr: SequenceNumber,
      toSequenceNr: SequenceNumber,
      deleted: Option[Boolean],
      limit: Int
  ): QueryRequest = {
    new QueryRequest()
      .withTableName(pluginConfig.tableName).withIndexName(pluginConfig.getJournalRowsIndexName).withKeyConditionExpression(
        "#pid = :pid and #snr between :min and :max"
      ).withFilterExpression(deleted.map { _ => s"#flg = :flg" }.orNull)
      .withExpressionAttributeNames(
        (Map(
          "#pid" -> pluginConfig.columnsDefConfig.persistenceIdColumnName,
          "#snr" -> pluginConfig.columnsDefConfig.sequenceNrColumnName
        ) ++ deleted
          .map(_ => Map("#flg" -> pluginConfig.columnsDefConfig.deletedColumnName)).getOrElse(Map.empty)).asJava
      )
      .withExpressionAttributeValues(
        (Map(
          ":pid" -> new AttributeValue().withS(persistenceId.asString),
          ":min" -> new AttributeValue().withN(fromSequenceNr.asString),
          ":max" -> new AttributeValue().withN(toSequenceNr.asString)
        ) ++ deleted.map(b => Map(":flg" -> new AttributeValue().withBOOL(b))).getOrElse(Map.empty)).asJava
      ).withLimit(limit)
  }

  protected def convertToJournalRow(map: Map[String, AttributeValue]): JournalRow = {
    JournalRow(
      persistenceId = PersistenceId(map(pluginConfig.columnsDefConfig.persistenceIdColumnName).getS),
      sequenceNumber = SequenceNumber(map(pluginConfig.columnsDefConfig.sequenceNrColumnName).getN.toLong),
      deleted = map(pluginConfig.columnsDefConfig.deletedColumnName).getBOOL,
      message = map.get(pluginConfig.columnsDefConfig.messageColumnName).map(_.getB.array()).get,
      ordering = map(pluginConfig.columnsDefConfig.orderingColumnName).getN.toLong,
      tags = map.get(pluginConfig.columnsDefConfig.tagsColumnName).map(_.getS)
    )
  }

}
