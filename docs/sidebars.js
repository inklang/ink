// @ts-check

/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  docsSidebar: [
    'intro',
    {
      type: 'category',
      label: 'Getting Started',
      items: [
        'getting-started/installation',
        'getting-started/first-program',
        'getting-started/running-code',
      ],
    },
    {
      type: 'category',
      label: 'Language Basics',
      items: [
        'basics/variables',
        'basics/data-types',
        'basics/operators',
        'basics/control-flow',
      ],
    },
    {
      type: 'category',
      label: 'Functions',
      items: [
        'functions/defining',
        'functions/parameters',
        'functions/return-values',
      ],
    },
    {
      type: 'category',
      label: 'Data Structures',
      items: [
        'data-structures/arrays',
        'data-structures/maps',
      ],
    },
    'examples',
  ],
};

export default sidebars;
