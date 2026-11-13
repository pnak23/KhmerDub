import type { TypedSchema, TypedSchemaError } from 'vee-validate'
import type { z } from 'zod'

/**
 * Zod 4 -> vee-validate adapter. (@vee-validate/zod 4.15 only supports Zod 3, and vee-validate 4.15 has no
 * Standard Schema support, so this small adapter maps Zod results onto vee-validate's TypedSchema contract.)
 */
export function toTypedSchema<S extends z.ZodType>(schema: S): TypedSchema<z.input<S>, z.output<S>> {
  return {
    __type: 'VVTypedSchema',
    async parse(values) {
      const result = await schema.safeParseAsync(values)
      if (result.success) {
        return { value: result.data, errors: [] }
      }
      return { errors: issuesToErrors(result.error.issues) }
    },
  }
}

/** vee-validate path style: `items[0].name`. Root-level issues get the empty path. */
export function issuePath(path: ReadonlyArray<PropertyKey>): string {
  return path.reduce<string>((acc, segment) => {
    if (typeof segment === 'number') return `${acc}[${segment}]`
    const key = String(segment)
    return acc ? `${acc}.${key}` : key
  }, '')
}

/** Group Zod issues by field, keeping every message for that field in order. */
export function issuesToErrors(issues: ReadonlyArray<{ path: ReadonlyArray<PropertyKey>; message: string }>): TypedSchemaError[] {
  const byPath = new Map<string, string[]>()
  for (const issue of issues) {
    const path = issuePath(issue.path)
    byPath.set(path, [...(byPath.get(path) ?? []), issue.message])
  }
  return [...byPath].map(([path, errors]) => ({ path, errors }))
}
