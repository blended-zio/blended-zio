---
id: servicemetrics
title: Service Metrics
---

The implementation of the ZIO version starts with defining an interface that resembles the operations which are _hidden_ behind the actor based implementation of _Blended 3_. Essentially, the straight forward approach is to look at the messages the actor currently understands and translate them into corresponding methods on the interface.

The actor currently understands 3 messages:

* start service invocation
* complete invocation with success
* complete invocation with error

In addition to this we would like to retrieve the current list of active invocations and also the current collected summaries.

This leads to the following interface definition:

CODE_INCLUDE lang="scala" file="../blended.zio.jmx/src/main/scala/blended/zio/jmx/metrics/ServiceMetrics.scala" doctag="service"

Note, that all methods on the interface return ZIO effects.

## Implementation notes

The implementation needs to maintain a list currently active of service invocation invocations, so that we can properly close them with a failed or completed event. Furthermore, we need to keep track of the invocation summaries so that we can keep track of the grouped invocations statistics.

Inspired by [this article](https://scalac.io/how-to-write-a-completely-lock-free-concurrent-lru-cache-with-zio-stm/) about implementing a concurrent LRU cache we have decided to implement a ConcurrentServiceTracker using STM References under the covers:

CODE_INCLUDE lang="scala" file="../blended.zio.jmx/src/main/scala/blended/zio/jmx/metrics/ServiceMetrics.scala" doctag="tracker"

First of all we need a couple of helpers helping us to manipulate the two maps. The names of the helper functions speak for themselves and all of them use STM under the covers, so that we can compose them to implement the actual business functions and finally call commit in order to end up with a ZIO effect as result.

CODE_INCLUDE lang="scala" file="../blended.zio.jmx/src/main/scala/blended/zio/jmx/metrics/ServiceMetrics.scala" doctag="helpers"

Within the implementation the `update`method is responsible for recording the completion or failure for a given invocation id. Therefore we need to determine the currently active entry from our active map and also the existing summary. Note that if everything works as designed, the summary mst already exist at this point in time. However, either of these calls may fail with a ServiceMetricsException, which is reflected in the method signature.

Once we have looked up the entries, we can simply perform the required update and we are done.

CODE_INCLUDE lang="scala" file="../blended.zio.jmx/src/main/scala/blended/zio/jmx/metrics/ServiceMetrics.scala" doctag="update"

The `start` method is very similar. We are using `getExistingActive(evt.id).flip`, so that having an already defined entry for the given id will be considered an exception. Also, in this case we are using `getOrCreateSummary(evt)` to ensure that the summary map definitely has an entry.

Finally, we are using `mapError` to create the proper exception indicating the a service with the same id was already active.

CODE_INCLUDE lang="scala" file="../blended.zio.jmx/src/main/scala/blended/zio/jmx/metrics/ServiceMetrics.scala" doctag="start"

## Testing

Testing is done with [zio-test](https://zio.dev/docs/howto/howto_test_effects) and is fairly straight forward. The tests provide the `live` service via `ZLayer` and then use the interface methods to call the service and verify the result with assertions.

For example, the test to verify that a successful service completion is implemented as follows:

CODE_INCLUDE lang="scala" file="../blended.zio.jmx/src/test/scala/blended/zio/jmx/metrics/ServiceMetricsTest.scala" doctag="complete"
