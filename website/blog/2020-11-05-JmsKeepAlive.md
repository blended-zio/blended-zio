---
slug: zio-jms-keepalive
title: Keep alive for JMS connections
tags: [ZIO, Streams, JMS]
author: Andreas Gies
author_url: https://github.com/atooni
---

This article concludes the mini series exploring the ZIO Stream API and shows how a simple keepalive mechanism can be added to a JMS based stream. This will check the health of the underlying JMS connection and issue a connection restart if required. 

<!-- truncate -->

# Why do we need a keep alive

In our existing application we have noticed that in some circumstances a connection that still exists and is reported as `connected` within the monitoring tools does not work as expected. In the case of JMS this usually happens when a particular connection is only used to receive messages. The connection may appear as `connected` and also have associated JMS sessions and consumers, but still will not receive any messages from the JMS broker.

Many JMS providers have implemented a keep alive mechanism, but in practice those have been proven to be somewhat difficult:

* They do not work in some edge cases such as restarting network switches on WAN connections.
* They are not part of the JMS specification and therefore will be different from vendor to vendor and are definitely not mandatory.
* They usually require some vendor specific code to be configured (if not configured via properties over JNDI).

A connection which is also used to produce messages normally does require a keep alive monitor because it would encounter a `JMSException` when sends are attempted over a stale connection and therefore the connection could be recovered during normal error handling.

In this article I will pick up from the [last article](2020-10-30-RecoveringStreams.md), where I investigated how we could leverage the ZIO Api to automatically recover from an exception in the JMS layer without terminating the ZIO stream with an exception.

We will show a simple keep alive monitor, which doesn't know anything about JMS or streams at all. Then we will create an instance of that monitor watching a created JMS connection issuing a reconnect once the maximum number of missed keep alive events is reached.

:::info
The complete source code used in this article can be found on [github](https://github.com/blended-zio/blended-zio/tree/main/blended.zio.streams)
:::

## References to related posts

* [Basic streams with JMS](2020-10-27-ZIOJms.md)
* [Auto Recovery for ZIO streams](2020-10-30-RecoveringStreams.md)

## A simple Keep Alive monitor

A keep alive monitor is more or less a counter that is increased at certain intervals. It can be reset by sending it `alive` signals. Whenever
the counter reaches a defined value `n` that practically means that for `n * interval` the monitor hasn't received a signal. The monitor as such does
not need to know about the entity it monitors, in our case the JMS connection.

CODE_INCLUDE lang="scala" file="../blended.zio.streams/src/main/scala/blended/zio/streams/KeepAliveMonitor.scala" doctag="trait"

The implementation for this interface is fairly straight forward. Within the `run` method we execute a step effect, which simply increases the internal
counter. If the counter has reached the given maximum, the step function terminates, otherwise the next step is scheduled after the given interval.

Overall, `run` will terminate once the given max count has been reached. Therefore it is always a good idea fo users of the monitor to execute `monitor.run` in it's own fiber.

CODE_INCLUDE lang="scala" file="../blended.zio.streams/src/main/scala/blended/zio/streams/KeepAliveMonitor.scala" doctag="run"

## Using the Keep alive monitor with JMS connections

With the JMS based streams explained [here](2020-10-27-ZIOJms.md) and the general keep alive monitor we can now build a monitor that determines whether a given JMS connection is healthy. We do that by using the connection to be monitored and regularly send and receive messages. Whenever a message is received, we execute `alive` on the underlying monitor - effectively resetting the counter.

### Definining a Keep alive Sink

From the API perspective we want to create a stream that regularly creates messages and run that with a JMS sink - effectively sending the messages to the JMS broker.


:::caution
Keep in mind that the library has prototyping character for now, so some elements like the ping message format are hard coded for the time being and need to be fleshed out later on.
:::

### Defining a Keep alive Stream

Now we need to define a consumer - in other words a ZIO stream - and for each message received we want to execute `alive` on a given `KeepAliveMonitor`.


### Create the JMS Keep Alive Monitor

The JMS keep alive monitor will be created once a connection configured with monitoring is established. We also need a destination that shall be used for sending and receiving the keep alive messages, an interval and the maximum allowed missed keep alives.

With those parameters the JMS keep alive monitor is straight forward:

1. Create an an instance of a general `KeepAliveMonitor`
1. Start the sink for keep alives
1. Start the stream to receive keep alives
1. Fork the run method of the just created monitor
1. Once run terminates, interrupt the stream and sink
1. Terminate with the current count of the underlying monitor


## Instrumenting a JMS connection with a keep alive monitor

The API has a case class exposed for a JMS connection:

CODE_INCLUDE lang="scala" file="../blended.zio.streams/src/main/scala/blended/zio/streams/jms/jmsobjects.scala" doctag="connection"

In here we see that the case class also contains an effect which will be executed every time a physical connection to the JMS broker has been established. This effect takes the `JMSConnection` as a parameter. We can now use `onConnect` to set up the keep alive monitor.


Let's break this down a bit:

* First we run an instance of the `JMSKeepAliveMonitor` which either terminates when the maximum number of missed keep alives has been reached or the application terminates. In the first case we need to issue a reconnect on the JMS connection using an underlying connection manager.

* Then we wrap running the monitor in it's own fiber and wait for it to terminate.

* The final step is perhaps a bit confusing, the case class defines `onConnect` to require an environment `[ZEnv with Logging]`, but using `reconnect` also requires a `ZIOJMSConnectionManager` to be present in the environment. Therefore we pass a `SingletonService[ZIOJmsConnectionManager.Service]` as a parameter to the `keepAlive` method.

   We can the use `provideSomeLayer` to provide the connection manager to the `JMSKeepAliveMonitor` and we are left with an effect that requires only `[ZEnv with Logging]` which is what we need to fulfill the API.

With the keepAlive method in place we can now create the connection factory. Again, we see an instance of the connection manager service passed through.


## The program to be run

First of all we need to create an instance of a `ZIOJmsConnectionManager.Service`. This instance is then passed to the actual logic, so that the keep alive monitor can use it to inject it into the environment of `JMSKeepAliveMonitor.run`. The instance is also required by the logic effect itself, otherwise the connections could not be established to create the streams and sinks.


## Conclusion

We have added a simple keep alive mechanism to the JMS connections we have discussed in the previous articles. The keep alive build on the streams with
auto recovery and triggers a reconnect once a limit of maximum missed keep alives has been reached. The reconnect then triggers the recovery and reconnect as defined for auto recovery.

For the users of the stream the keep alive and potential reconnect is completely transparent. Further, the `onConnect` effect would allow us to instrument the connection factory with other effects - for example to collect metrics or publish JMX information.

## Next steps

Apart from finalizing the API there are more areas to explore:

* Use defined types rather than only String as message payloads.
* Support arbitrary message properties.
* Look into defining message flows on top of streams with error handling and acknowledgements.
* Explore [zio-zmx](https://github.com/zio/zio-zmx) to visualize the current state of all fibers within a running application (for learning about threads primarily)
* Build a sample application that represents a JMS bridge
  * Consume message from provider A
  * Send the message to provider B
  * Acknowledge the message if and only if the send was successful, otherwise pass the message to a retry handler
  * Replay messages from the retry handler up to a given retry count until the send was successful. If the maximum retries have been reached or a given amount of time has passed before the send was successful, the message shall be processed by an error handler.
  * Messages in the retry handler shall be persisted and survive an application restart.

