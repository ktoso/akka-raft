akka-raft
=========

<a href="https://travis-ci.org/ktoso/akka-raft"><img src="https://travis-ci.org/ktoso/akka-raft.png"/></a>

Impl of https://ramcloud.stanford.edu/wiki/download/attachments/11370504/raft.pdf

**This is work in progress ;-)**

Basic info
===========

Leader election and log replication works. Including "when nodes die".
Cluster (backed by akka-cluster) awareness is in progress.

Other todos were moved to issues, the goal is to provide an akka-cluster aware example of "a zookeeper-like kv-store".

License
=======

Apache 2.0
