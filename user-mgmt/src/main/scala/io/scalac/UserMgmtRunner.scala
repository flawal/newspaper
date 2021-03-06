package io.scalac

import akka.actor.ActorSystem
import akka.kafka.{ConsumerSettings, ProducerMessage, ProducerSettings, Subscriptions}
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import com.typesafe.config.ConfigFactory
import io.scalac.inbound.{ChangeDetectedPBDeserializer, SlickUserRepository, SubscribeUserPBDeserializer, UserRepository}
import io.scalac.newspaper.events.RequestNotification
import io.scalac.outbound.{SlickTranslationService, RequestNotificationPBSerializer, TranslationService}
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, ByteArraySerializer}

import scala.concurrent.{ExecutionContext, Future}

object UserMgmtRunner extends App {
  implicit val system = ActorSystem("Newspaper-User-Mgmt-System")
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val materializer = ActorMaterializer()

  UserMgmtHelper.checkDBMigrations()

  val changeDetectedConsumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, ChangeDetectedPBDeserializer())
    .withGroupId("User-Mgmt")
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
  val producerSettings = ProducerSettings(system, new ByteArraySerializer, new RequestNotificationPBSerializer)

  val changeDetectedSubscription = Subscriptions.topics("newspaper")

  val userService = UserMgmtHelper.buildRepository()
  val translateMessages: TranslationService = new SlickTranslationService(userService)

  //Inbound 1: enrich Change Detected events with user data
  Consumer.committableSource(changeDetectedConsumerSettings, changeDetectedSubscription)
    .mapAsyncUnordered(10) { msg => // we don't care about order
      println(s"Received ${msg.record.value().pageUrl}") // TODO: replace with proper logging
      translateMessages.translate(msg.record.value()).map { case publishRequests =>
        publishRequests.map { case publishRequest =>
          val record = new ProducerRecord[Array[Byte], RequestNotification]("newspaper-notifications", publishRequest)
          ProducerMessage.Message(record, msg.committableOffset)
        }
      }
    }
    .mapConcat(identity)
    .via(Producer.flow(producerSettings))
    .map(_.message.passThrough)
    .mapAsync(1) { msg =>
      msg.commitScaladsl()
    }.runWith(Sink.ignore)

  //Inbound 2: add new users
  val subscribeUserConsumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new SubscribeUserPBDeserializer())
    .withGroupId("User-Mgmt")
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
  val subscribeUserSubscription = Subscriptions.topics("newspaper-users")

  Consumer.committableSource(subscribeUserConsumerSettings, subscribeUserSubscription)
    .mapAsync(1) { msg =>
      userService.addOrUpdate(msg.record.value()).map(_ => msg) //TODO: check failure handling
    }.mapAsync(1) { msg =>
      msg.committableOffset.commitScaladsl()
    }
  .runWith(Sink.ignore)
}


object UserMgmtHelper {
  def buildRepository(): UserRepository = {
    import slick.jdbc.PostgresProfile.api._
    val db = Database.forConfig("relational-datastore")
    new SlickUserRepository(db)
  }

  def checkDBMigrations() = {
    import org.flywaydb.core.Flyway

    val conf = ConfigFactory.load()
    val url = conf.getString("relational-datastore.url")
    val topLevelUser = conf.getString("migration.user")
    val topLevelUserPass = conf.getString("migration.password")


    val flyway = new Flyway()
    flyway.setDataSource(url, topLevelUser, topLevelUserPass)
    flyway.migrate()
  }
}
