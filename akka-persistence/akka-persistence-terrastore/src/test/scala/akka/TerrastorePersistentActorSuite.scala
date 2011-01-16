package akka.persistence.terrastore

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import akka.actor.Actor
import Actor._
import BankAccountActor._
import akka.stm._


case class Balance(accountNo: String)

case class Debit(accountNo: String, amount: Int)

case class MultiDebit(accountNo: String, amounts: List[Int])

case class Credit(accountNo: String, amount: Int)

case class Log(start: Int, finish: Int)

case object LogSize

object BankAccountActor {
  val state = "accountState"
  val tx = "txnLog"
}

class BankAccountActor extends Actor {
  private val accountState = TerrastoreStorage.newMap(state)
  private val txnLog = TerrastoreStorage.newVector(tx)

  import sjson.json.DefaultProtocol._
  import sjson.json.JsonSerialization._

  def receive = {
    case message => atomic {
      atomicReceive(message)
    }
  }

  def atomicReceive: Receive = {
    // check balance
    case Balance(accountNo) =>
      txnLog.add(("Balance:" + accountNo).getBytes)
      self.reply(
        accountState.get(accountNo.getBytes)
          .map(frombinary[Int](_))
          .getOrElse(0))

    // debit amount: can fail
    case Debit(accountNo, amount) =>
      txnLog.add(("Debit:" + accountNo + " " + amount).getBytes)
      val m = accountState.get(accountNo.getBytes)
        .map(frombinary[Int](_))
        .getOrElse(0)

      accountState.put(accountNo.getBytes, tobinary(m - amount))
      if (amount > m) fail

      self.reply(m - amount)

    // many debits: can fail
    // demonstrates true rollback even if multiple puts have been done
    case MultiDebit(accountNo, amounts) =>
      val sum = amounts.foldRight(0)(_ + _)
      txnLog.add(("MultiDebit:" + accountNo + " " + sum).getBytes)

      val m = accountState.get(accountNo.getBytes)
        .map(frombinary[Int](_))
        .getOrElse(0)

      var cbal = m
      amounts.foreach {
        amount =>
          accountState.put(accountNo.getBytes, tobinary(m - amount))
          cbal = cbal - amount
          if (cbal < 0) fail
      }

      self.reply(m - sum)

    // credit amount
    case Credit(accountNo, amount) =>
      txnLog.add(("Credit:" + accountNo + " " + amount).getBytes)
      val m = accountState.get(accountNo.getBytes)
        .map(frombinary[Int](_))
        .getOrElse(0)

      accountState.put(accountNo.getBytes, tobinary(m + amount))

      self.reply(m + amount)

    case LogSize =>
      self.reply(txnLog.length)

    case Log(start, finish) =>
      self.reply(txnLog.slice(start, finish).map(new String(_)))
  }

  def fail = throw new RuntimeException("Expected exception; to test fault-tolerance")
}

@RunWith(classOf[JUnitRunner])
class TerrastorePersistentActorSuite extends
Spec with
ShouldMatchers with
BeforeAndAfterEach with EmbeddedTerrastore {

  import TerrastoreStorageBackend._


  override def beforeEach {
    vectorAccess.drop
    mapAccess.drop
  }

  override def afterEach {
    beforeEach
  }

  describe("successful debit") {
    it("should debit successfully") {
      log.info("Succesful Debit starting")
      val bactor = actorOf[BankAccountActor]
      bactor.start
      bactor !! Credit("a-123", 5000)
      log.info("credited")
      bactor !! Debit("a-123", 3000)
      log.info("debited")
      (bactor !! Balance("a-123")).get.asInstanceOf[Int] should equal(2000)
      log.info("balane matched")
      bactor !! Credit("a-123", 7000)
      log.info("Credited")
      (bactor !! Balance("a-123")).get.asInstanceOf[Int] should equal(9000)
      log.info("Balance matched")
      bactor !! Debit("a-123", 8000)
      log.info("Debited")
      (bactor !! Balance("a-123")).get.asInstanceOf[Int] should equal(1000)
      log.info("Balance matched")
      (bactor !! LogSize).get.asInstanceOf[Int] should equal(7)
      (bactor !! Log(0, 7)).get.asInstanceOf[Iterable[String]].size should equal(7)
    }
  }

  describe("unsuccessful debit") {
    it("debit should fail") {
      val bactor = actorOf[BankAccountActor]
      bactor.start
      bactor !! Credit("a-123", 5000)
      (bactor !! Balance("a-123")).get.asInstanceOf[Int] should equal(5000)
      evaluating {
        bactor !! Debit("a-123", 7000)
      } should produce[Exception]
      (bactor !! Balance("a-123")).get.asInstanceOf[Int] should equal(5000)
      (bactor !! LogSize).get.asInstanceOf[Int] should equal(3)
    }
  }

  describe("unsuccessful multidebit") {
    it("multidebit should fail") {
      val bactor = actorOf[BankAccountActor]
      bactor.start
      bactor !! Credit("a-123", 5000)
      (bactor !! Balance("a-123")).get.asInstanceOf[Int] should equal(5000)
      evaluating {
        bactor !! MultiDebit("a-123", List(1000, 2000, 4000))
      } should produce[Exception]
      (bactor !! Balance("a-123")).get.asInstanceOf[Int] should equal(5000)
      (bactor !! LogSize).get.asInstanceOf[Int] should equal(3)
    }
  }
}