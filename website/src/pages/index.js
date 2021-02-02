import React from 'react';
import clsx from 'clsx';
import Layout from '@theme/Layout';
import Link from '@docusaurus/Link';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import useBaseUrl from '@docusaurus/useBaseUrl';
import styles from './styles.module.css';

const features = [
  {
    title: 'Testable',
    imageUrl: 'img/Testable.jpg',
    description: (
      <>
        Leverage our experience designing and building
        automated tests running locally or in the cloud.
      </>
    ),
  },
  {
    title: 'Built on ZIO',
    imageUrl: 'img/Foundation.jpg',
    imageAlt: 'Photo by Rodolfo Quirós from Pexels',
    description: (
      <>
        <a href="https://zio.dev">ZIO</a> is a library for asynchronous and concurrent programming
        that is based on pure functional programming.
      </>
    ),
  },
  {
    title: 'Contribute',
    imageUrl: 'img/Contribute.jpg',
    imageAlt: 'Photo by Kevin Ku from Pexels',
    description: (
      <>
        Help us by browsing our <a href="https://github.com/blended-zio/blended-zio">repository</a> and
        take a look at the <a href="https://github.com/blended-zio/blended-zio/issues"> open issues</a>.
      </>
    ),
  },
];

function Feature({ imageUrl, imageAlt, title, description }) {
  const imgUrl = useBaseUrl(imageUrl);
  return (
    <div className={clsx('col col--4', styles.feature)}>
      {imgUrl && (
        <div className="text--center">
          <img className={styles.featureImage} src={imgUrl} alt={imageAlt} />
        </div>
      )}
      <h3>{title}</h3>
      <p>{description}</p>
    </div>
  );
}

function Home() {
  const context = useDocusaurusContext();
  const { siteConfig = {} } = context;
  return (
    <Layout
      title={`${siteConfig.title}`}
      description="Composable, functional integration flows">
      <header className={clsx('hero hero--primary', styles.heroBanner)}>
        <div className="container">
          <h1 className="hero__title">{siteConfig.title}</h1>
          <p className="hero__subtitle">{siteConfig.tagline}</p>
          <div className={styles.buttons}>
            <Link
              className={clsx(
                'button button--outline button--secondary button--lg',
                styles.getStarted,
              )}
              to={useBaseUrl('docs/')}>
              Get Started
            </Link>
          </div>
        </div>
      </header>
      <main>
        {features && features.length > 0 && (
          <section className={styles.features}>
            <div className="container">
              <div className="row">
                {features.map((props, idx) => (
                  <Feature key={idx} {...props} />
                ))}
              </div>
            </div>
          </section>
        )}
      </main>
    </Layout>
  );
}

export default Home;
