import React from 'react';

import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Layout from '@theme/Layout';

class Element extends React.Component {
  render() {
    return <h1>Hello, wie geht es Dir  !</h1>
  }
}

function TestPage(): JSX.Element {
  return (
    <Layout
      title="Test"
      description="Some"
      wrapperClassName="blog-wrapper">
      <div className="container margin-vert--lg">
        <div className="row">
          <main className="col col--8">
            <Element />
          </main>
        </div>
      </div>
    </Layout>
  );
}

export default TestPage;
