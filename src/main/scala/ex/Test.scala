package ex

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.CommittableOffset
import akka.kafka._
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.stream.scaladsl.{RunnableGraph, Sink}
import org.apache.kafka.clients.consumer.internals.SubscriptionState
import org.apache.kafka.clients.consumer.{ConsumerRecord, MockConsumer, OffsetResetStrategy}
import org.apache.kafka.clients.producer.{MockProducer, ProducerRecord}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, ByteArraySerializer, StringDeserializer, StringSerializer}

import java.time.Duration
import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters._

object Test {

  /**
    * Dummy method to process messages from Kafka
    */
  def processValue(value: String): String = {
    value.reverse
  }

  /**
    * Creates a Kafka-to-Kafka flow:
    *   - Reads strings from Kafka
    *   - Processes strings using [[processValue]]
    *   - Writes processed strings back to Kafka to another topic
    */
  def flow(
    consumerSettings: ConsumerSettings[Array[Byte], String],
    producerSettings: ProducerSettings[Array[Byte], String],
    inputTopic: String,
    outputTopic: String,
  )(implicit system: ActorSystem): RunnableGraph[DrainingControl[Done]] = {
    // source of sources of (string, offset) reading from `inputTopic`
    val source = Consumer
      .committablePartitionedSource(consumerSettings, Subscriptions.topics(inputTopic))
      .map { case (_, partitionSource) =>
        partitionSource.map(message => (message.record.value, message.committableOffset))
      }

    // sink of (string, offset) writing to `outputTopic`
    val sink = Producer
      .committableSink(producerSettings, CommitterSettings(system))
      .contramap[(String, CommittableOffset)] { case (value, offset) =>
        val producerRecord = new ProducerRecord(outputTopic, Array.empty[Byte], value)
        val producerMessage = ProducerMessage.single(producerRecord, offset)
        producerMessage
      }

    // chain everything
    val graph = source
      .map { subSource =>
        subSource
          .map { case (value, offset) =>
            val processedValue = processValue(value)
            processedValue -> offset
          }
          .runWith(sink)
      }
      .toMat(Sink.seq) { (consumerControl, allSubSourceCompletionSignals) =>
        implicit val ec = system.dispatcher
        val unifiedCompletionSignal = allSubSourceCompletionSignals.flatMap { completionSignals =>
          Future.sequence(completionSignals).map[Done](_ => Done)
        }
        DrainingControl(consumerControl, unifiedCompletionSignal)
      }

    graph
  }

  private def createMockConsumer(
    sourceTopic: String,
    data: String,
  )(implicit system: ActorSystem): (MockConsumer[Array[Byte], String], ConsumerSettings[Array[Byte], String]) = {
    val mockConsumer = new MockConsumer[Array[Byte], String](OffsetResetStrategy.EARLIEST)

    val consumerSettings =
      ConsumerSettings[Array[Byte], String](system, new ByteArrayDeserializer, new StringDeserializer)
        .withGroupId("g") // needed solely for Akka Stream, otherwise the stream abruptly ends in a hard-to-debug manner
        .withConsumerFactory(_ => mockConsumer)
        .withStopTimeout(Duration.ZERO)

    // "Teach" the mock consumer how to behave, i.e. to simulate an actual Kafka consumption process
    // Usage reference: https://www.baeldung.com/kafka-mockconsumer
    val tp = new TopicPartition(sourceTopic, 0)
    mockConsumer.updateBeginningOffsets(Map(tp -> (0L: java.lang.Long)).asJava)

    mockConsumer.schedulePollTask(() => {
      // In the first poll we have to rebalance
      mockConsumer.rebalance(List(tp).asJava)

      // The mock consumer doesn't handle rebalance callbacks, which Akka Stream relies on to work properly, so we have
      // to do it ourselves with some reflection.
      val subscriptionsField = classOf[MockConsumer[_, _]].getDeclaredField("subscriptions")
      subscriptionsField.setAccessible(true)
      val subscriptions = subscriptionsField.get(mockConsumer).asInstanceOf[SubscriptionState]
      val rebalanceListener = subscriptions.rebalanceListener()
      rebalanceListener.onPartitionsAssigned(List(tp).asJava)
    })

    mockConsumer.schedulePollTask(() => {
      // The second poll should return our record(s)
      mockConsumer.addRecord(new ConsumerRecord(tp.topic, tp.partition, 0, Array.empty, data))
    })

    (mockConsumer, consumerSettings)
  }
  private def createMockProducer()(
    implicit system: ActorSystem,
  ): (MockProducer[Array[Byte], String], ProducerSettings[Array[Byte], String]) = {
    val mockProducer = new MockProducer(true, new ByteArraySerializer, new StringSerializer)
    val producerSettings =
      ProducerSettings[Array[Byte], String](system, new ByteArraySerializer, new StringSerializer)
        .withProducer(mockProducer)

    (mockProducer, producerSettings)
  }

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("test")
    implicit val ec = system.dispatcher
    val inputTopic = "in"
    val outputTopic = "out"
    val inputData = "hello world"
    val (mockConsumer, consumerSettings) = createMockConsumer(inputTopic, inputData)
    val (mockProducer, producerSettings) = createMockProducer()

    val graph = flow(consumerSettings, producerSettings, inputTopic, outputTopic)
    val streamStop = Promise[Unit]()
    val drainingControl = graph.run()

    mockConsumer.schedulePollTask(() => {
      for (_ <- drainingControl.drainAndShutdown())
        streamStop.success((): Unit)
    })

    for (_ <- streamStop.future) {
      val history = mockProducer.history.asScala
      assert(history.nonEmpty)
      system.terminate()
    }
  }
}
