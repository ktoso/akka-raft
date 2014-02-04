akka-raft
=========

This is an akka based implementation of the Raft consensus algorithm.

<a href="https://travis-ci.org/ktoso/akka-raft"><img src="https://travis-ci.org/ktoso/akka-raft.png"/></a>

Impl of https://ramcloud.stanford.edu/wiki/download/attachments/11370504/raft.pdf

**This is work in progress ;-)**

Basic info
===========

Leader election and log replication works. Including "when nodes die".
Cluster (backed by akka-cluster _experimental_) awareness is in progress.

Other todos were moved to issues, the goal is to provide an akka-cluster aware example of "a zookeeper-like kv-store".

License
=======

Apache 2.0

Links
=====

* [Raft - In Search of an Understandable Consensus Algorithm](https://ramcloud.stanford.edu/wiki/download/attachments/11370504/raft.pdf) whitepaper
* See other implementations (many lanugages) on [raftconsensus.github.io](http://raftconsensus.github.io)

[!Bitdeli Badge](https://d2weczhvl823v0.cloudfront.net/ktoso/akka-raft/trend.png)](https://bitdeli.com/free "Bitdeli Badge")

