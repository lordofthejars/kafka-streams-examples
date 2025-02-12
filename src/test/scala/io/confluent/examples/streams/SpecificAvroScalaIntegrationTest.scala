/*
 * Copyright Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.confluent.examples.streams

import java.util.{Collections, Properties}

import io.confluent.examples.streams.avro.WikiFeed
import io.confluent.examples.streams.kafka.EmbeddedSingleNodeKafkaCluster
import io.confluent.kafka.serializers.{AbstractKafkaSchemaSerDeConfig, KafkaAvroDeserializer, KafkaAvroDeserializerConfig, KafkaAvroSerializer}
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization._
import org.apache.kafka.streams.scala.StreamsBuilder
import org.apache.kafka.streams.{KafkaStreams, StreamsConfig}
import org.junit._
import org.scalatestplus.junit.AssertionsForJUnit

/**
  * End-to-end integration test that demonstrates how to work on Specific Avro data.
  *
  * See [[GenericAvroScalaIntegrationTest]] for the equivalent Generic Avro integration test.
  */
class SpecificAvroScalaIntegrationTest extends AssertionsForJUnit {

  import org.apache.kafka.streams.scala.ImplicitConversions._
  import org.apache.kafka.streams.scala.serialization.Serdes._

  private val privateCluster: EmbeddedSingleNodeKafkaCluster = new EmbeddedSingleNodeKafkaCluster

  @Rule def cluster: EmbeddedSingleNodeKafkaCluster = privateCluster

  private val inputTopic = "inputTopic"
  private val outputTopic = "output-topic"

  @Before
  def startKafkaCluster(): Unit = {
    cluster.createTopic(inputTopic, 2, 1)
    cluster.createTopic(outputTopic)
  }

  @Test
  def shouldRoundTripGenericAvroDataThroughKafka(): Unit = {
    val f: WikiFeed = WikiFeed.newBuilder.setUser("alice").setIsNew(true).setContent("lorem ipsum").build
    val inputValues: Seq[WikiFeed] = Seq(f)

    //
    // Step 1: Configure and start the processor topology.
    //
    val builder = new StreamsBuilder

    val streamsConfiguration: Properties = {
      val p = new Properties()
      p.put(StreamsConfig.APPLICATION_ID_CONFIG, "specific-avro-scala-integration-test")
      p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers())
      p.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, cluster.schemaRegistryUrl)
      p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      p
    }

    // Make an implicit serde available for WikiFeed, which is required for operations such as `to()` below.
    implicit val specificAvroSerde: Serde[WikiFeed] = {
      val sas = new SpecificAvroSerde[WikiFeed]
      val isKeySerde: Boolean = false
      sas.configure(Collections.singletonMap(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, cluster.schemaRegistryUrl), isKeySerde)
      sas
    }

    // Write the input data as-is to the output topic.
    builder.stream[String, WikiFeed](inputTopic).to(outputTopic)

    val streams: KafkaStreams = new KafkaStreams(builder.build(), streamsConfiguration)
    streams.start()

    //
    // Step 2: Produce some input data to the input topic.
    //
    val producerConfig: Properties = {
      val p = new Properties()
      p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers())
      p.put(ProducerConfig.ACKS_CONFIG, "all")
      p.put(ProducerConfig.RETRIES_CONFIG, "0")
      p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[ByteArraySerializer])
      p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[KafkaAvroSerializer])
      p.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, cluster.schemaRegistryUrl)
      p
    }
    import scala.jdk.CollectionConverters._
    IntegrationTestUtils.produceValuesSynchronously(inputTopic, SeqHasAsJava(inputValues).asJava, producerConfig)

    //
    // Step 3: Verify the application's output data.
    //
    val consumerConfig = {
      val p = new Properties()
      p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers())
      p.put(ConsumerConfig.GROUP_ID_CONFIG, "specific-avro-scala-integration-test-standard-consumer")
      p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, classOf[ByteArrayDeserializer])
      p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, classOf[KafkaAvroDeserializer])
      p.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, cluster.schemaRegistryUrl)
      p.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, "true")
      p
    }
    val actualValues: java.util.List[WikiFeed] =
      IntegrationTestUtils.waitUntilMinValuesRecordsReceived(consumerConfig, outputTopic, inputValues.size)
    streams.close()
    assert(actualValues === inputValues.asJava)
  }

}
