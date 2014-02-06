package pl.project13.scala.akka.raft.example.cluster

import pl.project13.scala.akka.raft.example.protocol._

import scala.collection.mutable.ListBuffer
import pl.project13.scala.akka.raft.cluster.ClusterRaftActor

class WordConcatClusterRaftActor extends ClusterRaftActor {

   type Command = Cmnd

   var words = ListBuffer[String]()

   /** Called when a command is determined by Raft to be safe to apply */
   def apply = {
     case AppendWord(word) =>
       words append word
       log.info(s"Applied command [AppendWord($word)], full words is: $words")

       word

     case GetWords =>
       words.toList
   }
 }

