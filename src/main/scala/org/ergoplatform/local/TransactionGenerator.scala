package org.ergoplatform.local

import akka.actor.{Actor, ActorRef, ActorRefFactory, ActorSystem, Cancellable, Props}
import org.ergoplatform.local.TransactionGenerator.{FetchBoxes, StartGeneration, StopGeneration}
import org.ergoplatform.modifiers.mempool.AnyoneCanSpendTransaction
import org.ergoplatform.modifiers.mempool.proposition.AnyoneCanSpendProposition
import org.ergoplatform.nodeView.history.ErgoHistory
import org.ergoplatform.nodeView.mempool.ErgoMemPool
import org.ergoplatform.nodeView.state.UtxoState
import org.ergoplatform.nodeView.wallet.ErgoWallet
import org.ergoplatform.settings.TestingSettings
import scorex.core.LocalInterface.LocallyGeneratedTransaction
import scorex.core.NodeViewHolder.GetDataFromCurrentView
import scorex.core.utils.ScorexLogging

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Random

class TransactionGenerator(viewHolder: ActorRef, settings: TestingSettings) extends Actor with ScorexLogging {
  var txGenerator: Cancellable = _

  var isStarted = false

  @SuppressWarnings(Array("TraversableHead"))
  override def receive: Receive = {
    case StartGeneration =>
      if (!isStarted) {
        context.system.scheduler.schedule(1500.millis, 1500.millis)(self ! FetchBoxes)
      }

    case FetchBoxes =>
      viewHolder ! GetDataFromCurrentView[ErgoHistory, UtxoState, ErgoWallet, ErgoMemPool,
        Seq[AnyoneCanSpendTransaction]] { v =>
        if (v.pool.size < settings.keepPoolSize) {
          (0 until settings.keepPoolSize - v.pool.size).map { _ =>
            val txBoxes = (1 to Random.nextInt(10) + 1).flatMap(_ => v.state.randomBox())
            val txInputs = txBoxes.map(_.nonce)
            val values = txBoxes.map(_.value)
            val txOutputs = if (values.head % 2 == 0) IndexedSeq.fill(2)(values.head / 2) ++ values.tail else values
            AnyoneCanSpendTransaction(txInputs, txOutputs)
          }
        } else {
          Seq.empty
        }
      }

    case txs: Seq[AnyoneCanSpendTransaction] =>
      txs.foreach { tx =>
        viewHolder ! LocallyGeneratedTransaction[AnyoneCanSpendProposition.type, AnyoneCanSpendTransaction](tx)
      }

    case StopGeneration =>
      txGenerator.cancel()
  }
}

object TransactionGenerator {
  def props(viewHolder: ActorRef, settings: TestingSettings): Props =
    Props(new TransactionGenerator(viewHolder, settings))

  def apply(viewHolder: ActorRef, settings: TestingSettings)
           (implicit context: ActorRefFactory): ActorRef =
    context.actorOf(props(viewHolder, settings))

  def apply(viewHolder: ActorRef, settings: TestingSettings, name: String)
           (implicit context: ActorRefFactory): ActorRef =
    context.actorOf(props(viewHolder, settings), name)

  case object StartGeneration

  case object FetchBoxes

  case object StopGeneration
}
