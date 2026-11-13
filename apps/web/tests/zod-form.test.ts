// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { useForm, type FormContext } from 'vee-validate'
import { describe, expect, it } from 'vitest'
import { defineComponent, h } from 'vue'
import { z } from 'zod'
import { issuePath, issuesToErrors, toTypedSchema } from '~/utils/zod-form'

const person = z.object({
  name: z.string().trim().min(1, 'Enter a name'),
  // Two independent checks: Zod 4 reports both. (Type checks such as .int() stop further checks.)
  code: z.string().min(6, 'At least 6 characters').regex(/\d/, 'Needs a digit'),
  tags: z.array(z.object({ label: z.string().min(2, 'Too short') })),
})

describe('toTypedSchema (Zod 4 -> vee-validate)', () => {
  it('returns the parsed (transformed) value and no errors for valid input', async () => {
    const result = await toTypedSchema(person).parse({ name: '  Sok  ', code: 'abc123', tags: [{ label: 'ok' }] })
    expect(result.errors).toEqual([])
    expect(result.value).toEqual({ name: 'Sok', code: 'abc123', tags: [{ label: 'ok' }] })
  })

  it('groups messages by field and uses vee-validate path syntax for nested arrays', async () => {
    const result = await toTypedSchema(person).parse({ name: ' ', code: 'abc', tags: [{ label: 'ok' }, { label: 'x' }] })
    expect(result.value).toBeUndefined()
    expect(result.errors).toEqual([
      { path: 'name', errors: ['Enter a name'] },
      { path: 'code', errors: ['At least 6 characters', 'Needs a digit'] },
      { path: 'tags[1].label', errors: ['Too short'] },
    ])
  })

  it('reports a type check once (Zod 4 stops checking that field)', async () => {
    const schema = z.object({ age: z.number().int('Whole years only').min(0, 'Cannot be negative') })
    expect((await toTypedSchema(schema).parse({ age: -1.5 })).errors).toEqual([{ path: 'age', errors: ['Whole years only'] }])
  })

  it('maps refinements to the field named in the issue path', async () => {
    const schema = z.object({ a: z.string(), b: z.string() })
      .refine((v) => v.a === v.b, { message: 'Must match', path: ['b'] })
    expect((await toTypedSchema(schema).parse({ a: 'x', b: 'y' })).errors).toEqual([{ path: 'b', errors: ['Must match'] }])
  })

  it('supports async refinements', async () => {
    const schema = z.object({ code: z.string().refine(async (v) => v !== 'taken', 'Already used') })
    expect((await toTypedSchema(schema).parse({ code: 'taken' })).errors).toEqual([{ path: 'code', errors: ['Already used'] }])
  })

  it('builds paths and groups issues', () => {
    expect(issuePath(['items', 0, 'name'])).toBe('items[0].name')
    expect(issuePath([])).toBe('')
    expect(issuesToErrors([{ path: ['x'], message: 'a' }, { path: ['x'], message: 'b' }])).toEqual([{ path: 'x', errors: ['a', 'b'] }])
  })
})

describe('inside a real vee-validate form', () => {
  function mountForm() {
    const Form = defineComponent({
      setup(_, { expose }) {
        expose(useForm({ validationSchema: toTypedSchema(z.object({ name: z.string().trim().min(1, 'Enter a name') })) }))
        return () => h('div')
      },
    })
    return mount(Form).vm as unknown as FormContext<{ name: string }>
  }

  it('reports field errors and blocks submit', async () => {
    const form = mountForm()
    form.setFieldValue('name', '   ')
    const result = await form.validate()
    expect(result.valid).toBe(false)
    expect(result.errors.name).toBe('Enter a name')
  })

  it('submits the transformed values when valid', async () => {
    const form = mountForm()
    form.setFieldValue('name', '  Movie night ')
    let submitted: unknown
    await form.handleSubmit((values) => { submitted = values })()
    expect(submitted).toEqual({ name: 'Movie night' })
  })
})
