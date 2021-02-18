(window.webpackJsonp=window.webpackJsonp||[]).push([[22],{123:function(e,t,n){"use strict";n.d(t,"a",(function(){return u})),n.d(t,"b",(function(){return h}));var a=n(0),i=n.n(a);function s(e,t,n){return t in e?Object.defineProperty(e,t,{value:n,enumerable:!0,configurable:!0,writable:!0}):e[t]=n,e}function o(e,t){var n=Object.keys(e);if(Object.getOwnPropertySymbols){var a=Object.getOwnPropertySymbols(e);t&&(a=a.filter((function(t){return Object.getOwnPropertyDescriptor(e,t).enumerable}))),n.push.apply(n,a)}return n}function r(e){for(var t=1;t<arguments.length;t++){var n=null!=arguments[t]?arguments[t]:{};t%2?o(Object(n),!0).forEach((function(t){s(e,t,n[t])})):Object.getOwnPropertyDescriptors?Object.defineProperties(e,Object.getOwnPropertyDescriptors(n)):o(Object(n)).forEach((function(t){Object.defineProperty(e,t,Object.getOwnPropertyDescriptor(n,t))}))}return e}function l(e,t){if(null==e)return{};var n,a,i=function(e,t){if(null==e)return{};var n,a,i={},s=Object.keys(e);for(a=0;a<s.length;a++)n=s[a],t.indexOf(n)>=0||(i[n]=e[n]);return i}(e,t);if(Object.getOwnPropertySymbols){var s=Object.getOwnPropertySymbols(e);for(a=0;a<s.length;a++)n=s[a],t.indexOf(n)>=0||Object.prototype.propertyIsEnumerable.call(e,n)&&(i[n]=e[n])}return i}var c=i.a.createContext({}),b=function(e){var t=i.a.useContext(c),n=t;return e&&(n="function"==typeof e?e(t):r(r({},t),e)),n},u=function(e){var t=b(e.components);return i.a.createElement(c.Provider,{value:t},e.children)},m={inlineCode:"code",wrapper:function(e){var t=e.children;return i.a.createElement(i.a.Fragment,{},t)}},p=i.a.forwardRef((function(e,t){var n=e.components,a=e.mdxType,s=e.originalType,o=e.parentName,c=l(e,["components","mdxType","originalType","parentName"]),u=b(n),p=a,h=u["".concat(o,".").concat(p)]||u[p]||m[p]||s;return n?i.a.createElement(h,r(r({ref:t},c),{},{components:n})):i.a.createElement(h,r({ref:t},c))}));function h(e,t){var n=arguments,a=t&&t.mdxType;if("string"==typeof e||a){var s=n.length,o=new Array(s);o[0]=p;var r={};for(var l in t)hasOwnProperty.call(t,l)&&(r[l]=t[l]);r.originalType=e,r.mdxType="string"==typeof e?e:a,o[1]=r;for(var c=2;c<s;c++)o[c]=n[c];return i.a.createElement.apply(null,o)}return i.a.createElement.apply(null,n)}p.displayName="MDXCreateElement"},89:function(e,t,n){"use strict";n.r(t),n.d(t,"frontMatter",(function(){return o})),n.d(t,"metadata",(function(){return r})),n.d(t,"toc",(function(){return l})),n.d(t,"default",(function(){return b}));var a=n(3),i=n(7),s=(n(0),n(123)),o={slug:"integration-testing",title:"Integration Testing",tags:["ZIO","Integration Testing","Docker","Kubernetes"],author:"Andreas Gies",author_url:"https://github.com/atooni"},r={permalink:"/blended-zio/blog/integration-testing",source:"@site/blog/2021-02-09-IntegrationTesting.md",description:"Modern applications are normally distributed across collaborating nodes, each of which has to fulfill t's role",date:"2021-02-09T00:00:00.000Z",tags:[{label:"ZIO",permalink:"/blended-zio/blog/tags/zio"},{label:"Integration Testing",permalink:"/blended-zio/blog/tags/integration-testing"},{label:"Docker",permalink:"/blended-zio/blog/tags/docker"},{label:"Kubernetes",permalink:"/blended-zio/blog/tags/kubernetes"}],title:"Integration Testing",truncated:!0,prevItem:{title:"Documentation Helpers",permalink:"/blended-zio/blog/doc-helpers"},nextItem:{title:"Keep alive for JMS connections",permalink:"/blended-zio/blog/zio-jms-keep-alive"}},l=[{value:"Different levels of testing",id:"different-levels-of-testing",children:[{value:"Unit tests",id:"unit-tests",children:[]},{value:"Integration tests",id:"integration-tests",children:[]},{value:"Application tests",id:"application-tests",children:[]}]},{value:"Definitions",id:"definitions",children:[]},{value:"Consolidated requirements",id:"consolidated-requirements",children:[]},{value:"Test library candidates",id:"test-library-candidates",children:[]}],c={toc:l};function b(e){var t=e.components,n=Object(i.a)(e,["components"]);return Object(s.b)("wrapper",Object(a.a)({},c,n,{components:t,mdxType:"MDXLayout"}),Object(s.b)("p",null,"Modern applications are normally distributed across collaborating nodes, each of which has to fulfill t's role\nwithin the overall architecture. In order to test such an application automatically, we will need mechanisms to spin\nup a test environment on the fly and execute an arbitrary test suite against such an  environment."),Object(s.b)("p",null,"This article defines the requirements for a test framework geared towards integration testing so that existing test\nframeworks can be evaluated against those requirements or a new library can be designed to fulfill them if no existing\nlibrary can be found. "),Object(s.b)("p",null,"The requirements for an integration test library are derived from an application architecture, that could be called a\n",Object(s.b)("em",{parentName:"p"},"satellite architecture"),": "),Object(s.b)("p",null,"In a nutshell, a ",Object(s.b)("em",{parentName:"p"},"satellite architecture")," consists of a central part which consists of a set of nodes installed at a data center.\nAttached to the data center is a number of remote installations - the satellites. Each remote installation consists of a set of\nnodes which make up a specific application on the satellite. "),Object(s.b)("p",null,"In such an architecture all applications of a given type are built from the\nsame node types and only differ from each other in terms of their configuration. "),Object(s.b)("p",null,"Even though such an architecture serves as the mental model for our application, the test principles could be applied to other\narchitectures likewise."),Object(s.b)("div",{className:"admonition admonition-note alert alert--secondary"},Object(s.b)("div",Object(a.a)({parentName:"div"},{className:"admonition-heading"}),Object(s.b)("h5",{parentName:"div"},Object(s.b)("span",Object(a.a)({parentName:"h5"},{className:"admonition-icon"}),Object(s.b)("svg",Object(a.a)({parentName:"span"},{xmlns:"http://www.w3.org/2000/svg",width:"14",height:"16",viewBox:"0 0 14 16"}),Object(s.b)("path",Object(a.a)({parentName:"svg"},{fillRule:"evenodd",d:"M6.3 5.69a.942.942 0 0 1-.28-.7c0-.28.09-.52.28-.7.19-.18.42-.28.7-.28.28 0 .52.09.7.28.18.19.28.42.28.7 0 .28-.09.52-.28.7a1 1 0 0 1-.7.3c-.28 0-.52-.11-.7-.3zM8 7.99c-.02-.25-.11-.48-.31-.69-.2-.19-.42-.3-.69-.31H6c-.27.02-.48.13-.69.31-.2.2-.3.44-.31.69h1v3c.02.27.11.5.31.69.2.2.42.31.69.31h1c.27 0 .48-.11.69-.31.2-.19.3-.42.31-.69H8V7.98v.01zM7 2.3c-3.14 0-5.7 2.54-5.7 5.68 0 3.14 2.56 5.7 5.7 5.7s5.7-2.55 5.7-5.7c0-3.15-2.56-5.69-5.7-5.69v.01zM7 .98c3.86 0 7 3.14 7 7s-3.14 7-7 7-7-3.12-7-7 3.14-7 7-7z"})))),"note")),Object(s.b)("div",Object(a.a)({parentName:"div"},{className:"admonition-content"}),Object(s.b)("p",{parentName:"div"},"Consider a retail company operating stores internationally with an international headquarter. The central nodes in that case\nwould provide the interface to the headquarters backend systems, while each store would at least run an instance of a POS\n(Point of Sales) system. The POS might be the same across all stores, in which case we would have only one application type. "),Object(s.b)("p",{parentName:"div"},"Caused by mergers and acquisitions the POS systems in individual countries could be different and we would have one application\ntype per POS system installed in the stores. "),Object(s.b)("p",{parentName:"div"},"A real world application would have many more business cases than just the POS system, but in principle data must be pushed from\nthe data center to the satellites and data must be collected within the satellites and pushed to the data center."))),Object(s.b)("h2",{id:"different-levels-of-testing"},"Different levels of testing"),Object(s.b)("h3",{id:"unit-tests"},"Unit tests"),Object(s.b)("p",null,"There are many articles available to learn about unit testing and various testing strategies in about every language one might think of.\nIn the context of ",Object(s.b)("inlineCode",{parentName:"p"},"blended-zio")," we will implement our tests using Scala and ",Object(s.b)("a",Object(a.a)({parentName:"p"},{href:"https://zio.dev/docs/usecases/usecases_testing"}),"ZIO Test"),".\nIn this article we won't go into all the details how tests are implemented, but just take a moment to think about the nature of test we\nwill encounter at this level. "),Object(s.b)("p",null,"As the name suggests, a Unit Test should have a minimal set of dependencies to other components and also minimal requirements for it's\nruntime. At this level we might apply either black box or white box testing. "),Object(s.b)("p",null,"A black box test is implemented against a given API of a component. For example, a queue data structure should implement the FIFO principle\nregardless of it's implementation."),Object(s.b)("p",null,"On the other hand, a white box test of the queue would make assertions about the inner state of the data structure and would require\nknowledge about the specific implementation. "),Object(s.b)("p",null,"In most cases black box tests should be more robust in cases the underlying implementation changes as they should still work unless the\nAPI has changed as well. ZIO Test includes the necessary tools to create Property based tests that help us to verify the expected\nbehavior of the API using powerful data generators."),Object(s.b)("div",{className:"admonition admonition-note alert alert--secondary"},Object(s.b)("div",Object(a.a)({parentName:"div"},{className:"admonition-heading"}),Object(s.b)("h5",{parentName:"div"},Object(s.b)("span",Object(a.a)({parentName:"h5"},{className:"admonition-icon"}),Object(s.b)("svg",Object(a.a)({parentName:"span"},{xmlns:"http://www.w3.org/2000/svg",width:"14",height:"16",viewBox:"0 0 14 16"}),Object(s.b)("path",Object(a.a)({parentName:"svg"},{fillRule:"evenodd",d:"M6.3 5.69a.942.942 0 0 1-.28-.7c0-.28.09-.52.28-.7.19-.18.42-.28.7-.28.28 0 .52.09.7.28.18.19.28.42.28.7 0 .28-.09.52-.28.7a1 1 0 0 1-.7.3c-.28 0-.52-.11-.7-.3zM8 7.99c-.02-.25-.11-.48-.31-.69-.2-.19-.42-.3-.69-.31H6c-.27.02-.48.13-.69.31-.2.2-.3.44-.31.69h1v3c.02.27.11.5.31.69.2.2.42.31.69.31h1c.27 0 .48-.11.69-.31.2-.19.3-.42.31-.69H8V7.98v.01zM7 2.3c-3.14 0-5.7 2.54-5.7 5.68 0 3.14 2.56 5.7 5.7 5.7s5.7-2.55 5.7-5.7c0-3.15-2.56-5.69-5.7-5.69v.01zM7 .98c3.86 0 7 3.14 7 7s-3.14 7-7 7-7-3.12-7-7 3.14-7 7-7z"})))),"note")),Object(s.b)("div",Object(a.a)({parentName:"div"},{className:"admonition-content"}),Object(s.b)("p",{parentName:"div"},"Sometimes this is a very fine line to walk.  For example, a component that sends and receives messages via JMS or some other Middleware\nmight need to spin up an ad hoc instance of the middleware component or a test server to talk to. Some articles will argue that involving\nany external component already belongs in the realm of integration tests."),Object(s.b)("p",{parentName:"div"},"For a unit test we would prefer an ad hoc instance as that makes it easier to provide deterministic pre-conditions. As a general rule, for\na unit test we would aim for the least complicated setup without compromising how meaningful the test is. "))),Object(s.b)("p",null,"The focal point of our unit test suite is to proof the correctness of our code on the component level. One of the metrics used to evaluate\nthe quality of the unit tests suites is the code coverage. "),Object(s.b)("p",null,"We have to keep in mind though, that even with a coverage of 100% this is not a guarantee the code is free of errors. There might be race\nconditions or subtle bugs that occur only in unforeseen circumstances. Also, sometimes 100% might not be achievable easily - especially\nwith inherited code bases. "),Object(s.b)("p",null,"In our own project we have coverage of slightly more than 80% and have the golden rule that we do not change the code if we don't have a failing\ntest. "),Object(s.b)("h3",{id:"integration-tests"},"Integration tests"),Object(s.b)("p",null,"Again, let's take a moment to think about the nature of tests we encounter at this level:"),Object(s.b)("p",null,"We are now assembling our components into applications, which will sit at one of the nodes in our satellite architecture. The applications\nconsist of the compiled binaries of our components together with the configuration files which model the connectivity to the outside world and\npotentially required runtime configuration for our components. "),Object(s.b)("p",null,"The integration tests are now targeting several main areas:"),Object(s.b)("ol",null,Object(s.b)("li",{parentName:"ol"},Object(s.b)("p",{parentName:"li"},"The configuration model is sufficient to use the components in our application scenario."),Object(s.b)("p",{parentName:"li"},"For example, a hard coded JMS destination name would most likely break a test on this level. We could argue that using an arbitrary destination\nname should be subject to a unit test for the underlying component and essentially that is correct. We would add a requirement to the component\nthat the destination name must be variable and configurable and would create a corresponding unit test Then we would come back to our integration test\nusing the component with the correctly configured destination name. "),Object(s.b)("p",{parentName:"li"},"This is just an example - ideally we would have had the requirement from the beginning, but more subtle parameters that we considered to be\nconstant values might pop up and again we would make them configurable and ideally verify the components compliance to the configuration with\na unit test.")),Object(s.b)("li",{parentName:"ol"},Object(s.b)("p",{parentName:"li"},"The components collaborate with each other according to the applications design. "),Object(s.b)("p",{parentName:"li"},"As an example, consider a routing component within the store server. The component should consume inbound messages, determine the routing parameters\nfor the incoming message and apply the routing accordingly. "),Object(s.b)("p",{parentName:"li"},"We can see at least 3 test cases for that component:"),Object(s.b)("ol",{parentName:"li"},Object(s.b)("li",{parentName:"ol"},Object(s.b)("p",{parentName:"li"},"Verify that a valid message is routed correctly. ")),Object(s.b)("li",{parentName:"ol"},Object(s.b)("p",{parentName:"li"},"Verify that a message with invalid routing information is routed to the error channel. ")),Object(s.b)("li",{parentName:"ol"},Object(s.b)("p",{parentName:"li"},"Verify that a message without any routing information is routed to the error channel."))),Object(s.b)("p",{parentName:"li"},"The test implementation in each case would inject a message into the router's inbound channel and create consumers on the error channel and\nthe normal outbound channel. The test would inspect the number of messages in the outbound channels and potentially inspect their content."),Object(s.b)("p",{parentName:"li"},"For example, in the first case we would expect one message in the normal outbound channel and zero messages in the error channel. We would also\nexpect that the message content has not changed, but we might expect some markers on the message indicating that it has been processed by the router."),Object(s.b)("p",{parentName:"li"},"Now we can think about the message router within it's larger context. A store server might use an inbound messaging bridge to consume messages from external\nsystems and forward the messages coming in over the bridge to an internal channel where the message router is configured as a listener. "),Object(s.b)("p",{parentName:"li"},"In terms of test cases we can still use the same set of tests, but with different channels. Instead of sending messages to the inbound channel\nof the message router directly we would send them to an appropriate channel on the central messaging middleware."))),Object(s.b)("p",null,"To measure the completeness of our integration test suite the code coverage is not suitable. Instead, we would be interested to cover as many business cases\nas possible."),Object(s.b)("p",null,Object(s.b)("strong",{parentName:"p"},"Looking at the integration tests we can capture these requirements:")),Object(s.b)("ul",null,Object(s.b)("li",{parentName:"ul"},Object(s.b)("p",{parentName:"li"},"For CI/CD we want to create a minimal, self contained environment in the form of a collection of docker images providing enough functionality to\nexecute our test suite and spin up a set of collaborating docker containers as the the ",Object(s.b)("em",{parentName:"p"},"system under test"),".")),Object(s.b)("li",{parentName:"ul"},Object(s.b)("p",{parentName:"li"},"For CI/CD we want to determine the required ",Object(s.b)("strong",{parentName:"p"},"TestChannels")," from the started containers.")),Object(s.b)("li",{parentName:"ul"},Object(s.b)("p",{parentName:"li"},"If we want to execute our integration test suite within a provided environment, we must be able to describe the connectivity to the provided components\nwith configuration files and determine the required ",Object(s.b)("strong",{parentName:"p"},"TestChannels")," from that configuration. ")),Object(s.b)("li",{parentName:"ul"},Object(s.b)("p",{parentName:"li"},"We must be able to use the set of ",Object(s.b)("strong",{parentName:"p"},"TestChannel"),"s to provide a ",Object(s.b)("strong",{parentName:"p"},"TestEnvironment"),".  ")),Object(s.b)("li",{parentName:"ul"},Object(s.b)("p",{parentName:"li"},"We want to iterate through the ",Object(s.b)("strong",{parentName:"p"},"TestTemplates")," of our ",Object(s.b)("strong",{parentName:"p"},"TestSuite")," at least once, creating ",Object(s.b)("strong",{parentName:"p"},"TestInstance"),"s which will be executed by a ",Object(s.b)("strong",{parentName:"p"},"TestExecutor"),"\nproducing ",Object(s.b)("strong",{parentName:"p"},"TestResult"),"s. ")),Object(s.b)("li",{parentName:"ul"},Object(s.b)("p",{parentName:"li"},"As the main focus of the integration test run is to be used in CI/CD, we want to guarantee that the integration test executions eventually terminates."))),Object(s.b)("h3",{id:"application-tests"},"Application tests"),Object(s.b)("p",null,"At this level it may seem that we are testing the same things as within the integration test, but we want to do it at a larger scale and\nwith a slightly different focus. The main difference is that the ",Object(s.b)("em",{parentName:"p"},"system under test")," is usually larger for application tests than it is for integration\ntests. If we stick to our mental model of a satellite architecture we want to increase the number of satellites, which must also be reflected in\nour test execution by instantiating tests for all participating satellites."),Object(s.b)("p",null,"Also, we do not make the termination of test suite mandatory at this level of testing. Instead, the test executor would continuously select a runnable\ntest template, instantiate and execute it. In that case the test executor would produce a stream of test results, which would update statistics for the\nexecuted tests. "),Object(s.b)("p",null,"Areas of interest at this level might be:"),Object(s.b)("ul",null,Object(s.b)("li",{parentName:"ul"},"The ratio of failed / successful tests for each test template. "),Object(s.b)("li",{parentName:"ul"},"Response times "),Object(s.b)("li",{parentName:"ul"},"Resource consumption such as disk space, memory and threads"),Object(s.b)("li",{parentName:"ul"},"Discovery of resource leaks ")),Object(s.b)("p",null,"With an increasing number of satellite nodes a docker environment available within CI/CD might not be sufficient to provide the ",Object(s.b)("em",{parentName:"p"},"system under test"),".\nHowever, such an environment can evolve towards a ",Object(s.b)("a",Object(a.a)({parentName:"p"},{href:"https://kubernetes.io"}),"kubernetes")," deployment by generating an appropriate setup - for example by\nusing a ",Object(s.b)("a",Object(a.a)({parentName:"p"},{href:"https://helm.sh"}),"helm")," installation."),Object(s.b)("p",null,Object(s.b)("strong",{parentName:"p"},"We can list the basic requirements for the test framework at this level:")),Object(s.b)("ul",null,Object(s.b)("li",{parentName:"ul"},Object(s.b)("p",{parentName:"li"},"We want to create a self contained test environment with a configurable number of nodes that will be used by our test suite.")),Object(s.b)("li",{parentName:"ul"},Object(s.b)("p",{parentName:"li"},"Use the configured environment to determine the required ",Object(s.b)("strong",{parentName:"p"},"TestChannels")," and provide the ",Object(s.b)("strong",{parentName:"p"},"TestEnvironment"),".")),Object(s.b)("li",{parentName:"ul"},Object(s.b)("p",{parentName:"li"},"Use a ",Object(s.b)("strong",{parentName:"p"},"TestExecutor")," to continuously select a ",Object(s.b)("em",{parentName:"p"},"runnable")," ",Object(s.b)("strong",{parentName:"p"},"TestTemplate"),", create and execute a ",Object(s.b)("strong",{parentName:"p"},"TestInstance")," and capture the ",Object(s.b)("strong",{parentName:"p"},"TestResult"))),Object(s.b)("li",{parentName:"ul"},Object(s.b)("p",{parentName:"li"},"We want to capture all ",Object(s.b)("strong",{parentName:"p"},"TestResults")," to build accumulated statistics which can be reported to suitable visualization back ends such as Prometheus\nor Datadog. ")),Object(s.b)("li",{parentName:"ul"},Object(s.b)("p",{parentName:"li"},'Potentially define alerts on the cumulated statistics, for example "The average response time exceeds x milliseconds"'))),Object(s.b)("h2",{id:"definitions"},"Definitions"),Object(s.b)("p",null,"We have captured some requirements and have used some terms without defining them, so let's define them:"),Object(s.b)("h4",{id:"test-channel"},"Test Channel"),Object(s.b)("p",null,"A test channel is a communication endpoint which can be used by the tests to send data to the ",Object(s.b)("em",{parentName:"p"},"system under test")," or retrieve data from the ",Object(s.b)("em",{parentName:"p"},"system under test"),".\nA test channel could be a channel within a message oriented middleware, it could be an interface to the underlying filesystem or just any suitable communication\nmechanism. "),Object(s.b)("h4",{id:"test-environment"},"Test Environment"),Object(s.b)("p",null,"A test environment is a collection of addressable ",Object(s.b)("em",{parentName:"p"},"Test Channels"),". A test environment is the main component for abstracting the ",Object(s.b)("em",{parentName:"p"},"system under test")," to be used\nby the test executor. "),Object(s.b)("h4",{id:"test-template"},"Test Template"),Object(s.b)("p",null,"A test template is a description of a test. It may be instantiated within a given ",Object(s.b)("em",{parentName:"p"},"Test Environment")," for execution. "),Object(s.b)("h4",{id:"test-instance"},"Test Instance"),Object(s.b)("p",null,"A test instance is an instantiated test template which can be scheduled by the test executor. "),Object(s.b)("h4",{id:"test-executor"},"Test Executor"),Object(s.b)("p",null,"The test executor is responsible for selecting and instantiating test templates and schedule the test instances for execution."),Object(s.b)("h4",{id:"test-suite"},"Test Suite"),Object(s.b)("p",null,"A test suite is a collection of test templates."),Object(s.b)("h2",{id:"consolidated-requirements"},"Consolidated requirements"),Object(s.b)("p",null,"There are some requirements that are kind of implicit from the requirements captured so far, but let's take a minute to make them explicit:"),Object(s.b)("ul",null,Object(s.b)("li",{parentName:"ul"},Object(s.b)("p",{parentName:"li"},"Test templates are data structures describing a test. The tests will execute only after they have been instantiated and scheduled. ")),Object(s.b)("li",{parentName:"ul"},Object(s.b)("p",{parentName:"li"},"In general, a test template should not make assumptions about the environment it is instantiated in. In other words, given the correct\ntest environment it should be possible to create an instance running within an integration test or an instance running within an application\ntest. ")),Object(s.b)("li",{parentName:"ul"},Object(s.b)("p",{parentName:"li"},"The test framework should provide suitable tools to discover the test environment. ")),Object(s.b)("li",{parentName:"ul"},Object(s.b)("p",{parentName:"li"},"The test framework should provide a configuration layer allowing to create the test environment via config files. "))),Object(s.b)("h2",{id:"test-library-candidates"},"Test library candidates"),Object(s.b)("ul",null,Object(s.b)("li",{parentName:"ul"},Object(s.b)("a",Object(a.a)({parentName:"li"},{href:"https://scalatest.org"}),"ScalaTest")),Object(s.b)("li",{parentName:"ul"},Object(s.b)("a",Object(a.a)({parentName:"li"},{href:"https://zio.dev/docs/usecases/usecases_testing"}),"ZIO Test")),Object(s.b)("li",{parentName:"ul"},Object(s.b)("a",Object(a.a)({parentName:"li"},{href:"https://www.testcontainers.org/"}),"Testcontainers")),Object(s.b)("li",{parentName:"ul"},Object(s.b)("a",Object(a.a)({parentName:"li"},{href:"https://izumi.7mind.io/distage/distage-testkit.html"}),"Izumi distage test kit"))))}b.isMDXComponent=!0}}]);