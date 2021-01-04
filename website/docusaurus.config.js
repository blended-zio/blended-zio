module.exports = {
  title: 'Blended ZIO',
  tagline: 'Composable integration flows',
  url: 'https://blended-zio.github.io',
  baseUrl: '/blended-zio/',
  onBrokenLinks: 'throw',
  onBrokenMarkdownLinks: 'warn',
  favicon: 'img/favicon.ico',
  organizationName: 'blended-zio', // Usually your GitHub org/user name.
  projectName: 'blended-zio', // Usually your repo name.
  themeConfig: {
    prism: {
      theme: require('prism-react-renderer/themes/nightOwl'),
      additionalLanguages: ['scala'],
    },
    navbar: {
      //title: 'Blended ZIO',
      logo: {
        alt: 'Blended ZIO',
        src: 'img/Logos/svg/black_no_background.svg',
      },
      items: [
        {
          to: 'docs/',
          activeBasePath: 'docs',
          label: 'Docs',
          position: 'right',
        },
        { to: 'blog', label: 'Blog', position: 'right' },
        {
          href: 'https://github.com/blended-zio/blended-zio',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Docs',
          items: [
            {
              label: 'Style Guide',
              to: 'docs/',
            },
            {
              label: 'Second Doc',
              to: 'docs/doc2/',
            },
          ],
        },
        {
          title: 'Community',
          items: [
            {
              label: 'Discord',
              href: 'https://discord.gg/jaHWkWqn',
            },
            {
              label: 'Discussions',
              href: 'https://github.com/blended-zio/blended-zio/discussions',
            },
          ],
        },
        {
          title: 'More',
          items: [
            {
              label: 'Blog',
              to: 'blog',
            },
            {
              label: 'GitHub',
              href: 'https://github.com/blended-zio/blended-zio',
            },
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} Way of Quality GmbH - Built with Docusaurus v2.`,
    },
  },
  presets: [
    [
      '@docusaurus/preset-classic',
      {
        docs: {
          sidebarPath: require.resolve('./sidebars.js'),
        },
        blog: {
          showReadingTime: false,
        },
        theme: {
          customCss: [
            require.resolve('./src/css/custom.css'),
            //require.resolve('./node_modules/prism-themes/themes/prism-cb.css')
          ],
        },
      },
    ],
  ],
};
