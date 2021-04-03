# h2 - H2 Playground Impl [![Build Status](https://travis-ci.com/ChristopherDavenport/h2.svg?branch=master)](https://travis-ci.com/ChristopherDavenport/h2) [![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.http4s/h2_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.http4s/h2_2.12) ![Code of Consuct](https://img.shields.io/badge/Code%20of%20Conduct-Scala-blue.svg)

## [Head on over to the microsite](https://ChristopherDavenport.github.io/h2)

## Quick Start

To use h2 in an existing SBT project with Scala 2.11 or a later version, add the following dependencies to your
`build.sbt` depending on your needs:

```scala
libraryDependencies ++= Seq(
  "org.http4s" %% "h2" % "<version>"
)
```


### Hpack Compression

The headers go through an algorithm to compress the data present, as well as getting
rid of the http paradigm of a prelude. In http2 prelude values are encoded as pseudo headers.

We presently leverage twitter.hpack to do this step, but eventually I'd like to have control as 
well rather than wrapping a mutable value.

### Binary Framing

The Frame identifies an encoding context that allows us to communicate. This is how we pass data back and forth.

### H2

Http2 operates via a single persistent connection.
We outline 2 concurrent processes outlined with creation of a connection.


#### Transport Plane

We have a readLoop which takes information that is written to us from the remote endpoint.
This is where the bulk of the logic resides as we determine how to respond to invalid communication.
Second, we have a writeLoop who takes generated data and writes it to the client and backpressures on connection
level flow control.

At the lower level we have the n Streams that are processing simultaneously. These are primarily state machines
that receive data and store it and allow the control plane actors to consume and write with appropriate
connection level backpressure.

#### Control Plane

The control plane is the concurrent, non-linear interactions with the underlying transport plane and state machines.
In the case of the client this is the `run` method, by which we interact with the connections we have, and first write our incoming write, and after the headers have been committed, then we submit the body simultaneously with waiting on headers to be returned. Then we attach the response body. The connection and stream level
flow control is responsible for backpressure, as data that follows the settings will be buffered per the spec.

In the server, the control plane is a queue of the created streams for a connection, which executes the 
provided httpApp against the processed requests to generate responses it writes back.