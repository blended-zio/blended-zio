---
giturl:  "https://github.com/blended-zio/blended-zio-jmx"
jmxsrc:  "modules/blended-zio-jmx/blended-zio-jmx/jvm/src/main/scala"
jmxtest: "modules/blended-zio-jmx/blended-zio-jmx/jvm/src/test/scala"
---
# MBean Publisher

The MBean publisher is used to publish arbitrary case classes as `DynamicMBean`s via JMX. A generic mapper will examine the structure of the given case class instance and recursively map all attributes to corresponding attributes within th MBean.

As such the interface definition for the publishing service is:

{{< codesection dirref="jmxsrc" file="blended/zio/jmx/publish/ProductMBeanPublisher.scala" section="service" >}}

## Using the MBean publisher

The easiest way to use the MBeanPublisher is to make it available through a layer like it is done within the tests:

{{< codesection dirref="jmxtest" file="blended/zio/jmx/publish/MBeanPublisherTest.scala" section="layer" >}}


Then the MBeanPublisher can be used by simply passing a case class to `updateMBean`. For now the case class also needs to implement `Nameable` so that the proper `ObjectName` can be calculated.

{{< codesection dirref="jmxtest" file="blended/zio/jmx/publish/MBeanPublisherTest.scala" section="simple" >}}

In the test case we are also using the `MBeanServerFacade` to verify that the `MBean` has been published correctly and has the correct values.

{{< hint info >}}
The implementation keeps track of all instances that have been published. Only the first call to publish will actually register the MBean while
subsequent calls will only update the underlying value. The Service makes sure that updates to MBeans are only allowed for MBeans that are based
on the same Class.
{{< /hint >}}

## Implementation details

The service implementation keeps track of the published values in a `TMap` with the object name as key and the `DynamicMBean` wrapper around the case class.

To manipulate the `TMap` we use some helper methods to either create or update an entry within the `TMap`:

{{< codesection dirref="jmxsrc" file="blended/zio/jmx/publish/ProductMBeanPublisher.scala" section="helpers" >}}

{{< hint warning >}}
The implementation uses [STM](https://zio.dev/docs/datatypes/datatypes_stm) under the covers. It is important to note that STM code should not include side effecting code that might not be idempotent (such as appending to a file or as in our case register an MBean). The reason for that
is that the STM code will be retried if any of the STM-values that are being touched by the operation is changed from another fiber.

In our case the tests were failing when run in parallel because the registration in JMX might have executed multiple times, which in turn
caused a JMX exception.

For now we are simply ignoring that specific JMX exception, but a better solution might be looking at another mechanism than TMap to handle that
scenario.

(Also see the [discussion on Discord](https://discordapp.com/channels/629491597070827530/630498701860929559/761219670622601277))
{{< /hint >}}

With the helper methods in place, actual service implementation methods is fairly straightforward:

{{< codesection dirref="jmxsrc" file="blended/zio/jmx/publish/ProductMBeanPublisher.scala" section="methods" >}}
