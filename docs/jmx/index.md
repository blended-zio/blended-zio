---
id: index
title: "Blended ZIO JMX"
---

All _blended_ applications require some basic JMX functionality:

## MBean Server facade

The MBean Server facade provides read only access to the `MBeanServer` of the underlying JVM. In _Blended 3_ the corresponding trait is defined [here](https://github.com/woq-blended/blended/blob/main/blended.jmx/jvm/src/main/scala/blended/jmx/BlendedMBeanServerFacade.scala).

The corresponding [implementation](https://github.com/woq-blended/blended/blob/main/blended.jmx/jvm/src/main/scala/blended/jmx/internal/BlendedMBeanServerFacadeImpl.scala) is implemented on top of some case classes to represent the JMX objects. The various methods in general return instances of `Try` after executing the calls to the `MBeanServer`.

{{< button relref="mbeanserverfacade" >}}
MBean Server implementation details
{{< /button >}}

## Publish arbitrary case classes as JMX objects

_Blended 3_ implements a mapping from arbitrary case classes to Dynamic MBeans. This mapping requires that all attributes of the case class are of a type supported by the [Open MBean specification](https://docs.oracle.com/cd/E19206-01/816-4178/6madjde4v/index.html).

Any case class that shall be published to JMX requires a naming strategy which maps any given instance of the case class to a JMX Object Name.

To publish a value within _Blended 3_, other modules can simply publish case class instances to the Akka event stream.

The implementation in _Blended 3_ is completed by a [MBeanManger actor](https://github.com/woq-blended/blended/blob/main/blended.jmx/jvm/src/main/scala/blended/jmx/internal/ProductMBeanManagerImpl.scala) which collects those published values from the event stream and creates or updates the MBeans within the MBean server accordingly.

For the _ZIO_ based implementation the actor has been replaced with a service that can be made via a `ZLayer` which transforms the service interface from messages to properly typed ZIO effects.

{{< button relref="mbeanpublisher" >}}
MBean publisher implementation details
{{< /button >}}

## Service invocation metrics

_Blended 3_ has a mechanism to capture metrics for arbitrary service invocations. Each service invocation is triggered by a `ServiceInvocation Started` event, eventually followed by either an `ServiceInvocation Completed` or `ServiceInvocation Failed`. Service Invocation events are identified by a unique invocation id.

Furthermore, any service invocation can be mapped to a group identifier, so that invocations belonging to the same group can be summarized:
* the total invocation count
* the count of currently active invocations
* the count of failed invocations
* the count of succeeded invocations
* the minimal, maximal and average execution time of both succeeded and failed invocations

Within _Blended 3_ this is implemented by publishing corresponding events on the Akka Event Stream and a service implemented within an actor listening for these events. Also, this actor uses the JMX publishing mechanism to publish the group summaries as MBeans.

{{< hint info >}}
The ZIO implementation should migrate the actor based solution to a ZIO module and provide the service via a ZLayer to other modules within _Blended ZIO_. Also, the publishing of the group summaries to should be decoupled from the collector Service and implemented as an a service, which requires the collector within it's environment.
{{< /hint >}}

{{< button relref="servicemetrics" >}}
Read more on the implementation details
{{< /button >}}

## Migration ToDo's

* [x] Service Invocation Metrics
  * [x] Implement the service access, so that a singleton instance will be used
  * [x] Review The singleton implementation as it uses an unsaferun method to initialize the TMaps used to hold the internal service state
  * [ ] Refactor the tests to use ZIO property based testing
* [x] MBeanServer Facade
* [x] JMX Publisher for arbitrary case classes
  * [ ] Revisit the publisher implementation to use locks rather than STM based operations as side effecting code such as registering MBeans might break the STM retries


