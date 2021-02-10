---
slug: integration-testing
title: Integration Testing
tags: [ZIO, Testing, Docker, Kubernetes]
author: Andreas Gies
author_url: https://github.com/atooni
---

Modern applications are normally distributed across collaborating nodes, each of which has to fulfill t's role 
within the overall architecture. In order to test such an application automatically, we will need mechanisms to spin
up a test environment on the fly and execute an arbitrary test suite against such an  environment.

This article defines the requirements for a test framework geared towards integration testing so that existing test 
frameworks can be evaluated against those requirements or a new library can be designed to fulfill them if no existing 
library can be found. 

<!-- truncate -->

The requirements for an integration test library are derived from an application architecture, that could be called a 
_satellite architecture_: 

The central part of an installation consists of a set of nodes installed at a data center. Attached to the data center 
is a number of remote installations. Each remote installation - a satellite - consists of a set of nodes which which make
up a specific application on the satellite. In such an Architecture all applications of a given type are built from  the 
same node types and only differ from each other in terms of their configuration. 

Even though such an architecture serves as the mental model for our application, the test principles and differentations
could be applied to other architectures likewise.

:::note
Consider a retail company operating stores internationally with an international headquarter. The central nodes in that case 
would provide the interface to the headquarters backend systems, while each store would at least run an instance of a POS 
(Point of Sales) system. The POS might be the same across all stores, in which case we would have only one application type. 

Caused by mergers and aquisitions the POS systems in individual countries could be different and we would have one application 
type per POS system installed in the stores. 

A real world application would have many more business cases than just the POS system, but in principle data must be pushed from
the data center to the satellites and data must be collected within the satellites and pushed to the datacenter.
:::

## Different levels of testing 

### Unit tests 

There are many articles available to learn about unit testing and various testing strategies in about every language one might think of. 
In the context of `blended-zio` we will implement our tests using Scala and [ZIO Test](https://zio.dev/docs/usecases/usecases_testing). 
In this article we won't go into all the details how tests are implemented, but just take a moment to think about the nature of test we 
will encounter at this level. 

As the name suggests, a Unit Test should have a minimal set of dependencies to other components and also minimal requirements for it's 
runtime. At this level we might apply either blackbox or whitebox testing. 

A blackbox test is implemented against a given API of a component. For example, a queue datastructure should implement the FIFO principle
regardless of it's implementation.

On the other hand, a whitebox test of the queue would make assertions about the inner state of the data structure and would require 
knowledge about the specific implementation. 

In most cases blackbox tests should be more robust in cases the underlying implementation changes as they should still work unless the 
API has changed as well. ZIO Test includes the necessary tools to create Property based tests that help us to verify the exepected 
behavior of the API using powerful data generators.

:::note 
Sometimes this is a very fine line to walk.  For example, a component that sends and receives messages via JMS or some other Middleware 
might need to spin up an ad hoc instance of the middleware component or irequires a test server to talk to. Some articles will argue 
that involving any external component already belongs in the realm of integration tests.

For a unit test we would prefer an adhoc instance as that makes it easier to provide deterministic pre-conditions. As a general rule, for 
a unit test we would aim for the least complicated setup without compromising how meaningfull the test is. 
:::

:::info
The focal point of our unit test suite is to proof the correctness of our code on the component level. One of the metrics used to evaluate 
the quality of the unit tests suites is the code coverage. 

We have to keep in mind though, that even with a coverage of 100% this is not a guarantee the code is free of errors. There might be race 
conditions or subtle bugs that occurr only in unforeseen circumstances. Also, sometimes 100% might not be achievable easily - especially 
with inherited code bases. 

In our own project we have coverage of slightly more than 80% and have the golden rule that we do not change the code if we don't have a failing 
test. 
:::

### Integration tests

Again, let's take a moment to think about the nature of tests we encounter at this level:

We are now assembling our components into applications, which will sit at one of the nodes in our satellite architecture. The applications 
consist of the compiled binaries of our components and also the configuration files which model the connectivity to the outside world and 
potentially required runtime configuration for our components. 

:::note
Again, think of the distributed retail application. The central nodes will provide connectivity to the central systems such as the enterprise 
JMS or messaging backbone. Typical configuration parameters indicate in which environment the application is currently installed (DEV, QA, UAT, PROD)
and derive which backend systems it needs to connect to. 

The nodes within the store will provide store specific functionality. Typical store parameters include at least a store identifier, so that 
store specific messaging channels and API endpoints can be derived. 
:::

The integration tests are now targeting several main areas:

1. __The configuration model is sufficient to use the components in our application scenario.__
  
   For example, a hard coded JMS destination name would most likely break a test on this level. We could argue that using an arbitrary destination 
   name should be subject to a unit test for the underlying component and essentially that is correct. We would add a requirement to the component
   that the destination name must be variable and configurable the requirement with a unit test. Then we would come back to our integration test 
   using the component with the correctly configured destination name. 

   This is just an example - ideally we would have had the requirement from the beginning, but more subtle parameters that we considered to be 
   constant values might pop up and again we would make them configurable and ideally verify the components obeyance to the configuration with 
   a unit test.

1. __The components collaborate with each other according to the applications design.__

   As an example, consider a routing component within the store server. The component should consume inbound messages, determine the routing parameters 
   for the incoming message and apply the routing accordingly. 

   We can see at least 3 test cases for that component:

   1. Verify that a valid message is routed correctly. 

   1. Verify that a message with invalid routing information is routed to the error channel. 

   1. Verify that a message without any routing information is routed to the error channel.

   The test implementation in each case would inject a message into the router's inbound channel and create consumers on the error channel and 
   the normal outbound channel. The test would inspect the number of messages in the outbound channels and potentially inspect their content.

   For example, in the first case we would expect one message in the normal outbound channel and zero messages in the error channel. We would also 
   expect that the message content has not changed, but we might expect some markers on the message indicating that it has been processed by the router.

   Now we can think about the message router within it's larger context. A store server might use an inbound messaging bridge to consume messages from external 
   systems and forward the messages coming in over the bridge to an internal channel where the message router is configured as a listener. 

   In terms of test cases we can still use the same set of tests, but with different channels. Instead of sending messages to the inbound channel 
   of the message router directly we would send them to an appropriate channel within the central middleware and inspect the potential outcomes of the 
   router's outbound channel.

To run the integration test suite, the middleware components must be available. If we were to reuse a preinstalled central messaging middleware there would 
be a potential that interleaving executions of the test suite influence each other. In our own application we have chosen to provide docker images representing 
the central components - an LDAP server, a central JMS backbone and a central PKI provider. 

Furthermore, the build process must create docker images for each application type involved in the communication. 

To finally automate the test we have to spin up the relevant docker images and connect them to each other within Docker's network layer. The test executor needs 
to retrieve port configuration for the docker images and set up the communication channels required for testing accordingly. If we think about the set of configured 
configuration channels as a __TestEnvironment__, we could say that the __TestSuite__ suite needs to execute within that __TestEnvironment__. 

:::note
Within this layer of testing we usually create a minmal environment which allows us to execute our business cases and verify the _communication behavior_ of our 
applications. Normally, all test cases are executed only once before and the the test suite terminates producing a test report. 
:::

If we consider our retail application again, we can imagine that integration testing against a minimal set of application containers does not necessarily uncover 
all potential problems:

- If more then one store server is connected to the central applications, errors might occur related to interleaving communication. A growing number of containers 
  might also uncover potential problems with the number of connections on the central side or reveal negative effects regarding the runtime behavior. 

- If we would execute the test cases repetative, resource leaks might be uncovered that we would not necessarily see executing the test cases only once.   

This leads to yet another level of testing, _application testing_.

### Application tests

We stick to our mental model of a large retail application with a satellite architecture and a large number of satellites. In our testing efforts we want to:

- Create a self contained test environment with a configurable number of satellite nodes that will be used by out test suite. 

- Use the configured environment to determine the required __TestChannels__ and provide the __TestEnvironment__. 

- Treat each __TestCase__ within our __TestSuite__ as a __TestTemplate__. A __TestExecutor__ will continously select select a template, create and execute 
  a __TestInstance__ capturing the __TestResult__. 

-   
  

At this level it may seem that we are testing the same things as within the integration test, but we want to do it at a larger scale and 
with a slightly different focus. 

:::info
:::

## Existing libraries 

- ScalaTest
- ZIO Test
- Test API's in general 
- testcontainers.org 
