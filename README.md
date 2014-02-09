akka-raft
=========

<a href="https://travis-ci.org/ktoso/akka-raft"><img src="https://travis-ci.org/ktoso/akka-raft.png"/></a> *Argh, fleaky tests! WIP...*

This is an akka based implementation of the **Raft consensus algorithm**.
It is generic enough that you can build your own replicated state machines on top of it (with raft keeping track of the consensus part of it).

This implementation is **akka-cluster aware**, so it can be easily deployed on multiple machines.
Implementation wise, all parts of the raft whitepaper are covered:

* Leader election
* Dynamic membership changes (with transitioning periods implemented as Joint Consensus)
* Deployment across multiple nodes
* Log compaction, via snapshotting

Disclaimer
----------

<span style="color:red; font-weight:bold">This project is still work in progress and has not been stress tested (athough it is tested on multiple nodes already)</span>

Basic info
----------

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
And if you want to enable snapshotting support it's as simple as implementing one method and matching for `InstallSnapshot`
in your Actor:

```scala
class SnapshottingWordConcatRaftActor extends RaftActor {

  type Command = Cmnd

  var words = Vector[String]()

  def apply = {
    case AppendWord(word) =>
      words +: word
      word

    case GetWords =>
      val res = words.toList
      log.info("Replying with {}", res)
      res

    case InstallSnapshot(snapshot) =>
      words = snapshot.data.asInstanceOf[Vector[String]]
  }

  override def prepareSnapshot(meta: RaftSnapshotMetadata) =
    Future.successful(Some(RaftSnapshot(meta, words)))
}
```


License
-------

Simply: *Apache 2.0*

Issues, Pull Requests as well as Tweets and Emails are more than welcome!

Links & kudos
-------------

* [Raft - In Search of an Understandable Consensus Algorithm](https://ramcloud.stanford.edu/wiki/download/attachments/11370504/raft.pdf) whitepaper
* See other implementations (many lanugages) on [raftconsensus.github.io](http://raftconsensus.github.io)
* Docs on [akka-cluster](http://doc.akka.io/docs/akka/2.3.0-RC2/scala/cluster-usage.html) in *2.3.0-RC2*.

We have discussed this paper both in Kraków and London, on these _awesome_ reading clubs (drop by if you're into CS papers!):

* [Software Craftsmanship Kraków - SCKRK.com](http://www.meetup.com/sc-krk/events/134500362/)
* [Paper Cup - the reading club - London](http://www.meetup.com/Paper-Cup/events/153170202/)

[![Bitdeli Badge](https://d2weczhvl823v0.cloudfront.net/ktoso/akka-raft/trend.png)](https://bitdeli.com/free "Bitdeli Badge")

