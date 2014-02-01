package pl.project13.scala.akka.raft.example.cluster

import scala.collection.mutable.ListBuffer
import pl.project13.scala.akka.raft.cluster.ClusterRaftActor
import pl.project13.scala.akka.raft.example.{GetWords, AppendWord, Cmnd}

class WordConcatClusterRaftActor extends ClusterRaftActor {

   type Command = Cmnd

   var words = ListBuffer[String]()

   /** Called when a command is determined by Raft to be safe to apply */
   def apply(command: Cmnd): Any = command match {
     case AppendWord(word) =>
       words append word
       log.info(s"Applied command [$command], full words is: $words")

       word

     case GetWords =>
       words.toList
   }
 }

