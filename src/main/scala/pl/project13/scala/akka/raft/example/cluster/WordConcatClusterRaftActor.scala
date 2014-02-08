package pl.project13.scala.akka.raft.example.cluster

import pl.project13.scala.akka.raft.example.protocol._

import scala.collection.mutable.ListBuffer
import pl.project13.scala.akka.raft.cluster.ClusterRaftActor

class WordConcatClusterRaftActor extends ClusterRaftActor {

   type Command = Cmnd

   var words = Vector[String]()

   /** Called when a command is determined by Raft to be safe to apply */
   def apply = {
     case AppendWord(word) =>
       words = words :+ word
       log.info("Applied command [AppendWord({})], full words is: {}", word, words)

       word

     case GetWords =>
       words.toList
   }
 }

