package io.github.interestinglab.waterdrop.benchmark

import java.util.Properties

import io.github.interestinglab.waterdrop.benchmark.bean.{KafkaCluster, KafkaSink}
import io.github.interestinglab.waterdrop.benchmark.utils.{ArgsUtil, Transform}
import kafka.common.TopicAndPartition
import kafka.message.MessageAndMetadata
import kafka.serializer.StringDecoder
import org.apache.spark.sql.functions.{col, regexp_replace, udf}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.IntegerType
import org.apache.spark.{SparkConf, SparkException}
import org.apache.spark.streaming.kafka.{KafkaUtils, OffsetRange}
import org.apache.spark.streaming.{Duration, StreamingContext}

object SparkMain {

  def main(args: Array[String]) {

    val options = ArgsUtil.ArgsParse.init(args)
    val context = createContext(options)()
    context.start()
    context.awaitTermination()
    context.stop()
  }

  def createContext(options: Map[String, String]): () => StreamingContext = {

    val interval = options("windowStep").toLong
    val topic = options("topic").toString
    val targetTopic = options("targetTopic").toString
    val appName = options("appName").toString
    val groupId = options("groupId").toString

    val conf = new SparkConf().setAppName(appName)

    System.setProperty("spark.locality.wait", "200")

    val ssc = new StreamingContext(conf, Duration(interval))

    var offsetRanges = Array[OffsetRange]()
    var km: KafkaManager = null

    val kafkaParams = Map[String, String]("metadata.broker.list" -> "localhost:9092",
      "group.id" -> groupId)
    val topics = Set(topic)

    val messageHandler = (mmd: MessageAndMetadata[String, String]) => (mmd.topic, mmd.message())
    km = new KafkaManager(kafkaParams)
    val fromOffsets = km.setOrUpdateOffsets(topics, groupId)

    val props = new Properties()
    props.put("bootstrap.servers", "localhost:9092")
    props.put("serializer", "json")
    props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    val kafkaSink = Some(ssc.sparkContext.broadcast(KafkaSink(props)))

    val rawRdd = KafkaUtils.createDirectStream[String,String,StringDecoder,StringDecoder](
      ssc,
      kafkaParams,
      topics)

    rawRdd.foreachRDD { strRDD =>

      val rowsRDD = strRDD.mapPartitions { partitions =>
        val row = partitions.map(_._2)
        val rows = row.toList
        rows.iterator
      }

      val spark = SparkSession.builder.config(rowsRDD.sparkContext.getConf).getOrCreate()
      var df = spark.read.json(rowsRDD)

      val dateFunc = udf((s: String) => {
        Transform.changeTime(s)
      })

      df = df.withColumn("timestamp", dateFunc(col("datetime")))
          .withColumn("status", col("status").cast(IntegerType))
          .withColumn("domain", regexp_replace(col("domain"), "\\.", "_"))

      df.toJSON.foreach {

        row => kafkaSink.foreach { ks =>
          ks.value.send(targetTopic, row)
        }
      }
    }

//    val rawRdd = KafkaUtils.createDirectStream[String, String, StringDecoder, StringDecoder, (String, String)](
//      ssc,
//      kafkaParams,
//      fromOffsets,
//      messageHandler)
//
//    rawRdd.transform { rdd =>
//      offsetRanges = rdd.asInstanceOf[HasOffsetRanges].offsetRanges
//      rdd
//    }

    () => ssc;
  }
}

class KafkaManager(val kafkaParams: Map[String, String]) extends Serializable {

  private val kc = new KafkaCluster(kafkaParams)

  def setOrUpdateOffsets(topics: Set[String], groupId: String): Map[TopicAndPartition, Long] = {

    val defaultOff = 10000000 // debug 在偏移的基础上再偏移10亿, 防止因为kafka删除过期log的原因导致读kafka topic出现 kafka.common.OffsetOutOfRangeException

    topics.foreach(topic => {
      var hasConsumed = true
      val partitionsE = kc.getPartitions(Set(topic))
      if (partitionsE.isLeft) {
        throw new SparkException(s"get kafka partition failed: ${partitionsE.left.get}")
      }

      val partitions = partitionsE.right.get
      val consumerOffsetsE = kc.getConsumerOffsets(groupId, partitions)
      if (consumerOffsetsE.isLeft) hasConsumed = false
      if (hasConsumed) { // 消费过
        /**
          * 如果streaming程序执行的时候出现kafka.common.OffsetOutOfRangeException，
          * 说明zk上保存的offsets已经过时了，即kafka的定时清理策略已经将包含该offsets的文件删除。
          * 针对这种情况，只要判断一下zk上的consumerOffsets和earliestLeaderOffsets的大小，
          * 如果consumerOffsets比earliestLeaderOffsets还小的话，说明consumerOffsets已过时,
          * 这时把consumerOffsets更新为earliestLeaderOffsets
          */
        val earliestLeaderOffsetsE = kc.getEarliestLeaderOffsets(partitions)
        if (earliestLeaderOffsetsE.isLeft) {
          throw new SparkException(s"get earliest leader offsets failed: ${earliestLeaderOffsetsE.left.get}")
        }

        val earliestLeaderOffsets = earliestLeaderOffsetsE.right.get
        val consumerOffsets = consumerOffsetsE.right.get

        // 可能只是存在部分分区consumerOffsets过时，所以只更新过时分区的consumerOffsets为earliestLeaderOffsets
        var offsets: Map[TopicAndPartition, Long] = Map()
        consumerOffsets.foreach({
          case (tp, n) =>
            val earliestLeaderOffset = earliestLeaderOffsets(tp).offset
            if (n < earliestLeaderOffset) {
              println(
                "consumer group:" + groupId + ",topic:" + tp.topic + ",partition:" + tp.partition +
                  " offsets已经过时，更新为" + earliestLeaderOffset)
              offsets += (tp -> earliestLeaderOffset)
            }
        })
        if (!offsets.isEmpty) {
          kc.setConsumerOffsets(groupId, offsets)
        }
      } else { // 没有消费过
        val reset = kafkaParams.get("auto.offset.reset").map(_.toLowerCase)
        var leaderOffsets: Map[TopicAndPartition, KafkaCluster.LeaderOffset] =
          null
        if (reset == Some("smallest")) {
          val leaderOffsetsE = kc.getEarliestLeaderOffsets(partitions)
          if (leaderOffsetsE.isLeft) {
            throw new SparkException(s"get earliest leader offsets failed: ${leaderOffsetsE.left.get}")
          }

          leaderOffsets = leaderOffsetsE.right.get
        } else {
          val leaderOffsetsE = kc.getLatestLeaderOffsets(partitions)
          if (leaderOffsetsE.isLeft) {
            throw new SparkException(s"get latest leader offsets failed: ${leaderOffsetsE.left.get}")
          }

          leaderOffsets = leaderOffsetsE.right.get
        }
        val offsets = leaderOffsets.map {
          // case (tp, offset) => (tp, offset.offset + defaultOff) // debug, in this debug code, largest will cause out of range offset !!!!

          case (tp, offset) => (tp, offset.offset)
        }
        kc.setConsumerOffsets(groupId, offsets)
      }
    })

    val partitionsE = kc.getPartitions(topics)
    if (partitionsE.isLeft) {
      throw new SparkException(s"get kafka partition failed: ${partitionsE.left.get}")
    }

    val partitions = partitionsE.right.get
    val consumerOffsetsE = kc.getConsumerOffsets(groupId, partitions)
    if (consumerOffsetsE.isLeft) {
      throw new SparkException(s"get kafka consumer offsets failed: ${consumerOffsetsE.left.get}")
    }

    consumerOffsetsE.right.get
  }

  def updateZKOffsetsFromoffsetRanges(offsetRanges: Array[OffsetRange]): Unit = {
    val groupId = kafkaParams.get("group.id").get

    for (offsets <- offsetRanges) {
      val topicAndPartition =
        TopicAndPartition(offsets.topic, offsets.partition)

      println("partition: " + offsets.partition + ", from: " + offsets.fromOffset + ", until: " + offsets.untilOffset)

      val o = kc.setConsumerOffsets(groupId, Map((topicAndPartition, offsets.untilOffset)))
      if (o.isLeft) {
        println(s"Error updating the offset to Kafka cluster: ${o.left.get}")
      }
    }
  }

}
