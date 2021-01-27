---
id: mbeanpublisher
title: MBean Publisher
---

The MBean publisher is used to publish arbitrary case classes as `DynamicMBean`s via JMX. A generic mapper will examine the structure of the given case class instance and recursively map all attributes to corresponding attributes within th MBean.

As such the interface definition for the publishing service is:

CODE_INCLUDE lang="scala" file="../blended.zio.jmx/src/main/scala/blended/zio/jmx/publish/ProductMBeanPublisher.scala" doctag="service"

## Using the MBean publisher

The easiest way to use the MBeanPublisher is to make it available through a layer like it is done within the tests:

CODE_INCLUDE lang="scala" file="../blended.zio.jmx/src/test/scala/blended/zio/jmx/MBeanServerTest.scala" doctag="zlayer"

Then the MBeanPublisher can be used by simply passing a case class to `updateMBean`. For now the case class also needs to implement `Nameable` so that the proper `ObjectName` can be calculated.

CODE_INCLUDE lang="scala" file="../blended.zio.jmx/src/test/scala/blended/zio/jmx/publish/MBeanPublisherTest.scala" doctag="simple"

In the test case we are also using the `MBeanServerFacade` to verify that the `MBean` has been published correctly and has the correct values.

:::note
The implementation keeps track of all instances that have been published. Only the first call to publish will actually register the MBean while
subsequent calls will only update the underlying value. The Service makes sure that updates to MBeans are only allowed for MBeans that are based
on the same Class.
:::

## Implementation details

The service implementation keeps track of the published values in a `TMap` with the object name as key and the `DynamicMBean` wrapper around the case class.

To manipulate the `TMap` we use some helper methods to either create or update an entry within the `TMap`:

CODE_INCLUDE lang="scala" file="../blended.zio.jmx/src/main/scala/blended/zio/jmx/publish/ProductMBeanPublisher.scala" doctag="helpers"

:::caution
The implementation uses [STM](https://zio.dev/docs/datatypes/datatypes_stm) under the covers. It is important to note that STM code should not include side effecting code that might not be idempotent (such as appending to a file or as in our case register an MBean). The reason for that
is that the STM code will be retried if any of the STM-values that are being touched by the operation is changed from another fiber.

In our case the tests were failing when run in parallel because the registration in JMX might have executed multiple times, which in turn
caused a JMX exception.

For now we are simply ignoring that specific JMX exception, but a better solution might be looking at another mechanism than TMap to handle that
scenario.

(Also see the [discussion on Discord](https://discordapp.com/channels/629491597070827530/630498701860929559/761219670622601277))
:::

With the helper methods in place, actual service implementation methods is fairly straightforward:

CODE_INCLUDE lang="scala" file="../blended.zio.jmx/src/main/scala/blended/zio/jmx/publish/ProductMBeanPublisher.scala" doctag="methods"
