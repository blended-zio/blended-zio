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

The fantastic [Kroki Web Service](https://kroki.io/) provides a unified web service as a common interface to many generators that can create images from various specialised DSL's. 

The [Kroki Image Plugin](https://www.npmjs.com/package/remark-kroki-plugin) is used within _Blended ZIO_ to execute the Kroki web service for code blocks having `kroki` as their language. 

## Overall processing 

### Project layout 

The project documentation is located within the `docs` directory of the projects. All markdown files from this directory will be preprocessed by Scala Mdoc. 

Everything related to the web site is located within the `website` directory. The markdown files within `website\blog` will not be run through Scala Mdoc. 

### Site generation 

First, the content of `docs` is run through Scala Mdoc. This will produce a modified copy of the markdown files in `website/docs`. Together with the markdown documents in `website/blog` this will be the primary input to the site generator. 

The markdown input will be run though the chain of Remark processors, the last of which will actually generate the HTML pages. We have configured the plugins above into our Docusaurus 2 config file as below:

CODE_INCLUDE lang="javascript" file="./docusaurus.config.js" doctag="configure" title="docusaurus.config.js"

The image below has been generated using [mermaid](https://mermaid-js.github.io/mermaid/#/) and depicts the overall site generation.

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
## Working on the documentation 

To work on the documentation locally, it is best to run two terminal windows to execute the individual parts of the generator chain:

### Start mdoc

In the first terminal, navigate to the project's checkout directory and start a `sbt` shell. 

Within the sbt shell, execute 

```
docs/mdoc --watch
```

This will monitor the docs directory and execute the mdoc preprocessor whenever a change in one of the markdown documents is detected. 

### Start the docusaurus test server 

In the second terminal, navigate to the `website` directory and 

1. For the first start, run `npm install`
1. Otherwise, just run `yarn start`

This will start a local web server on port 3000, so that the documentation can be viewed at http://localhost:3000/blended-zio. Now, when changes to one of the markdown files 
in `docs` or `website/blog` are made and saved, the website will update automatically within the browser. 

:::note
Without running the mdoc preprocessor, the changes to the markdown files in `doc` will not trigger an update to the generated site. 
:::