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
    {
      type: 'category',
      label: 'Classes',
      items: [
        'classes/defining',
        'classes/inheritance',
      ],
    },
    'examples',
  ],
};

export default sidebars;
