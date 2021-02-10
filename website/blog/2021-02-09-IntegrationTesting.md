---
slug: integration-testing
title: Integration Testing
tags: [ZIO, Integration Testing, Docker, Kubernetes]
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

In a nutshell, a _satellite architecture_ consists of a central part which consists of a set of nodes intalled at a data center.
Attached to the data center is a number of remote installations - the satellites. Each remote installation consists of a set of 
nodes which make up a specific application on the satellite. 

In such an architecture all applications of a given type are built from the 
same node types and only differ from each other in terms of their configuration. 

Even though such an architecture serves as the mental model for our application, the test principles could be applied to other 
architectures likewise.

:::note
Consider a retail company operating stores internationally with an international headquarter. The central nodes in that case 
would provide the interface to the headquarter's backend systems, while each store would at least run an instance of a POS 
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
might need to spin up an ad hoc instance of the middleware component or a test server to talk to. Some articles will argue that involving 
any esternal component already belongs in the realm of integration tests.

For a unit test we would prefer an adhoc instance as that makes it easier to provide deterministic pre-conditions. As a general rule, for 
a unit test we would aim for the least complicated setup without compromising how meaningfull the test is. 
:::

The focal point of our unit test suite is to proof the correctness of our code on the component level. One of the metrics used to evaluate 
the quality of the unit tests suites is the code coverage. 

We have to keep in mind though, that even with a coverage of 100% this is not a guarantee the code is free of errors. There might be race 
conditions or subtle bugs that occurr only in unforeseen circumstances. Also, sometimes 100% might not be achievable easily - especially 
with inherited code bases. 

In our own project we have coverage of slightly more than 80% and have the golden rule that we do not change the code if we don't have a failing 
test. 

### Integration tests

Again, let's take a moment to think about the nature of tests we encounter at this level:

We are now assembling our components into applications, which will sit at one of the nodes in our satellite architecture. The applications 
consist of the compiled binaries of our components together with the configuration files which model the connectivity to the outside world and 
potentially required runtime configuration for our components. 

The integration tests are now targeting several main areas:

1. The configuration model is sufficient to use the components in our application scenario.
  
   For example, a hard coded JMS destination name would most likely break a test on this level. We could argue that using an arbitrary destination 
   name should be subject to a unit test for the underlying component and essentially that is correct. We would add a requirement to the component
   that the destination name must be variable and configurable and would create a corresponding unit test Then we would come back to our integration test 
   using the component with the correctly configured destination name. 

   This is just an example - ideally we would have had the requirement from the beginning, but more subtle parameters that we considered to be 
   constant values might pop up and again we would make them configurable and ideally verify the components obeyance to the configuration with 
   a unit test.

1. The components collaborate with each other according to the applications design. 

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

   In terms of test cases we can still use the same set of tests, but with different channels. Instead of sending messages to the inbaund channel 
   of the message router directly we would send them to an appropriate channel on the central messaging middleware.

To measure the completeness of our integration test suite the code coverage is not suitable. Instead, we would be interested to cover as many business cases 
as possible.

__Looking at the integration tests we can capture these requirements:__

- For CI/CD we want to create a minimal, self contained environment in the form of a collection of docker images providing enough functionality to 
  execute our test suite and spin up a set of collaborting docker containers as the the _system under test_.

- For CI/CD we want to determine the required __TestChannels__ from the started containers.

- If we want to execute our integration test suite within a provided environment, we must be able to describe the connectivity to the provided components
  with configuration files and determine the required __TestChannels__ from that configuration. 

- We must be able to use the set of __TestChannel__s to provide a __TestEnvironment__.  

- We want to iterate through the __TestTemplates__ of our __TestSuite__ at least once, creating __TestInstance__s which will be executed by a __TestExecutor__ 
  producing __TestResult__s. 

- As the main focus of the integration test run is to be used in CI/CD, we want to guarantee that the integration test executions eventually terminates.

### Application tests

At this level it may seem that we are testing the same things as within the integration test, but we want to do it at a larger scale and 
with a slightly different focus. The main difference is that the _system under test_ is usually larger for application tests than it is for integration
tests. If we stick to our mental model of a satellite architecture we want to increase the number of satellites, which must also be reflected in 
our test execution by instantiating tests for all participating satellites.

Also, we do not make the termination of test suite mandatory at this level of testing. Instead, the test executor would continously select a runnable 
test template, instantiate and execute it. In that case the test executor would produce a stream of test results, which would update statistics for the 
executed tests. 

Areas of interest at this level might be:

- The ratio of failed / successfull tests for each test template. 
- Response times 
- Resource consumption such as disk space, memory and threads
- Discovery of resource leaks 

With an increasing number of satellite nodes a docker environment available within CI/CD might not be sufficient to provide the _system under test_. 
However, such an environment can evolve towards a [kubernetes](https://kubernetes.io) deployment by generating an appropriate setup - for example by 
using a [helm](https://helm.sh) installation.

__We can list the basic requirements for the test framework at this level:__

- We want to create a self contained test environment with a configurable number of nodes that will be used by our test suite.

- Use the configured environment to determine the required __TestChannels__ and provide the __TestEnvironment__.

- Use a __TestExecutor__ to continously select a _runnable_ __TestTemplate__, create and execute a __TestInstance__ and capture the __TestResult__

- We want to capture all __TestResults__ to build accumulated statistics which can be reported to suitable visualization backends such as Prometheus 
  or Datadog. 

- Potentially define alerts on the cumulated statistics, for example "The average response time exceeds x milliseconds"

## Definitions

We have captured some requirements and have used some terms without defining them, so let's define them:

#### Test Channel 
A test channel is a communication endpoint which can be used by the tests to send data to the _system under test_ or retrieve data from the _system under test_. 
A test channel could be a channel within a message oriented middleware, it could be an interface to the underlying filesystem or just any suitable communication 
mechanism. 

#### Test Environment
A test environment is a collection of addreessable _Test Channels_. A test environment is the main component for abstracting the _system under test_ to be used 
by the test executor. 

#### Test Template 
A test template is a description of a test. It may be instantiated within a given _Test Environment_ for execution. 

#### Test Instance 
A test instance is an instantiated test template which can be scheduled by the test executor. 

#### Test Executor
The test executor is responsible for selecting and instantiating test templates and schedule the test instances for execution.

#### Test Suite 
A test suite is a collection of test templates.

## Consolidated requirements 

There are some requirements that are kind of implicit from the requirements captured so far, but let's take a minute to make them explicit:

- Test templates are data structures describing a test. The tests will execute only after they have been instantiated and scheduled. 

- In general, a test template should not make assumptions about the environment it is instantiated in. In other words, given the correct 
  test environment it should be possible to create an instance running within an integration test or an instance running within an application 
  test. 

- The test framework should provide suitable tools to discover the test environment. 

- The test framework should provide a configuration layer allowing to create the test environment via config files. 

## Test library candidates

- [ScalaTest](https://scalatest.org)
- [ZIO Test](https://zio.dev/docs/usecases/usecases_testing)
- [Testcontainers](https://www.testcontainers.org/)
- [Izumi distage testkit](https://izumi.7mind.io/distage/distage-testkit.html)

