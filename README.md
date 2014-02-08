akka-raft
=========

<a href="https://travis-ci.org/ktoso/akka-raft"><img src="https://travis-ci.org/ktoso/akka-raft.png"/></a>

This is an akka based implementation of the Raft consensus algorithm.

It is **akka-cluster** aware, and supports the additional features of raft such as: mambership changes and (_not yet_) snapshotting.

<span style="color:red; font-weight:bold">This is still work in progress and has not been stress tested (athough it is tested on multiple nodes already)</span>

Basic info
===========

Raft is a distributed consensus algorithm, much like Paxos (but simpler).
This implementation is fully akka (and akka-cluster) based, and can be used to deploy a replicated state machine on top of akka clusters.

**THIS API IS STILL SUBJECT TO CHANGE**

```scala
class WordConcatRaftActor extends RaftActor {

  type Command = Cmnd

  var words = Vector[String]()

  /** 
   * Called when a command is determined by Raft to be safe to apply; 
   * Application results are sent back to the client issuing the command.
   */
  def apply = { 
    case AppendWord(word) =>
      words +: word
      log.info("Applied command [{}], full words is: {}", command, words)

      word

    case GetWords =>
      val res = words.toList
      log.info("Replying with {}", res)
      res
  }
}

// ...

val members = (1 to 3) map { i => system.actorOf(Props[WordConcatRaftActor], name = s"member-$i") }
val clusterConfiguration = ClusterConfiguration(raftConfiguration.members + additionalActor) // 0, 1

members foreach { _ ! ChangeConfiguration(clusterConfiguration)

// todo implement re-routing if you send to a non-leader
// then send messages to it; the state machine will only be applied when consensus has been reached about a value
leader ! ClientRequest(AppendWord("I"))
leader ! ClientRequest(AppendWord("like"))
leader ! ClientRequest(AppendWord("capybaras"))

// ... after some time
leader ! GetWords

expectMsg(List("I", "like", "capybaras"))

```

License
=======

*Apache 2.0*

Links
=====

* [Raft - In Search of an Understandable Consensus Algorithm](https://ramcloud.stanford.edu/wiki/download/attachments/11370504/raft.pdf) whitepaper
* See other implementations (many lanugages) on [raftconsensus.github.io](http://raftconsensus.github.io)
* Docs on [akka-cluster](http://doc.akka.io/docs/akka/2.3.0-RC2/scala/cluster-usage.html) in *2.3.0-RC2*.

[!Bitdeli Badge](https://d2weczhvl823v0.cloudfront.net/ktoso/akka-raft/trend.png)](https://bitdeli.com/free "Bitdeli Badge")

