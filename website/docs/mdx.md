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
    println("Hello Andreas")
  }
}
```

<div>
  <button class="button button--primary">Primary</button>
  <button class="button button--secondary">Secondary</button>
  <button class="button button--success">Success</button>
  <button class="button button--info">Info</button>
  <button class="button button--warning">Warning</button>
  <button class="button button--danger">Danger</button>
  <button class="button button--link">Link</button>
</div>
