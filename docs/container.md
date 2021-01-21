---
id: container
title: "Service based containers"
---

A JVM executing a blended application will be referenced to as a _blended container_. When _blended_ was started in it's first version, it has been a Java application running inside a J2EE server, which everyone referred to as a container. Over time _blended_ has evolved to a stand-alone Scala application on top of OSGi, but the term _container_ has stuck.

A typical _blended environment_ consists of several _blended containers_ distributed across geographical regions throughout the enterprise and the core use case for a _blended environment_ is to provide a communication backbone across the enterprise for other applications. As such _blended_ is the layer to realize enterprise level deployment policies such as enforcing security, connection management and general message routing.

To achieve this, _blended_ makes use of other enterprise applications such as messaging backbones, the PKI infrastructure and others. Every access to an external application is realized within a module and exposed to other modules only in terms of service interfaces, so that a change in the implementation has only minimal impact. In essence, one could say that blended consists of layered services running within the same JVM.

:::note
For example, within _Blended 3_ the JMS connectivity is realized as a service which is offered to other modules with the `ConnectionFactory` interface defined in the [JMS specification](https://download.oracle.com/otndocs/jcp/7195-jms-1.1-fr-spec-oth-JSpec/). Under the covers the implementation uses a keep alive mechanism to ensure that JMS connections are alive and operational.

Another example is a `CertificateManager` managing the key- and trust-store of a _blended container_. Within _Blended 3_, an implementation with self signed certificates and another based on the [`SCEP` protocol](https://en.wikipedia.org/wiki/Simple_Certificate_Enrollment_Protocol) to provision certificates exist. These certificates are used by the `SSLContext` service, which is in turn used by all modules that need to offer a SSL based server socket.
:::

## Container types

Within a blended environment several __container types__ might exist. Each container type is defined by the services that are configured for that particular container. As a general rule, two containers of the same type differ from each other __only__ within their configuration.

## Container deployment

A container deployment consists of the jar files with the services and their implementations and the configuration files.

Within _Blended 3_ it has proven to be a best practice that the configuration files should be kept identical across all container types within a blended environment. To achieve that, the configuration files should allow to define placeholders that can be filled in at runtime. All differences between two containers of the same type are exclusively realized by having different values within the environment variables referenced in the config files.

For example, the configuration for a LDAP service might look like:

```json
{
  url             : "ldaps://ldap.$[[env]].$[[country]]:4712"
  systemUser"     : "admin"
  systemPassword" : "$[(encrypted)[5c4e48e1920836f68f1abbaf60e9b026]]"
  userBase"       : "o=employee"
  userAttribute"  : "uid"
  groupBase"      : "ou=sib,ou=apps,o=global"
  groupAttribute" : "cn"
  groupSearch"    : "(member={0})"
}
```

In this example, two environment variables - `env` and `country` - are required to resolve the LDAP url. In terms of service we need

* a crypto service to decrypt encrypted config values
* an evaluation service to resolve config expressions that might occur in config files

These services can than be used by __all__ modules that need to be configured.

## Container directory structure

A container should be deployable to a target machine simply by extracting an archive to a directory - the `BLENDED_HOME`. Within `BLENDED_HOME` all libraries are deployed within the `lib` sub directory, all configuration files shall be within the `etc` sub directory. If modules require storage on the file system, this storage should be beneath `BLENDED_HOME` as well.

{{< hint info >}}
For example, some containers contain an embedded [ActiveMQ](https://activemq.apache.org) broker, which requires a data directory.
{{< /hint >}}

## Deployment automation

If containers of the same type are identical in terms of their libraries and configuration files and only differ within their environment variables, it is very easy to automate the deployment by different means:

* [Docker](https://www.docker.com/) images can be defined around the deployment archive and the environment variables are the configuration points to instantiate a docker container.
* A [Kubernetes](https://kubernetes.io/) deployment consisting of several _blended containers_ - potentially of different types can be defined on top og the docker images simply by templating the injection of the environment variables.
* Deployment to physical machines can be realized by tools such as [Ansible](https://www.ansible.com/) simply by extracting the deployment archive to the target machine and then use a templating mechanism to create a script exposing the required environment variables.

The creation of docker images can and should be automated within the build environment.
