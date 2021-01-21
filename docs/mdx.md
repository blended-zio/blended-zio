---
id: mdx
title: Powered by MDX
---

You can write JSX and use React components within your Markdown thanks to [MDX](https://mdxjs.com/).

export const Highlight = ({children, color}) => ( <span style={{
    backgroundColor: color,
    borderRadius: '2px',
    color: '#fff',
    padding: '0.2rem',
  }}>{children}</span> );


<Highlight color="#25c2a0">Docusaurus green</Highlight> and <Highlight color="#1877F2">Facebook blue</Highlight> are my favorite colors.

I can write **Markdown** alongside my _JSX_!

```jsx
console.log('Every repo must come with a mascot.');
```

```scala title="Test"
package test

import foo

sealed trait Bar 
final case object MyObj extends Bar

object Foo {
  def main(args: Array[String]) : Unit = {
    val i = 5
    val j = i + 3
    println(s"Hello Andreas! (${i + j})")
  }
}
```

