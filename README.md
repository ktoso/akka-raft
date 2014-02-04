akka-raft
=========

<a href="https://travis-ci.org/ktoso/akka-raft"><img src="https://travis-ci.org/ktoso/akka-raft.png"/></a>

This is an akka based implementation of the Raft consensus algorithm.

It is akka-cluster (which is _experimental_) aware, and supports the additional features of raft such as: mambership changes and (_not yet_) snapshotting.

**This is still work in progress and has not been stress tested (athough it is tested on multiple nodes already)**

Basic info
===========

Raft is a distributed consensus algorithm, much like Paxos (but simpler).
This implementation is fully akka (and akka-cluster) based, and can be used to deploy a replicated state machine on top of akka clusters.

Usage looks (_APIs still subject to change_):

**THIS API IS STILL SUBJECT TO CHANGE**

```scala
class WordConcatRaftActor extends RaftActor {

  type Command = Cmnd

  var words = ListBuffer[String]()

  /** Called when a command is determined by Raft to be safe to apply */
  def apply(command: Cmnd): Any = command match {
    case AppendWord(word) =>
      words append word
      log.info(s"Applied command [$command], full words is: $words")

      word

    case GetWords =>
      log.info("Replying with {}", words.toList)
      words.toList
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

