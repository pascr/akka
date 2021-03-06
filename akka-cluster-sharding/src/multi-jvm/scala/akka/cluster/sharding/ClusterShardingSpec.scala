/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.sharding

import akka.cluster.sharding.ShardCoordinator.Internal.{ ShardStopped, HandOff }
import akka.cluster.sharding.ShardRegion.Passivate
import language.postfixOps
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor._
import akka.cluster.Cluster
import akka.persistence.PersistentActor
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.SharedLeveldbJournal
import akka.persistence.journal.leveldb.SharedLeveldbStore
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import akka.testkit.TestEvent.Mute
import java.io.File
import org.apache.commons.io.FileUtils
import akka.cluster.singleton.ClusterSingletonManager
import akka.cluster.singleton.ClusterSingletonManagerSettings

object ClusterShardingSpec extends MultiNodeConfig {
  val controller = role("controller")
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")
  val sixth = role("sixth")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.auto-down-unreachable-after = 0s
    akka.cluster.roles = ["backend"]
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
    akka.persistence.journal.leveldb-shared.store {
      native = off
      dir = "target/journal-ClusterShardingSpec"
    }
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/snapshots-ClusterShardingSpec"
    akka.cluster.sharding {
      role = backend
      retry-interval = 1 s
      handoff-timeout = 10 s
      shard-start-timeout = 5s
      entry-restart-backoff = 1s
      rebalance-interval = 2 s
      least-shard-allocation-strategy {
        rebalance-threshold = 2
        max-simultaneous-rebalance = 1
      }
    }
    """))

  nodeConfig(sixth) {
    ConfigFactory.parseString("""akka.cluster.roles = ["frontend"]""")
  }

  //#counter-actor
  case object Increment
  case object Decrement
  final case class Get(counterId: Long)
  final case class EntryEnvelope(id: Long, payload: Any)

  case object Stop
  final case class CounterChanged(delta: Int)

  class Counter extends PersistentActor {
    import ShardRegion.Passivate

    context.setReceiveTimeout(120.seconds)

    // self.path.parent.parent.name is the type name (utf-8 URL-encoded)
    // self.path.name is the entry identifier (utf-8 URL-encoded)
    override def persistenceId: String = self.path.parent.parent.name + "-" + self.path.name

    var count = 0
    //#counter-actor

    override def postStop(): Unit = {
      super.postStop()
      // Simulate that the passivation takes some time, to verify passivation bufffering
      Thread.sleep(500)
    }
    //#counter-actor

    def updateState(event: CounterChanged): Unit =
      count += event.delta

    override def receiveRecover: Receive = {
      case evt: CounterChanged ⇒ updateState(evt)
    }

    override def receiveCommand: Receive = {
      case Increment      ⇒ persist(CounterChanged(+1))(updateState)
      case Decrement      ⇒ persist(CounterChanged(-1))(updateState)
      case Get(_)         ⇒ sender() ! count
      case ReceiveTimeout ⇒ context.parent ! Passivate(stopMessage = Stop)
      case Stop           ⇒ context.stop(self)
    }
  }
  //#counter-actor

  val idExtractor: ShardRegion.IdExtractor = {
    case EntryEnvelope(id, payload) ⇒ (id.toString, payload)
    case msg @ Get(id)              ⇒ (id.toString, msg)
  }

  val numberOfShards = 12

  val shardResolver: ShardRegion.ShardResolver = {
    case EntryEnvelope(id, _) ⇒ (id % numberOfShards).toString
    case Get(id)              ⇒ (id % numberOfShards).toString
  }

}

// only used in documentation
object ClusterShardingDocCode {
  import ClusterShardingSpec._

  //#counter-extractor
  val idExtractor: ShardRegion.IdExtractor = {
    case EntryEnvelope(id, payload) ⇒ (id.toString, payload)
    case msg @ Get(id)              ⇒ (id.toString, msg)
  }

  val numberOfShards = 100

  val shardResolver: ShardRegion.ShardResolver = {
    case EntryEnvelope(id, _) ⇒ (id % numberOfShards).toString
    case Get(id)              ⇒ (id % numberOfShards).toString
  }
  //#counter-extractor

}

class ClusterShardingMultiJvmNode1 extends ClusterShardingSpec
class ClusterShardingMultiJvmNode2 extends ClusterShardingSpec
class ClusterShardingMultiJvmNode3 extends ClusterShardingSpec
class ClusterShardingMultiJvmNode4 extends ClusterShardingSpec
class ClusterShardingMultiJvmNode5 extends ClusterShardingSpec
class ClusterShardingMultiJvmNode6 extends ClusterShardingSpec
class ClusterShardingMultiJvmNode7 extends ClusterShardingSpec

class ClusterShardingSpec extends MultiNodeSpec(ClusterShardingSpec) with STMultiNodeSpec with ImplicitSender {
  import ClusterShardingSpec._

  override def initialParticipants = roles.size

  val storageLocations = List(
    "akka.persistence.journal.leveldb.dir",
    "akka.persistence.journal.leveldb-shared.store.dir",
    "akka.persistence.snapshot-store.local.dir").map(s ⇒ new File(system.settings.config.getString(s)))

  override protected def atStartup() {
    runOn(controller) {
      storageLocations.foreach(dir ⇒ if (dir.exists) FileUtils.deleteDirectory(dir))
    }
  }

  override protected def afterTermination() {
    runOn(controller) {
      storageLocations.foreach(dir ⇒ if (dir.exists) FileUtils.deleteDirectory(dir))
    }
  }

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system) join node(to).address
      createCoordinator()
    }
    enterBarrier(from.name + "-joined")
  }

  def createCoordinator(): Unit = {
    val allocationStrategy = new ShardCoordinator.LeastShardAllocationStrategy(rebalanceThreshold = 2, maxSimultaneousRebalance = 1)
    def coordinatorProps(rebalanceEnabled: Boolean) =
      ShardCoordinator.props(handOffTimeout = 10.seconds, shardStartTimeout = 10.seconds,
        rebalanceInterval = if (rebalanceEnabled) 2.seconds else 3600.seconds,
        snapshotInterval = 3600.seconds, allocationStrategy)

    List("counter", "rebalancingCounter", "PersistentCounterEntries", "AnotherPersistentCounter",
      "PersistentCounter", "RebalancingPersistentCounter", "AutoMigrateRegionTest").foreach { coordinatorName ⇒
        val rebalanceEnabled = coordinatorName.toLowerCase.startsWith("rebalancing")
        system.actorOf(ClusterSingletonManager.props(
          singletonProps = ShardCoordinatorSupervisor.props(failureBackoff = 5.seconds, coordinatorProps(rebalanceEnabled)),
          terminationMessage = PoisonPill,
          settings = ClusterSingletonManagerSettings(system)),
          name = coordinatorName + "Coordinator")
      }
  }

  def createRegion(typeName: String, rememberEntries: Boolean): ActorRef = system.actorOf(ShardRegion.props(
    typeName = typeName,
    entryProps = Props[Counter],
    role = None,
    coordinatorPath = "/user/" + typeName + "Coordinator/singleton/coordinator",
    retryInterval = 1.second,
    shardFailureBackoff = 1.second,
    entryRestartBackoff = 1.second,
    snapshotInterval = 1.hour,
    bufferSize = 1000,
    rememberEntries = rememberEntries,
    idExtractor = idExtractor,
    shardResolver = shardResolver),
    name = typeName + "Region")

  lazy val region = createRegion("counter", rememberEntries = false)
  lazy val rebalancingRegion = createRegion("rebalancingCounter", rememberEntries = false)

  lazy val persistentEntriesRegion = createRegion("PersistentCounterEntries", rememberEntries = true)
  lazy val anotherPersistentRegion = createRegion("AnotherPersistentCounter", rememberEntries = true)
  lazy val persistentRegion = createRegion("PersistentCounter", rememberEntries = true)
  lazy val rebalancingPersistentRegion = createRegion("RebalancingPersistentCounter", rememberEntries = true)
  lazy val autoMigrateRegion = createRegion("AutoMigrateRegionTest", rememberEntries = true)

  "Cluster sharding" must {

    "setup shared journal" in {
      // start the Persistence extension
      Persistence(system)
      runOn(controller) {
        system.actorOf(Props[SharedLeveldbStore], "store")
      }
      enterBarrier("peristence-started")

      runOn(first, second, third, fourth, fifth, sixth) {
        system.actorSelection(node(controller) / "user" / "store") ! Identify(None)
        val sharedStore = expectMsgType[ActorIdentity].ref.get
        SharedLeveldbJournal.setStore(sharedStore, system)
      }

      enterBarrier("after-1")
    }

    "work in single node cluster" in within(20 seconds) {
      join(first, first)

      runOn(first) {
        region ! EntryEnvelope(1, Increment)
        region ! EntryEnvelope(1, Increment)
        region ! EntryEnvelope(1, Increment)
        region ! EntryEnvelope(1, Decrement)
        region ! Get(1)
        expectMsg(2)
      }

      enterBarrier("after-2")
    }

    "use second node" in within(20 seconds) {
      join(second, first)

      runOn(second) {
        region ! EntryEnvelope(2, Increment)
        region ! EntryEnvelope(2, Increment)
        region ! EntryEnvelope(2, Increment)
        region ! EntryEnvelope(2, Decrement)
        region ! Get(2)
        expectMsg(2)

        region ! EntryEnvelope(11, Increment)
        region ! EntryEnvelope(12, Increment)
        region ! Get(11)
        expectMsg(1)
        region ! Get(12)
        expectMsg(1)
      }
      enterBarrier("second-update")
      runOn(first) {
        region ! EntryEnvelope(2, Increment)
        region ! Get(2)
        expectMsg(3)
        lastSender.path should ===(node(second) / "user" / "counterRegion" / "2" / "2")

        region ! Get(11)
        expectMsg(1)
        // local on first
        lastSender.path should ===(region.path / "11" / "11")
        region ! Get(12)
        expectMsg(1)
        lastSender.path should ===(node(second) / "user" / "counterRegion" / "0" / "12")
      }
      enterBarrier("first-update")

      runOn(second) {
        region ! Get(2)
        expectMsg(3)
        lastSender.path should ===(region.path / "2" / "2")
      }

      enterBarrier("after-3")
    }

    "support passivation and activation of entries" in {
      runOn(second) {
        region ! Get(2)
        expectMsg(3)
        region ! EntryEnvelope(2, ReceiveTimeout)
        // let the Passivate-Stop roundtrip begin to trigger buffering of subsequent messages
        Thread.sleep(200)
        region ! EntryEnvelope(2, Increment)
        region ! Get(2)
        expectMsg(4)
      }
      enterBarrier("after-4")
    }

    "support proxy only mode" in within(10.seconds) {
      runOn(second) {
        val proxy = system.actorOf(ShardRegion.proxyProps(
          typeName = "counter",
          role = None,
          coordinatorPath = "/user/counterCoordinator/singleton/coordinator",
          retryInterval = 1.second,
          bufferSize = 1000,
          idExtractor = idExtractor,
          shardResolver = shardResolver),
          name = "regionProxy")

        proxy ! Get(1)
        expectMsg(2)
        proxy ! Get(2)
        expectMsg(4)
      }
      enterBarrier("after-5")
    }

    "failover shards on crashed node" in within(30 seconds) {
      // mute logging of deadLetters during shutdown of systems
      if (!log.isDebugEnabled)
        system.eventStream.publish(Mute(DeadLettersFilter[Any]))
      enterBarrier("logs-muted")

      runOn(controller) {
        testConductor.exit(second, 0).await
      }
      enterBarrier("crash-second")

      runOn(first) {
        val probe1 = TestProbe()
        awaitAssert {
          within(1.second) {
            region.tell(Get(2), probe1.ref)
            probe1.expectMsg(4)
            probe1.lastSender.path should ===(region.path / "2" / "2")
          }
        }
        val probe2 = TestProbe()
        awaitAssert {
          within(1.second) {
            region.tell(Get(12), probe2.ref)
            probe2.expectMsg(1)
            probe2.lastSender.path should ===(region.path / "0" / "12")
          }
        }
      }

      enterBarrier("after-6")
    }

    "use third and fourth node" in within(15 seconds) {
      join(third, first)
      join(fourth, first)

      runOn(third) {
        for (_ ← 1 to 10)
          region ! EntryEnvelope(3, Increment)
        region ! Get(3)
        expectMsg(10)
        lastSender.path should ===(region.path / "3" / "3") // local
      }
      enterBarrier("third-update")

      runOn(fourth) {
        for (_ ← 1 to 20)
          region ! EntryEnvelope(4, Increment)
        region ! Get(4)
        expectMsg(20)
        lastSender.path should ===(region.path / "4" / "4") // local
      }
      enterBarrier("fourth-update")

      runOn(first) {
        region ! EntryEnvelope(3, Increment)
        region ! Get(3)
        expectMsg(11)
        lastSender.path should ===(node(third) / "user" / "counterRegion" / "3" / "3")

        region ! EntryEnvelope(4, Increment)
        region ! Get(4)
        expectMsg(21)
        lastSender.path should ===(node(fourth) / "user" / "counterRegion" / "4" / "4")
      }
      enterBarrier("first-update")

      runOn(third) {
        region ! Get(3)
        expectMsg(11)
        lastSender.path should ===(region.path / "3" / "3")
      }

      runOn(fourth) {
        region ! Get(4)
        expectMsg(21)
        lastSender.path should ===(region.path / "4" / "4")
      }

      enterBarrier("after-7")
    }

    "recover coordinator state after coordinator crash" in within(60 seconds) {
      join(fifth, fourth)

      runOn(controller) {
        testConductor.exit(first, 0).await
      }
      enterBarrier("crash-first")

      runOn(fifth) {
        val probe3 = TestProbe()
        awaitAssert {
          within(1.second) {
            region.tell(Get(3), probe3.ref)
            probe3.expectMsg(11)
            probe3.lastSender.path should ===(node(third) / "user" / "counterRegion" / "3" / "3")
          }
        }
        val probe4 = TestProbe()
        awaitAssert {
          within(1.second) {
            region.tell(Get(4), probe4.ref)
            probe4.expectMsg(21)
            probe4.lastSender.path should ===(node(fourth) / "user" / "counterRegion" / "4" / "4")
          }
        }

      }

      enterBarrier("after-8")
    }

    "rebalance to nodes with less shards" in within(60 seconds) {

      runOn(fourth) {
        for (n ← 1 to 10) {
          rebalancingRegion ! EntryEnvelope(n, Increment)
          rebalancingRegion ! Get(n)
          expectMsg(1)
        }
      }
      enterBarrier("rebalancing-shards-allocated")

      join(sixth, third)

      runOn(sixth) {
        awaitAssert {
          val probe = TestProbe()
          within(3.seconds) {
            var count = 0
            for (n ← 1 to 10) {
              rebalancingRegion.tell(Get(n), probe.ref)
              probe.expectMsgType[Int]
              if (probe.lastSender.path == rebalancingRegion.path / (n % 12).toString / n.toString)
                count += 1
            }
            count should be >= (2)
          }
        }
      }

      enterBarrier("after-9")

    }
  }

  "easy to use with extensions" in within(50.seconds) {
    runOn(third, fourth, fifth, sixth) {
      //#counter-start
      val counterRegion: ActorRef = ClusterSharding(system).start(
        typeName = "Counter",
        entryProps = Some(Props[Counter]),
        roleOverride = None,
        rememberEntries = false,
        idExtractor = idExtractor,
        shardResolver = shardResolver)
      //#counter-start
      ClusterSharding(system).start(
        typeName = "AnotherCounter",
        entryProps = Some(Props[Counter]),
        roleOverride = None,
        rememberEntries = false,
        idExtractor = idExtractor,
        shardResolver = shardResolver)
    }
    enterBarrier("extension-started")
    runOn(fifth) {
      //#counter-usage
      val counterRegion: ActorRef = ClusterSharding(system).shardRegion("Counter")
      counterRegion ! Get(123)
      expectMsg(0)

      counterRegion ! EntryEnvelope(123, Increment)
      counterRegion ! Get(123)
      expectMsg(1)
      //#counter-usage

      ClusterSharding(system).shardRegion("AnotherCounter") ! EntryEnvelope(123, Decrement)
      ClusterSharding(system).shardRegion("AnotherCounter") ! Get(123)
      expectMsg(-1)
    }

    enterBarrier("extension-used")

    // sixth is a frontend node, i.e. proxy only
    runOn(sixth) {
      for (n ← 1000 to 1010) {
        ClusterSharding(system).shardRegion("Counter") ! EntryEnvelope(n, Increment)
        ClusterSharding(system).shardRegion("Counter") ! Get(n)
        expectMsg(1)
        lastSender.path.address should not be (Cluster(system).selfAddress)
      }
    }

    enterBarrier("after-10")

  }
  "easy API for starting" in within(50.seconds) {
    runOn(first) {
      val counterRegionViaStart: ActorRef = ClusterSharding(system).start(
        typeName = "ApiTest",
        entryProps = Some(Props[Counter]),
        roleOverride = None,
        rememberEntries = false,
        idExtractor = idExtractor,
        shardResolver = shardResolver)

      val counterRegionViaGet: ActorRef = ClusterSharding(system).shardRegion("ApiTest")

      counterRegionViaStart should equal(counterRegionViaGet)
    }
    enterBarrier("after-11")

  }

  "Persistent Cluster Shards" must {
    "recover entries upon restart" in within(50.seconds) {
      runOn(third, fourth, fifth) {
        persistentEntriesRegion
        anotherPersistentRegion
      }
      enterBarrier("persistent-started")

      runOn(third) {
        //Create an increment counter 1
        persistentEntriesRegion ! EntryEnvelope(1, Increment)
        persistentEntriesRegion ! Get(1)
        expectMsg(1)

        //Shut down the shard and confirm it's dead
        val shard = system.actorSelection(lastSender.path.parent)
        val region = system.actorSelection(lastSender.path.parent.parent)

        //Stop the shard cleanly
        region ! HandOff("1")
        expectMsg(10 seconds, "ShardStopped not received", ShardStopped("1"))

        val probe = TestProbe()
        awaitAssert({
          shard.tell(Identify(1), probe.ref)
          probe.expectMsg(1 second, "Shard was still around", ActorIdentity(1, None))
        }, 5 seconds, 500 millis)

        //Get the path to where the shard now resides
        persistentEntriesRegion ! Get(13)
        expectMsg(0)

        //Check that counter 1 is now alive again, even though we have
        // not sent a message to it via the ShardRegion
        val counter1 = system.actorSelection(lastSender.path.parent / "1")
        counter1 ! Identify(2)
        expectMsgType[ActorIdentity](3 seconds).ref should not be (None)

        counter1 ! Get(1)
        expectMsg(1)
      }

      enterBarrier("after-shard-restart")

      runOn(fourth) {
        //Check a second region does not share the same persistent shards

        //Create a separate 13 counter
        anotherPersistentRegion ! EntryEnvelope(13, Increment)
        anotherPersistentRegion ! Get(13)
        expectMsg(1)

        //Check that no counter "1" exists in this shard
        val secondCounter1 = system.actorSelection(lastSender.path.parent / "1")
        secondCounter1 ! Identify(3)
        expectMsg(3 seconds, ActorIdentity(3, None))

      }
      enterBarrier("after-12")
    }

    "permanently stop entries which passivate" in within(15.seconds) {
      runOn(third, fourth, fifth) {
        persistentRegion
      }
      enterBarrier("cluster-started-12")

      runOn(third) {
        //Create and increment counter 1
        persistentRegion ! EntryEnvelope(1, Increment)
        persistentRegion ! Get(1)
        expectMsg(1)

        val counter1 = lastSender
        val shard = system.actorSelection(counter1.path.parent)
        val region = system.actorSelection(counter1.path.parent.parent)

        //Create and increment counter 13
        persistentRegion ! EntryEnvelope(13, Increment)
        persistentRegion ! Get(13)
        expectMsg(1)

        val counter13 = lastSender

        counter1.path.parent should ===(counter13.path.parent)

        //Send the shard the passivate message from the counter
        watch(counter1)
        shard.tell(Passivate(Stop), counter1)

        //Watch for the terminated message
        expectTerminated(counter1, 5 seconds)

        val probe1 = TestProbe()
        awaitAssert({
          //Check counter 1 is dead
          counter1.tell(Identify(1), probe1.ref)
          probe1.expectMsg(1 second, "Entry 1 was still around", ActorIdentity(1, None))
        }, 5 second, 500 millis)

        //Stop the shard cleanly
        region ! HandOff("1")
        expectMsg(10 seconds, "ShardStopped not received", ShardStopped("1"))

        val probe2 = TestProbe()
        awaitAssert({
          shard.tell(Identify(2), probe2.ref)
          probe2.expectMsg(1 second, "Shard was still around", ActorIdentity(2, None))
        }, 5 seconds, 500 millis)
      }

      enterBarrier("shard-shutdown-12")

      runOn(fourth) {
        //Force the shard back up
        persistentRegion ! Get(25)
        expectMsg(0)

        val shard = lastSender.path.parent

        //Check counter 1 is still dead
        system.actorSelection(shard / "1") ! Identify(3)
        expectMsg(ActorIdentity(3, None))

        //Check counter 13 is alive again                        8
        system.actorSelection(shard / "13") ! Identify(4)
        expectMsgType[ActorIdentity](3 seconds).ref should not be (None)
      }

      enterBarrier("after-13")
    }

    "restart entries which stop without passivating" in within(50.seconds) {
      runOn(third, fourth) {
        persistentRegion
      }
      enterBarrier("cluster-started-12")

      runOn(third) {
        //Create and increment counter 1
        persistentRegion ! EntryEnvelope(1, Increment)
        persistentRegion ! Get(1)
        expectMsg(2)

        val counter1 = system.actorSelection(lastSender.path)

        counter1 ! Stop

        val probe = TestProbe()
        awaitAssert({
          counter1.tell(Identify(1), probe.ref)
          probe.expectMsgType[ActorIdentity](1 second).ref should not be (None)
        }, 5.seconds, 500.millis)
      }

      enterBarrier("after-14")
    }

    "be migrated to new regions upon region failure" in within(15.seconds) {

      //Start only one region, and force an entry onto that region
      runOn(third) {
        autoMigrateRegion ! EntryEnvelope(1, Increment)
        autoMigrateRegion ! Get(1)
        expectMsg(1)
      }
      enterBarrier("shard1-region3")

      //Start another region and test it talks to node 3
      runOn(fourth) {
        autoMigrateRegion ! EntryEnvelope(1, Increment)

        autoMigrateRegion ! Get(1)
        expectMsg(2)
        lastSender.path should ===(node(third) / "user" / "AutoMigrateRegionTestRegion" / "1" / "1")

        //Kill region 3
        system.actorSelection(lastSender.path.parent.parent) ! PoisonPill
      }
      enterBarrier("region4-up")

      // Wait for migration to happen
      //Test the shard, thus counter was moved onto node 4 and started.
      runOn(fourth) {
        val counter1 = system.actorSelection(system / "AutoMigrateRegionTestRegion" / "1" / "1")
        val probe = TestProbe()
        awaitAssert({
          counter1.tell(Identify(1), probe.ref)
          probe.expectMsgType[ActorIdentity](1 second).ref should not be (None)
        }, 5.seconds, 500 millis)

        counter1 ! Get(1)
        expectMsg(2)
      }

      enterBarrier("after-15")
    }

    "ensure rebalance restarts shards" in within(50.seconds) {
      runOn(fourth) {
        for (i ← 2 to 12) {
          rebalancingPersistentRegion ! EntryEnvelope(i, Increment)
        }

        for (i ← 2 to 12) {
          rebalancingPersistentRegion ! Get(i)
          expectMsg(1)
        }
      }
      enterBarrier("entries-started")

      runOn(fifth) {
        rebalancingPersistentRegion
      }
      enterBarrier("fifth-joined-shard")

      runOn(fifth) {
        awaitAssert {
          var count = 0
          for (n ← 2 to 12) {
            val entry = system.actorSelection(rebalancingPersistentRegion.path / (n % 12).toString / n.toString)
            entry ! Identify(n)
            receiveOne(3 seconds) match {
              case ActorIdentity(id, Some(_)) if id == n ⇒ count = count + 1
              case ActorIdentity(id, None)               ⇒ //Not on the fifth shard
            }
          }
          count should be >= (2)
        }
      }

      enterBarrier("after-16")
    }
  }
}

