---
id: usecases
title: Blended Use Cases
slug: /
---
# An overview of requirements for Blended

:::note
For the sake of this article a _Blended_ based application instance running on top of a JVM shall be referenced to as a _blended container_ or simply _container_.
:::

## High Level  

### General architecture

_Blended_ has it's roots in [EAI](https://en.wikipedia.org/wiki/Enterprise_application_integration) and was designed primarily to be used as a communication backbone in a distributed enterprise. The company has a centralized IT with several backend applications and stores/offices  in several countries.

The backend applications communicate with each other over a JMS backbone. This backbone is also used to provide data to the stores and keep it up to date and to collect data from the stores to be processed in the central applications. For monitoring and security reasons, each shop is defined with a dedicated channel to push data to the shop, while the data center collects data from country specific channels that the shops use to send their data to. As a result, from the perspective of any shop all data flows through the same channel and therefore the differentiation of business cases must be encoded within the messages.

### Remote location architecture

As a result, within the shops we have at least one _shop container_ which serves as communication partner for the backend applications. Some of the shop specific applications may require that the data is pushed different machines. This is the case for applications that require data import/export by using the local file system. In these cases, the shop may require the installation of one or more _secondary containers_.

These secondary containers __never__ communicate with the data-center directly, but use the _shop container_ as their messaging relay. In order to keep the shop in itself operating even if the connectivity to the data center is lost, the _shop container_ and the associated _secondary containers_ are connected to each other with a shop internal JMS backbone, which is connected to the data center by the means of a store and forward mechanism.

For resilience, the _shop containers_ should be run in a cluster.

```kroki imgType="mermaid" imgTitle="Collaborating containers"
graph TD
  subgraph Shop X
    Bx(Shop X) --> FX1((Fs X1)) --> Bx
    Bx --> FX2((Fs X2)) --> Bx
  end
  subgraph Shop Y
    By(Shop Y) --> FY1((Fs Y1)) --> By
    By --> FY2((Fs Y2)) --> By
  end
  subgraph Shop Z
    Bz(Shop Z) --> FZ1((Fs Z1)) --> Bz
    Bz --> FZ2((Fs Z2)) --> Bz
  end
  A(Data Center) --> Bx --> A
  A --> By --> A
  A --> Bz --> A
```

## Application requirements

### Data-center -> Shop

Data is sent to an individual shop by placing a message in the shop specific channel, where the _shop container_ can consume them as a client to the central messaging backbone.

### Shop -> Data-center

Data is provided from the shop via the _shop container_ which will place messages in the central, country specific messaging channel.

### Intra-shop

All _containers_ within the shop can communicate with each other using the shop's messaging backbone without involving the central messaging backbone at all.

### Messaging architecture

All messages are routed via the _shop container_ using the same services. Since we are using JMS messages, we can assume that we have a message body and custom message headers to transport information.

Within the message we require an identifier to denote the business case the message belongs to. This business case identifier determines the routing rules that shall be applied to the message moving through the _container_.

:::note
A business case __price__ may indicate that the message shall be routed to the _shop.price_ messaging channel, where a cash desk application may be listening as a consumer.

A business case __payment__ may indicate that the message shall be routed to the _central.payments_ messaging channel, where a central application may be listening as a consumer.
:::

Keeping these examples in mind we can see that the _shop container_ must be able to route message independently from the source or destination messaging provider. Also, since the _shop container_ should be able to operate regardless whether it is currently connected to the central application, the overall communication is split into an _inbound bridge_, an _outbound bridge_ and a _dispatcher_.

The _inbound bridge_ consumes messages from central messaging channels and places them in shop local messaging channels for further processing.

The _outbound bridge_ consumes messages from shop local messaging channels and places them in central messaging channels.

The _dispatcher_ consumes messages from a local dispatcher messaging channel, enriches the message with routing information and either

* dispatches the message to a shop local channel to be consumed by a shop local application
* dispatches the message to a shop local channel to be routed further to a central channel

:::note
The important architectural decision is, that __only__ the _shop container_ is connected to the central messaging backbone while the _secondary containers_ use the inbound / outbound bridge to communicate with the central applications.
:::

Besides this general message flow architecture, the _usual_ requirements apply:

* No messages must be be lost
* Messages must be tracked from _shop entry_ to _shop exit_ with enough information to gather business case related statistics for message processing
* All routing information is parametrized and if required must be kept in message headers
* The _dispatcher_ must not modify the message body nor shall it be required to read it
* The message providers must be pluggable, so that migration between JMS providers is possible
* The _dispatcher_ ideally does not have knowledge whether it processes messages originating from JMS or some other messaging backbone such as Kafka or an AMQP or a MQTT capable backbone. For testing purposes messages might be generated or read from the file system.

### Proxying

More and more backend applications offer REST services rather than JMS. As some of the shop applications move at a different speed in terms of their development, in some cases a protocol mapping JMS <-> REST is required. This mapping shall be configurable, so that new use cases can be adopted without coding.

Within the shop architecture, the protocol mapping should be available to all _containers_ as services within the _shop container_.

## Secondary requirements

### Config as code

The outlined use cases are mostly driven by configuration, which may become quite complex. Therefore we have made a decision early on, that the configuration files are treated as code and are packaged as part of a _container_ deployment. Furthermore, the fingerprints of the config-files are recorded by the packaging process, to that we can detect ad hoc config changes that may have happened after _container_ rollout.

### Static packaging

A _blended container_ has at least a common runtime and has - depending on it's purpose - services implemented on top of the core runtime. The build process shall support packaging of containers for different purposes simply by listing the services or modules required for that purpose.

### Certificate provisioning

Each _container_ has potential services communicating via network sockets - especially JMS or HTTP server components. _Blended_ shall provide a general SSL Context Layer that all components requiring SSL support shall use. The SSL Context Layer shall be capable of retrieving a server SSL certificate, maintain it and configure the SSL Context to use the obtained certificate as server side certificate.

The first implementations of the certificate provisioning shall be for self signed certificates and certificates obtained from a [SCEP](https://en.wikipedia.org/wiki/Simple_Certificate_Enrollment_Protocol) capable server.

## Testing strategy

All modules shall be unit tested. Even though this seems straight forward, some corner cases are hard to test if the code touches external systems or relies on timeouts.

### Simple integration tests

The build process shall produce [docker](https://www.docker.com/) images that can be used to test the collaboration of _shop containers_ and _secondary containers_

### Kubernetes based integration tests

The produced docker images shall be deployable in a kubernetes infrastructure, so that within a cluster one or more shops can be deployed. A specialized _blended container_, the _test container_ shall auto-discover the deployed containers and periodically generate test messages and examine the outcome. These tests shall run as long as the _test container_ is running.

These tests will expose resource leaks and allow to examine failover and restart behavior.
