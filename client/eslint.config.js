// @ts-check
const eslint = require('@eslint/js');
const { defineConfig } = require('eslint/config');
const tseslint = require('typescript-eslint');
const angular = require('angular-eslint');
const sonarjs = require('eslint-plugin-sonarjs');
const boundaries = require('eslint-plugin-boundaries');

module.exports = defineConfig([
  {
    ignores: ['src/generated/**'],
  },
  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      tseslint.configs.recommended,
      tseslint.configs.stylistic,
      angular.configs.tsRecommended,
      sonarjs.configs.recommended,
    ],
    processor: angular.processInlineTemplates,
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: __dirname,
      },
    },
    rules: {
      '@angular-eslint/directive-selector': [
        'error',
        {
          type: 'attribute',
          prefix: 'app',
          style: 'camelCase',
        },
      ],
      '@angular-eslint/component-selector': [
        'error',
        {
          type: 'element',
          prefix: 'app',
          style: 'kebab-case',
        },
      ],
      '@typescript-eslint/no-deprecated': 'error',
      '@typescript-eslint/prefer-readonly': 'error',
      '@typescript-eslint/no-floating-promises': 'error',
      'no-console': ['error', { allow: ['warn', 'error'] }],
      eqeqeq: ['error', 'always', { null: 'ignore' }],
      // God-object / size caps — stop a class growing one harmless method at a time. Adopted as a
      // ratchet: files already over the cap get a per-file override pinned at their current counted
      // size (see the overrides at the bottom), so they can only shrink, never grow. Specs are
      // exempt below (fixtures/provider setup make them legitimately long).
      'max-lines': ['error', { max: 400, skipBlankLines: true, skipComments: true }],
      'max-lines-per-function': ['error', { max: 80, skipBlankLines: true, skipComments: true }],
      'max-classes-per-file': ['error', 1],
      // Off project-wide: the only randomness in the client is dice-animation timing (period, phase,
      // stop delay). There is no security-sensitive randomness anywhere, so this security rule is
      // pure noise here.
      'sonarjs/pseudo-random': 'off',
      // Off: TODO markers are used deliberately as discoverable debt markers (the ratchet overrides
      // below and the boundaries exemptions both point future work at a TODO). Flagging them as
      // build-breaking errors is counterproductive.
      'sonarjs/todo-tag': 'off',
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['**/generated/model/!(models)'],
              message: "Use the model barrel: import from '…/generated/model/models'",
            },
            {
              group: ['**/generated/api/!(api)'],
              message: "Use the API barrel: import from '…/generated/api/api'",
            },
          ],
        },
      ],
    },
  },
  {
    files: ['**/*.spec.ts'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-empty-function': 'off',
      '@typescript-eslint/no-unused-vars': 'off',
      // Specs are legitimately long (fixtures, provider setup) and use long describe/it callbacks.
      'max-lines': 'off',
      'max-lines-per-function': 'off',
    },
  },
  // ── Ratchet overrides ───────────────────────────────────────────────────────
  // Files already over a size cap when it was introduced, pinned at their CURRENT counted size
  // (skipBlankLines/skipComments, as reported by lint — not `wc -l`). A frozen ceiling: they can
  // only shrink. TODO: lower each toward the global cap (400 / 80) as the file/method is split,
  // then delete the override once it drops under the global cap.
  {
    files: ['src/app/board/board.component.ts'],
    rules: {
      'max-lines': ['error', { max: 644, skipBlankLines: true, skipComments: true }],
      // TODO: reduce to the default 15 by extracting from the complex method at ~line 730.
      'sonarjs/cognitive-complexity': ['error', 31],
    },
  },
  {
    files: ['src/app/settings/settings.component.ts'],
    rules: {
      'max-lines-per-function': ['error', { max: 94, skipBlankLines: true, skipComments: true }],
      // TODO: reduce to the default 15 (ngOnInit — already down from 31; extract the option-loading).
      'sonarjs/cognitive-complexity': ['error', 19],
    },
  },
  {
    files: ['src/app/services/cell-highlight.service.ts'],
    rules: {
      // TODO: reduce to the default 15 by splitting the highlight-computation method.
      'sonarjs/cognitive-complexity': ['error', 28],
    },
  },
  // ── Architectural layer boundaries ──────────────────────────────────────────
  // Enforce the dependency direction of the app's layers. The load-bearing rule is that a
  // component may use the generated DTO *types* (generated-model) but may NOT inject the generated
  // API *services* (generated-api) — data access must go through an app service, so components don't
  // accrete data-layer logic into god objects. Utils are leaves; services never depend on UI.
  //
  // NOTE: boundaries resolves every import to a file before classifying it. The default node
  // resolver can't follow extensionless TS paths, so without the typescript resolver below every
  // dependency would classify as "unknown" and the rule would silently pass — a green gate that
  // checks nothing. The `import/resolver` setting is load-bearing, not optional.
  {
    files: ['src/app/**/*.ts'],
    ignores: ['**/*.spec.ts'],
    plugins: { boundaries },
    settings: {
      'import/resolver': {
        typescript: { project: 'tsconfig.json' },
      },
      // Order matters: first matching pattern wins, so specific suffixes precede the broad
      // component glob, and generated-api precedes the generated-model catch-all.
      'boundaries/elements': [
        { type: 'app', mode: 'full', pattern: ['src/app/app.ts', 'src/app/app.config.ts', 'src/app/app.routes.ts'] },
        { type: 'util', mode: 'full', pattern: ['src/app/**/*.util.ts', 'src/app/**/*.data.ts'] },
        { type: 'guard', mode: 'full', pattern: ['src/app/**/*.guard.ts'] },
        { type: 'interceptor', mode: 'full', pattern: ['src/app/interceptors/**/*'] },
        { type: 'service', mode: 'full', pattern: ['src/app/services/**/*.service.ts'] },
        { type: 'component', mode: 'full', pattern: ['src/app/**/*.component.ts'] },
        { type: 'generated-api', mode: 'full', pattern: ['src/generated/api/**/*'] },
        { type: 'generated-model', mode: 'full', pattern: ['src/generated/**/*'] },
      ],
    },
    rules: {
      // no-unknown(-files) also flags external packages and unclassified bootstrap files (main.ts,
      // environments); leave off so the dependency rule below carries the signal.
      'boundaries/no-unknown': 'off',
      'boundaries/no-unknown-files': 'off',
      'boundaries/dependencies': [
        'error',
        {
          default: 'disallow',
          rules: [
            { from: { type: 'app' }, allow: { to: { type: '*' } } },
            {
              from: { type: 'component' },
              allow: { to: { type: ['component', 'service', 'guard', 'interceptor', 'util', 'generated-model'] } },
            },
            {
              from: { type: 'service' },
              allow: { to: { type: ['service', 'util', 'generated-api', 'generated-model'] } },
            },
            { from: { type: 'guard' }, allow: { to: { type: ['service', 'util', 'generated-model'] } } },
            {
              from: { type: 'interceptor' },
              allow: { to: { type: ['service', 'util', 'generated-api', 'generated-model'] } },
            },
            { from: { type: 'util' }, allow: { to: { type: ['util', 'generated-model'] } } },
          ],
        },
      ],
    },
  },
  {
    files: ['**/*.html'],
    extends: [angular.configs.templateRecommended, angular.configs.templateAccessibility],
    rules: {},
  },
]);
