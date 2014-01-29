akka-raft
=========

<a href="https://travis-ci.org/ktoso/akka-raft"><img src="https://travis-ci.org/ktoso/akka-raft.png"/></a>

Impl of https://ramcloud.stanford.edu/wiki/download/attachments/11370504/raft.pdf

**This is work in progress ;-)**

Todo
====

- [x] election impl
- [x] log replication, committing
- [x] handle commit on non-leader nodes
- [ ] Candidate -> Follower transition when got msg from Leader
- [ ] more torture tests
- [ ] verify flow when catching up from "very behind"
- [ ] log compactation (snapshots) - expose user api for this?
- [ ] full key-store example docs
- [ ] make `akka-cluster` aware (react on nodes joining/leaving the cluster)

License
=======

Apache 2.0
