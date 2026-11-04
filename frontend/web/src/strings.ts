/** Reserved namespace for JavaScript strings that cannot be represented as UTF-8. */
const UTF16_PREFIX = '\0js:utf16:';

function hasUnpairedSurrogate(value: string): boolean {
  for (let index = 0; index < value.length; index++) {
    const unit = value.charCodeAt(index);
    if (unit >= 0xd800 && unit <= 0xdbff) {
      const next = value.charCodeAt(index + 1);
      if (!(next >= 0xdc00 && next <= 0xdfff)) return true;
      index++;
    } else if (unit >= 0xdc00 && unit <= 0xdfff) return true;
  }
  return false;
}

/**
 * Preserve JavaScript UTF-16 strings in the existing UTF-8 StringConstant schema.
 * Ordinary Unicode is unchanged. Unpaired surrogates and reserved-prefix
 * originals are encoded as the prefix followed by four lowercase hex digits per
 * UTF-16 code unit. Escaping prefix originals makes this representation injective.
 * Cypher exposes the stored representation; clients must explicitly decode these
 * exceptional values, or encode an original value when constructing a predicate.
 */
export function encodeArtifactString(value: string): string {
  if (!value.startsWith(UTF16_PREFIX) && !hasUnpairedSurrogate(value)) return value;
  let encoded = UTF16_PREFIX;
  for (let index = 0; index < value.length; index++) {
    encoded += value.charCodeAt(index).toString(16).padStart(4, '0');
  }
  return encoded;
}

/** Decode one value produced by encodeArtifactString; reject corrupt encodings. */
export function decodeArtifactString(value: string): string {
  if (!value.startsWith(UTF16_PREFIX)) return value;
  const payload = value.slice(UTF16_PREFIX.length);
  if (!payload.length || payload.length % 4 !== 0 || /[^0-9a-f]/.test(payload)) {
    throw new Error('Invalid JavaScript UTF-16 string encoding');
  }
  let decoded = '';
  for (let index = 0; index < payload.length; index += 4) {
    decoded += String.fromCharCode(Number.parseInt(payload.slice(index, index + 4), 16));
  }
  if (encodeArtifactString(decoded) !== value) {
    throw new Error('Noncanonical JavaScript UTF-16 string encoding');
  }
  return decoded;
}
