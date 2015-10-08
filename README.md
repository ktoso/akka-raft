akka-raft
=========

<a href="https://travis-ci.org/ktoso/akka-raft"><img src="https://travis-ci.org/ktoso/akka-raft.svg"/></a>

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

:boom: :boom:

**This project is a side-project of mine and is still work in progress (treat it as EARLY PREVIEW) and has a number of known protocol bugs (see [Issues](https://github.com/ktoso/akka-raft/issues)). It is NOT recommended to be used in production, however it's a great project to play around with implementing and discussing the Raft protocol.** 

:boom: :boom:

In other words: Use at own risk, best not on any production-like environments (for now).

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

      word // will be sent back to original actor, who sent the AppendWord command

    case GetWords =>
      val res = words.toList
      log.info("Replying with {}", res)
      res
  }
}

// ...

val members = (1 to 3) map { i => system.actorOf(Props[WordConcatRaftActor], name = s"raft-member-$i") }
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

RaftClientActor
---------------

In the above examples, the **client** implementation is very naive, and assumes you have some way
of finding out who the current Leader is (as this is a requirement to interact with any Raft cluster).
Thankfully, you can use the provided `RaftClientActor`, which works like a proxy that forwards all your messages
to the current _Leader_, or stashes them if the cluster has no _Leader_ at the moment (is undergoing an election) and
sends the messages once the Leader becomes available.




License
-------

Simply: *Apache 2.0*

Issues, Pull Requests as well as Tweets and Emails are more than welcome!

Links & kudos
-------------

* An excellent analysis using *fuzz testing of akka-raft, discovering a number of protocol bugs by @colin-scott*, blog post [Fuzzing Raft for Fun and Publication](https://colin-scott.github.io/blog/2015/10/07/fuzzing-raft-for-fun-and-profit/) and linked inside it the whitepaper draft.


* [Raft - In Search of an Understandable Consensus Algorithm](https://ramcloud.stanford.edu/wiki/download/attachments/11370504/raft.pdf) whitepaper
* See other implementations (many lanugages) on [raftconsensus.github.io](http://raftconsensus.github.io)
* Docs on [akka-cluster](http://doc.akka.io/docs/akka/2.3.0-RC2/scala/cluster-usage.html) in *2.3.0-RC2*.

We have discussed this paper both in Kraków and London, on these _awesome_ reading clubs (drop by if you're into CS papers!):

* [Software Craftsmanship Kraków - SCKRK.com](http://www.meetup.com/sc-krk/events/134500362/)
* [Paper Cup - the reading club - London](http://www.meetup.com/Paper-Cup/events/153170202/)

[![Bitdeli Badge](https://d2weczhvl823v0.cloudfront.net/ktoso/akka-raft/trend.png)](https://bitdeli.com/free "Bitdeli Badge")

