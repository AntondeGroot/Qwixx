// @ts-check
const eslint = require('@eslint/js');
const { defineConfig } = require('eslint/config');
const tseslint = require('typescript-eslint');
const angular = require('angular-eslint');
const sonarjs = require('eslint-plugin-sonarjs');

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
  {
    files: ['**/*.html'],
    extends: [angular.configs.templateRecommended, angular.configs.templateAccessibility],
    rules: {},
  },
]);
