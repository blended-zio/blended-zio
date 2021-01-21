---
giturl:  "https://github.com/blended-zio/blended-zio-jmx"
jmxsrc:  "modules/blended-zio-jmx/blended-zio-jmx/jvm/src/main/scala"
jmxtest: "modules/blended-zio-jmx/blended-zio-jmx/jvm/src/test/scala"
---
# A simple facade to the Platform MBean Server

This service is a small wrapper around the platform MBean server within a JVM. The use case is to
have a server side component which can query for the names within the MBeanServer for a given pattern
and to retrieve the MBean Information for a given object name. All JMX specific data shall be mapped
to appropriate classes, so that the data can be used later on with a simple read-only JMX REST service.

This leads to the following, simple interface definition:

{{< codesection dirref="jmxsrc" file="blended/zio/jmx/MBeanServerFacade.scala" section="service" >}}

{{< hint info >}}
Even though the interface is defined without any environment restrictions, the actual `live` service requires that
a `Logging` service is available. We have decided to push the requirement for a `Logging` service into the instantiation
of the `live` service as we might come up with `test` instances at some point that should just mock up the interface and
does not require any logging at all.

We will use the [zio-logging](https://zio.github.io/zio-logging/) API to perform the actual logging. See
[this post]({{< ref "/posts/2020-09-28-ZIOLogging.md" >}}) for more details on injecting different logging back-ends into the
`live` service instance.
{{< /hint >}}

## Querying for MBean Names

To query for a set of MBean names with an optional is fairly straight forward wrapper around the original JMX API.
We just have to translate from the case class we want to use in our API to a JMX search pattern and call the API.

{{< codesection dirref="jmxsrc" file="blended/zio/jmx/MBeanServerFacade.scala" section="names" >}}

In order to make the code a bit more readable, we encapsulate the translation within some helper methods abstracting
over the case that the pattern may be optional. It might be that a single helper method to translate the pattern
would have been sufficient, but in this case it seemed to improve the code's readability to have two helper methods.

The `queryNames` method performs the actual JMX call and translates the resulting Java object into a `List` of
`JmxObjectName`

{{< codesection dirref="jmxsrc" file="blended/zio/jmx/MBeanServerFacade.scala" section="helper" >}}

## Retrieving MBean information

The complicated part retrieving MBean information is to translate the attributes within the MBean information to an actual
case class. We assume that the MBeans do have properties which are allowed for
[OpenMBeans](https://docs.oracle.com/cd/E19206-01/816-4178/6madjde4v/index.html).

If we encounter an attribute that is not valid as an attribute in the sense of the Open MBean specification, we will ignore
that attribute in our mapping rather than throw an exception. As a result, some attributes _may_ be missing for certain
MBean Info objects.

The mapping between attributes and their case class representation happens within the `JmxAttributeCompanion` object. For the
simple types this is straight forward:

{{< codesection dirref="jmxsrc" file="blended/zio/jmx/JmxAttributeCompanion.scala" section="simple" >}}

To map the complex data we will rely on the ZIO `collectPar` operator:

{{< codesection dirref="jmxsrc" file="blended/zio/jmx/JmxAttributeCompanion.scala" section="tabdata" >}}

Note, that within `JmxAttributeCompanion` the overall signature is

```scala
def make(v: Any): ZIO[Any, IllegalArgumentException, AttributeValue[_]]
```
This means that the error handling is in the responsibility of the user of the `make` effect.

{{< codesection dirref="jmxsrc" file="blended/zio/jmx/MBeanServerFacade.scala" section="attribute" >}}

Here, the `orElse` operator will handle the error by just ignoring the attribute that was faulty.

### Representation of an entire MBean Info

The entire MBean Info object contains the `JmxObjectName` it belongs to and a Map of attribute names to their corresponding values.
In other words, the attributes of a MBean Info can be represented by an instance of `CompositeAttributeValue`.

{{< codesection dirref="jmxsrc" file="blended/zio/jmx/JmxAttribute.scala" section="beaninfo" >}}
