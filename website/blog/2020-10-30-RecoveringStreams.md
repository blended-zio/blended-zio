---
slug: zio-streams-autorecover
title: Autorecovery for (JMS) Streams
tags: [ZIO, Streams, JMS]
author: Andreas Gies
author_url: https://github.com/atooni
---

Today we are going to explore the ZIO streams API and see how we can create a stream that will will enter a recovery phase in certain error scenarios. We will explore how we can deal with exceptions on the connection level while keeping the stream processing code untouched.

<!-- truncate -->

# Automatically recover (JMS) streams

In my last [article](2020-10-27-ZIOJms.md) I have shown how the ZIO stream API allows us to easily create streams for sending or receiving messages via JMS. Within the sample program we have seen that the streams terminate with an exception whenever the underlying JMS API raises encounters an error.

One of the most common errors is that the connection is lost due to a network error. For long running applications we would like to initiate an automatic reconnect and either create a new stream or recover the existing stream. The advantage of recovering the existing stream is that we do not have to rewire the users of the streams. Any effect using the existing stream will be suspended until the reconnect has happened and then continue.

In this article I will explore how we ca use the ZIO API to achieve such a transparent reconnect.

:::info
The complete source code used in this article can be found on [github](https://github.com/blended-zio/blended-zio/tree/main/blended.zio.streams)
:::

## What we want to achieve

Like in the last article, let's start by looking at a sample program we would like to run:

CODE_INCLUDE lang="scala" file="../blended.zio.streams/src/test/scala/blended/zio/streams/RecoveringJmsApp.scala" doctag="program"

There are 2 important differences in comparison to the sample application of the last article:

1. We are issuing a reconnect to the underlying connection factory. This implies that there is some mechanism within the connection factory that controls an automated reconnect.
1. Rather than creating the JMS stream / sink directly we use an effect that yields a __factory__ linked to the connection factory which can create a stream or sink for a given destination.

When we run this program with the input stream below, we will notice that the output pauses for a couple of seconds when the reconnect is triggered and then continues sending and receiving messages.

CODE_INCLUDE lang="scala" file="../blended.zio.streams/src/test/scala/blended/zio/streams/RecoveringJmsApp.scala" doctag="stream"

Here is an excerpt from the console output:

```
2020-10-30-07.13.52.817
2020-10-30-07.13.54.631
2020-10-30-07.13.54.985  <----- NOTE THE GAP
2020-10-30-07.13.58.952  <----- NOTE THE GAP
2020-10-30-07.13.59.095
2020-10-30-07.13.59.955
```

## A reconnecting wrapper around the JMS Connection factory

Under the covers we use a connection manager which manages named JMS connections and at the moment it guarantees that for a given id only a single physical JMS connections will be established. Under the covers the connection manager delegates all JMS API calls to the methods of `ConnectionFactory` within the JMS API. 

To guarantee that only a single connection can be established, we wrap the actual connect with a Semaphore and return the connection if it already exists, otherwise we create a new connection and store it.

CODE_INCLUDE lang="scala" file="../blended.zio.streams/src/main/scala/blended/zio/streams/jms/ZIOJmsConnectionManager.scala" doctag="connect"

To recover a connection, we perform a JMS close on the existing connect and enter a recovery period. Within that period any execution of the `connect` effect will result in a `JMSException`.

CODE_INCLUDE lang="scala" file="../blended.zio.streams/src/main/scala/blended/zio/streams/jms/ZIOJmsConnectionManager.scala" doctag="recover"

Finally, the `reconnect`effect simply triggers the recover if an underlying connection currently exists.

CODE_INCLUDE lang="scala" file="../blended.zio.streams/src/main/scala/blended/zio/streams/jms/ZIOJmsConnectionManager.scala" doctag="reconnect"

## Creating a recoverable Stream (consume messages)

The idea behind the recovering stream is that we connect to the JMS broker with the given connection factory and then start consuming messages until we hit an exception. Whenever we hit an exception, we catch it and enter a recovery phase. After the recovery phase we will try to reconnect and continue to consume messages.

CODE_INCLUDE lang="scala" file="../blended.zio.streams/src/main/scala/blended/zio/streams/jms/RecoveringJmsStream.scala" doctag="stream"

The idea manifests in `consumeUntilException` and `consumeForEver`. `consumeUntilException` uses the stream we have seen in the last [article](2020-10-27-ZIOJms.md#receiving-messages). It will stick all messages that have been received into a one element buffer which we can use later on to create the final stream visible to the outside world.

`consumeForever` simply creates an effect which will create the JMS connection and then delegate to `consumeUntilException`. The we apply the `catchAll` operator to that effect where we schedule the next iteration to `consumeForEver` after a recovery period.

The final stream is then created from repeating the `take` operation of the buffer while `consumerForEver` is executing in it's own fiber.

### Why a one element buffer ?

One might wonder why I am using a one element buffer. We are operating on JMS and want to make sure that no messages are being lost. As a result, in a real application we have to acknowledge the message to the message broker once we are done with processing it. In case we encounter an exception while processing the message we have several options:

1. We drop the message byt acknowledging even if we could not process it correctly
1. We forward the message to another destination such as an error destination or a retry destination and acknowledge it
1. We deny the message

The last option here is not really part of the JMS API which only has an acknowledge method on the JMS message. What we would do in a real application is use a session with `CLIENT_ACKNOWLEDGE` and to deny the message we would close the receiving session. This would automatically mark the message as undelivered in the JMS broker - effectively denying the message. As this would apply to all messages that have been received within the same session and that have not been acknowledged yet, we consume the messages one by one.

We will explore the error handling further in another post.

## Creating a recoverable Sink (send messages)

The idea behind the recovering sink is pretty much the same as for the recovering stream. The subtle difference is that we do not use the sink we have seen in the last [article](2020-10-27-ZIOJms.md), but a method to send a single message.

Apart from that, the pattern to create the recoverable sink is the same as for creating the stream.

CODE_INCLUDE lang="scala" file="../blended.zio.streams/src/main/scala/blended/zio/streams/jms/RecoveringJmsSink.scala" doctag="sink"

## Sample log

Below is a log excerpt of the sample app execution. Note the disconnect starting time `10770`, the recovery period of the connection and the recurring recovery attempts of the consumer and producer stream until the reconnect has finished and everything can resume from `16550` onwards.

```
--- Entries omitted
2020-10-30-08:11.49.397 |     9458 | DEBUG : Received [Some(ActiveMQTextMessage {commandId = 37, responseRequired = true, messageId = ID:ToonBox-46199-1604041900408-4:1:1:1:16, originalDestination = null, originalTransactionId = null, producerId = ID:ToonBox-46199-1604041900408-4:1:1:1, destination = queue://sample, transactionId = null, expiration = 0, timestamp = 1604041909396, arrival = 0, brokerInTime = 1604041909396, brokerOutTime = 1604041909397, correlationId = null, replyTo = null, persistent = true, type = null, priority = 4, groupID = null, groupSequence = 0, targetConsumerId = null, compressed = false, userID = null, content = null, marshalledProperties = null, dataStructure = null, redeliveryCounter = 0, size = 1070, properties = null, readOnlyProperties = true, readOnlyBody = true, droppable = false, jmsXGroupFirstForConsumer = false, text = 2020-10-30-08.11.49.396})] with [JmsConsumer((amq:amq)(sampleCon)-(S-1604041900731-1)-(C-1)-sample)]
2020-10-30-08:11.50.005 |    10066 | DEBUG : Message [2020-10-30-08.11.50.004] sent successfully with [JmsProducer((amq:amq)(sampleCon)-(S-1604041900731-2)-P-1)] to [sample]
2020-10-30-08:11.50.005 |    10066 | DEBUG : Received [Some(ActiveMQTextMessage {commandId = 39, responseRequired = true, messageId = ID:ToonBox-46199-1604041900408-4:1:1:1:17, originalDestination = null, originalTransactionId = null, producerId = ID:ToonBox-46199-1604041900408-4:1:1:1, destination = queue://sample, transactionId = null, expiration = 0, timestamp = 1604041910004, arrival = 0, brokerInTime = 1604041910004, brokerOutTime = 1604041910005, correlationId = null, replyTo = null, persistent = true, type = null, priority = 4, groupID = null, groupSequence = 0, targetConsumerId = null, compressed = false, userID = null, content = null, marshalledProperties = null, dataStructure = null, redeliveryCounter = 0, size = 1070, properties = null, readOnlyProperties = true, readOnlyBody = true, droppable = false, jmsXGroupFirstForConsumer = false, text = 2020-10-30-08.11.50.004})] with [JmsConsumer((amq:amq)(sampleCon)-(S-1604041900731-1)-(C-1)-sample)]
2020-10-30-08:11.50.684 |    10745 | DEBUG : Message [2020-10-30-08.11.50.683] sent successfully with [JmsProducer((amq:amq)(sampleCon)-(S-1604041900731-2)-P-1)] to [sample]
2020-10-30-08:11.50.684 |    10745 | DEBUG : Received [Some(ActiveMQTextMessage {commandId = 41, responseRequired = true, messageId = ID:ToonBox-46199-1604041900408-4:1:1:1:18, originalDestination = null, originalTransactionId = null, producerId = ID:ToonBox-46199-1604041900408-4:1:1:1, destination = queue://sample, transactionId = null, expiration = 0, timestamp = 1604041910683, arrival = 0, brokerInTime = 1604041910683, brokerOutTime = 1604041910684, correlationId = null, replyTo = null, persistent = true, type = null, priority = 4, groupID = null, groupSequence = 0, targetConsumerId = null, compressed = false, userID = null, content = null, marshalledProperties = null, dataStructure = null, redeliveryCounter = 0, size = 1070, properties = null, readOnlyProperties = true, readOnlyBody = true, droppable = false, jmsXGroupFirstForConsumer = false, text = 2020-10-30-08.11.50.683})] with [JmsConsumer((amq:amq)(sampleCon)-(S-1604041900731-1)-(C-1)-sample)]
2020-10-30-08:11.50.709 |    10770 | INFO  : Connector vm://simple stopped
2020-10-30-08:11.50.710 |    10771 | DEBUG : Closed [((amq:amq)(sampleCon))]
2020-10-30-08:11.50.717 |    10778 | DEBUG : Beginning recovery period for [(amq:amq)] , cause [Boom]
2020-10-30-08:11.50.720 |    10781 | WARN  : Error receiving message with [JmsConsumer((amq:amq)(sampleCon)-(S-1604041900731-1)-(C-1)-sample)] : [The Consumer is closed]
2020-10-30-08:11.50.763 |    10824 | DEBUG : Closing Consumer [JmsConsumer((amq:amq)(sampleCon)-(S-1604041900731-1)-(C-1)-sample)]
2020-10-30-08:11.50.767 |    10828 | DEBUG : Closing JMS Session [((amq:amq)(sampleCon)-(S-1604041900731-1))]
2020-10-30-08:11.51.442 |    11503 | WARN  : Error sending message with [JmsProducer((amq:amq)(sampleCon)-(S-1604041900731-2)-P-1)] to [JmsQueue(sample)]: [The Session is closed]
2020-10-30-08:11.51.451 |    11512 | DEBUG : Closing JMS Producer [JmsProducer((amq:amq)(sampleCon)-(S-1604041900731-2)-P-1)]
2020-10-30-08:11.51.455 |    11516 | DEBUG : Closing JMS Session [((amq:amq)(sampleCon)-(S-1604041900731-2))]
2020-10-30-08:11.52.459 |    12520 | DEBUG : Trying to recover producer for [(amq:amq)] with [JmsQueue(sample)]
2020-10-30-08:11.52.771 |    12832 | DEBUG : Trying to recover consumer for [(amq:amq)] with destination [JmsQueue(sample)]
2020-10-30-08:11.53.464 |    13525 | DEBUG : Trying to recover producer for [(amq:amq)] with [JmsQueue(sample)]
2020-10-30-08:11.54.467 |    14528 | DEBUG : Trying to recover producer for [(amq:amq)] with [JmsQueue(sample)]
2020-10-30-08:11.54.774 |    14835 | DEBUG : Trying to recover consumer for [(amq:amq)] with destination [JmsQueue(sample)]
2020-10-30-08:11.55.470 |    15531 | DEBUG : Trying to recover producer for [(amq:amq)] with [JmsQueue(sample)]
2020-10-30-08:11.55.773 |    15834 | DEBUG : Ending recovery period for [(amq:amq)]
2020-10-30-08:11.56.472 |    16533 | DEBUG : Trying to recover producer for [(amq:amq)] with [JmsQueue(sample)]
2020-10-30-08:11.56.474 |    16535 | DEBUG : Connecting [(amq:amq)] with clientId [sampleCon]
2020-10-30-08:11.56.475 |    16536 | INFO  : Connector vm://simple started
2020-10-30-08:11.56.486 |    16547 | DEBUG : Created [((amq:amq)(sampleCon))]
2020-10-30-08:11.56.489 |    16550 | DEBUG : Created JMS Producer [JmsProducer((amq:amq)(sampleCon)-(S-1604041916487-1)-P-1)]
2020-10-30-08:11.56.492 |    16553 | DEBUG : Message [2020-10-30-08.11.51.895] sent successfully with [JmsProducer((amq:amq)(sampleCon)-(S-1604041916487-1)-P-1)] to [sample]
2020-10-30-08:11.56.493 |    16554 | DEBUG : Message [2020-10-30-08.11.52.295] sent successfully with [JmsProducer((amq:amq)(sampleCon)-(S-1604041916487-1)-P-1)] to [sample]
2020-10-30-08:11.56.776 |    16837 | DEBUG : Trying to recover consumer for [(amq:amq)] with destination [JmsQueue(sample)]
2020-10-30-08:11.56.781 |    16842 | DEBUG : Created JMS Consumer [JmsConsumer((amq:amq)(sampleCon)-(S-1604041916777-2)-(C-1)-sample)]
2020-10-30-08:11.56.782 |    16843 | DEBUG : Received [Some(ActiveMQTextMessage {commandId = 5, responseRequired = true, messageId = ID:ToonBox-46199-1604041900408-4:2:1:1:1, originalDestination = null, originalTransactionId = null, producerId = ID:ToonBox-46199-1604041900408-4:2:1:1, destination = queue://sample, transactionId = null, expiration = 0, timestamp = 1604041916490, arrival = 0, brokerInTime = 1604041916490, brokerOutTime = 1604041916779, correlationId = null, replyTo = null, persistent = true, type = null, priority = 4, groupID = null, groupSequence = 0, targetConsumerId = null, compressed = false, userID = null, content = null, marshalledProperties = null, dataStructure = null, redeliveryCounter = 0, size = 1070, properties = null, readOnlyProperties = true, readOnlyBody = true, droppable = false, jmsXGroupFirstForConsumer = false, text = 2020-10-30-08.11.51.895})] with [JmsConsumer((amq:amq)(sampleCon)-(S-1604041916777-2)-(C-1)-sample)]
2020-10-30-08:11.56.783 |    16844 | DEBUG : Received [Some(ActiveMQTextMessage {commandId = 6, responseRequired = true, messageId = ID:ToonBox-46199-1604041900408-4:2:1:1:2, originalDestination = null, originalTransactionId = null, producerId = ID:ToonBox-46199-1604041900408-4:2:1:1, destination = queue://sample, transactionId = null, expiration = 0, timestamp = 1604041916492, arrival = 0, brokerInTime = 1604041916493, brokerOutTime = 1604041916780, correlationId = null, replyTo = null, persistent = true, type = null, priority = 4, groupID = null, groupSequence = 0, targetConsumerId = null, compressed = false, userID = null, content = null, marshalledProperties = null, dataStructure = null, redeliveryCounter = 0, size = 1070, properties = null, readOnlyProperties = true, readOnlyBody = true, droppable = false, jmsXGroupFirstForConsumer = false, text = 2020-10-30-08.11.52.295})] with [JmsConsumer((amq:amq)(sampleCon)-(S-1604041916777-2)-(C-1)-sample)]
2020-10-30-08:11.57.123 |    17184 | DEBUG : Message [2020-10-30-08.11.57.122] sent successfully with [JmsProducer((amq:amq)(sampleCon)-(S-1604041916487-1)-P-1)] to [sample]
2020-10-30-08:11.57.123 |    17184 | DEBUG : Received [Some(ActiveMQTextMessage {commandId = 11, responseRequired = true, messageId = ID:ToonBox-46199-1604041900408-4:2:1:1:3, originalDestination = null, originalTransactionId = null, producerId = ID:ToonBox-46199-1604041900408-4:2:1:1, destination = queue://sample, transactionId = null, expiration = 0, timestamp = 1604041917122, arrival = 0, brokerInTime = 1604041917122, brokerOutTime = 1604041917123, correlationId = null, replyTo = null, persistent = true, type = null, priority = 4, groupID = null, groupSequence = 0, targetConsumerId = null, compressed = false, userID = null, content = null, marshalledProperties = null, dataStructure = null, redeliveryCounter = 0, size = 1070, properties = null, readOnlyProperties = true, readOnlyBody = true, droppable = false, jmsXGroupFirstForConsumer = false, text = 2020-10-30-08.11.57.122})] with [JmsConsumer((amq:amq)(sampleCon)-(S-1604041916777-2)-(C-1)-sample)]
--- Entries omitted
```

## Conclusion

With very little code and simple patterns we could create ZIO streams on top of JMS with automatic recovery baked in. Towards the users of the created stream or sink the recovery is completely transparent and from their perspective they are working with normal `ZStream`s or `ZSink`s.

## Next steps

The next step is to add a keep alive monitor to an established connection, which will trigger reconnects if a maximum number of keep alive messages have been missed.
