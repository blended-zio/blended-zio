---
slug: zio-logging
title: Use ZIO Logging
tags: [ZIO, Logging, Utilities]
author: Andreas Gies
author_url: https://github.com/atooni
---

In this article we will investigate how we can leverage [zio-logging](https://zio.github.io/zio-logging/) in our service implementations while avoiding to add a logging service requirement to the business interfaces.

<!-- truncate -->

:::note
The complete source code used in this article can be found on [github](https://github.com/blended-zio/blended-zio/tree/main/blended.zio.jmx)
:::

## Keep business interfaces free from non-business requirements

Within _Blended ZIO_ the services are kept clean of non functional requirements such as relying on a logging service being present within the environment.

For example, the `Service` within `MBeanServerFacade` is defined as follows.

CODE_INCLUDE lang="scala" file="../blended.zio.jmx/src/main/scala/blended/zio/jmx/MBeanServerFacade.scala" doctag="service" title="Service Definition"

However, within the service's implementation `JvmMBeanServerFacade` the corresponding methods leverage the API of [zio-logging](https://zio.github.io/zio-logging/) to produce some output while executing the effects.

CODE_INCLUDE lang="scala" file="../blended.zio.jmx/src/main/scala/blended/zio/jmx/MBeanServerFacade.scala" doctag="info" title="Sample implementation"

So, when we assemble the service

* we need to provide a Logging service when building up the ZLayer
* we need to make the Logging service available to the service implementation
* the business service as such should not have any knowledge of the Logging service requirement

The code to construct the live service which requires `Logging` leverages `ZLayer.fromFunction`. We see that a `Logging` service is required within the environment and we can use the parameter to the `fromFunction` call in the `provide` operator so that the requirement of having a `Logging` service is eliminated and the sole business service interface remains.

CODE_INCLUDE lang="scala" file="../blended.zio.jmx/src/main/scala/blended/zio/jmx/MBeanServerFacade.scala" doctag="zlayer" title="Layer definition"

We might have other service implementations that do not require logging or use a different logging API while keeping the same business interface.

Finally, we can construct the environment for our program as we do in the testcase:

CODE_INCLUDE lang="scala" file="../blended.zio.jmx/src/test/scala/blended/zio/jmx/MBeanServerTest.scala" doctag="zlayer" title="Layer creation"
