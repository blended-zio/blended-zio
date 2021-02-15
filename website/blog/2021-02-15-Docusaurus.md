---
slug: doc-helpers
title: Documentation Helpers
tags: [ Remark, "Docusaurus 2"]
author: Andreas Gies
author_url: https://github.com/atooni
---

_Blended ZIO_ uses [Docusaurus 2](https://v2.docusaurus.io/) as a generator to produce the static web site from markdown documents. This blog post shows some add-ons we are using to increase the quality of our web site. 

<!-- truncate -->

At the core the website is a collection of markdown documents that are translated to static HTML, which can be hosted on any web server, in our case github pages. [Docusaurus 2](https://v2.docusaurus.io/) uses [Remark.js](https://github.com/gnab/remark) under the covers, which gives the user the option to rely on additional remark plugins in the rendering process. 

## Additional tools 

### Scala MDoc

[Scala mdoc](https://scalameta.org/mdoc/) can be used to run Scala codeblocks through the compiler to ensure that the code examples given in the documentation do compile. Furthermore, 
it provides the option to amend the code block by inserting commented lines with REPL output. 

Mdoc is applied by the sbt mdoc plugin and creates a copy of the markdown files used as input with the Scala code blocks changed according to the mdoc processing rules. 

The resulting output is then run though Remark to produce the final HTML. 

### Code include plugin 

In many cases we want to reference code either from within our own project or from a URL. Within _Blended ZIO_ we are using the [Include Code Plugin](https://www.npmjs.com/package/blended-include-code-plugin) which allows us to copy code blocks from the file system of from URLs into our documentation. 

### Text to Image generator 

Within the documentation we often have the requirement to produce technical images such as graph visualizations, flow charts, sequence diagrams etc. Rather than using a dedicated graphics program, we prefer to create the images from a textual description. There are many generators that support that. 

The fantastic [Kroki Web Service](https://kroki.io/) provides a unified web service as a common interface to many generators that can create images from a special DSL. 

The [Kroki Image Plugin](https://www.npmjs.com/package/remark-kroki-plugin) is used within _Blended ZIO_ to execute the Kroki web service for code blocks having `kroki` as their language. 

## Overall processing 

### Project layout 

###



```kroki imgType="mermaid"
graph LR
html((Web Site))

md((Markdown Sources))


subgraph MDoc
mdoc(Mdoc preprocessor)
end

subgraph Remark
inc(Include code)
kroki(Generate Diagrams)
end

md --> mdoc --> inc --> kroki --> html
```


