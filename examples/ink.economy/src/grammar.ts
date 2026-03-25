import { defineGrammar, declaration, rule } from '@inklang/quill/grammar'

export default defineGrammar({
  package: 'ink.economy',
  declarations: [
    declaration({
      keyword: 'economy',
      inheritsBase: true,
      rules: [
        rule('on_config_clause', r => r.seq(r.keyword('on_config'), r.block())),
      ]
    })
  ]
})
