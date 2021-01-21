---
id: index
title: Blended ZIO Core
---
# blended-zio-core

Functionality that is required by all _blended_ containers.

## Configuring Blended Containers

As outlined [here]({{< relref "/docs/blended_container.md" >}}) all modules that require configuration should be able to use external configuration files containing place holders to specify lookups from environment variables or resolve encrypted values.

For example, the configuration for an LDAP service might be:

```
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

The ZIO ecosystem has a library called [zio-config](https://zio.github.io/zio-config/) which supports different sources such as property files, HOCON, YAML or even the command line. At the core of the library are ConfigDescriptors which can be used to read the config information into config case classes. The descriptors are also used to generate documentation for the available config options or reports over the configuration values used within the application.

Following the [advice](https://discord.com/channels/629491597070827530/633028431000502273/767663251092930591) from the zio-config library author on discord, we introduce a `LazyConfigString` as follows:

{{< codesection dirref="coresrc" file="blended/zio/core/config/LazyConfigString.scala" section="descriptor" >}}

Essentially we define a class `LazyConfigString`, which instances will eventually hold the resolved config value. Making the class `sealed` and `abstract` ensures that new instances can only bo created from within the companion object.

Within the companion object the case class `Raw` can be instantiated with Strings read from the config sources. Also, within this class the `evaluate` method holds the effect describing the resolution of the raw config string to a real value. Essentially we are deferring the resolution to a `StringEvaluator` service.

At last we need to provide a config descriptor for `LazyConfigStrings`, so that the generated documentation will reflect that the config values are subject to lazy evaluation.

Using the `LazyConfigString`, we can define the `LDAPConfig` as follows:

{{< codesection dirref="coretest" file="blended/zio/core/config/ConfigReaderTest.scala" section="config" >}}

To access a config value, a layer with a `StringEvaluator` must be referenced:

{{< codesection dirref="coretest" file="blended/zio/core/config/ConfigReaderTest.scala" section="access" >}}

With the code above, zio-config will generate the following report in markdown format:

{{< hint info >}}
## Configuration Details

|FieldName|Format                     |Description|Sources|
|---      |---                        |---        |---    |
|         |all-offielddescriptions|           |       |

### Field Descriptions

|FieldName     |Format   |Description                                                          |Sources|
|---           |---      |---                                                                  |---    |
|url           |primitive|lazyly evaluated config string, The url to connect to the LDAP server|       |
|systemUser    |primitive|lazyly evaluated config string                                       |       |
|systemPassword|primitive|lazyly evaluated config string                                       |       |
|userBase      |primitive|lazyly evaluated config string                                       |       |
|userAttribute |primitive|lazyly evaluated config string                                       |       |
|groupBase     |primitive|lazyly evaluated config string                                       |       |
|groupAttribute|primitive|lazyly evaluated config string                                       |       |
|groupSearch   |primitive|lazyly evaluated config string                                       |       |
{{< /hint >}}

## Evaluate simple string expressions

Lazyly evaluated string expressions are simple expressions as defined here:

{{< codesection dirref="coresrc" file="blended/zio/core/evaluator/StringExpression.scala" section="expression" >}}

The notable piece here is the `ModifierExpression`, which has the form
```
$[modifier*[StringExpression]]
```

A modifier expression contains an inner expression and evaluation will be from the innermost expression outwards. After resolving the inner expression, zero or more modifiers will be applied to the resolved value for a given context. The context is a simple `Map[String, String]` and the normal resolution simply maps the resolved expression to the corresponding value in the map.

For example, the expression `$[[foo]]` with the context map `Map("foo" -> "bar")` will yield `"bar"`.

Modifiers will be applied to the value resolved from the context map, for example with the context map from above

```
$[(upper)[foo]] => "BAR"
$[(left:2)[foo]] => "ba"
```

Modifiers are specified as:

{{< codesection dirref="coresrc" file="blended/zio/core/evaluator/Modifier.scala" section="modifier" >}}

{{< hint info >}}
A modifier implementation can override `lookup` to avoid that the value resolved from the inner expression will be used to look up the final value from the context map.

The `EncryptModifier` does that, so that the decryption will be applied to the string resolved from the inner expression.
{{< /hint >}}

## Simple crypto service

The `EncryptModifier` is defined as

{{< codesection dirref="coresrc" file="blended/zio/core/evaluator/Modifier.scala" section="decrypt" >}}

It relies on a crypto service available within the ZIO environment and simply delegates the resolution to the `decrypt` method of that service.

The crypto service is defined as

{{< codesection dirref="coresrc" file="blended/zio/core/crypto/CryptoSupport.scala" section="service" >}}

The default implementation can be instantiated with a password, for convenience the code also contains a default password. The password can also be provided via a file. Essentially, the provided password is used to generate a key that is then used to create an instance of a CryptoService which simply wraps some Crypto methods from Java:

{{< codesection dirref="coresrc" file="blended/zio/core/crypto/CryptoSupport.scala" section="crypto" >}}

## Using the services

To use the services resolving config string, a layer with all required services must be provided:

{{< codesection dirref="coretest" file="blended/zio/core/config/ConfigReaderTest.scala" section="layer" >}}

This layer can be provided to an effect by the means of `provideLayer`

{{< codesection dirref="coretest" file="blended/zio/core/config/ConfigReaderTest.scala" section="access" >}}

Finally, the config can be resolved from a config source created from a `Map` with `ConfigSource.fromMap`:

{{< codesection dirref="coretest" file="blended/zio/core/config/ConfigReaderTest.scala" section="config" >}}
