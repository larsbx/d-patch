// CoopEventCanonicalV1: RFC 8949 deterministic CBOR (shortest heads, definite
// lengths, map keys sorted by their encoded bytes) over integers, text, byte
// strings, booleans, null, arrays and text-keyed maps. The platform's one
// canonical form (ADR-0009, ADR-0012); the Kotlin client's
// `core.model.device.Canonical` is the same encoder.

function head(major, value) {
  const n = BigInt(value);
  const type = major << 5;
  if (n < 24n) return Buffer.from([type | Number(n)]);
  for (const [info, size] of [[24, 1], [25, 2], [26, 4], [27, 8]]) {
    if (n < 1n << BigInt(8 * size)) {
      const out = Buffer.alloc(1 + size);
      out[0] = type | info;
      for (let i = 0; i < size; i++) out[size - i] = Number((n >> BigInt(8 * i)) & 0xffn);
      return out;
    }
  }
  throw new RangeError(`integer out of range: ${value}`);
}

const compare = (a, b) => Buffer.compare(a, b);

export function encode(value) {
  if (value === null) return Buffer.from([0xf6]);
  if (value === true) return Buffer.from([0xf5]);
  if (value === false) return Buffer.from([0xf4]);
  if (typeof value === "number" || typeof value === "bigint") {
    if (typeof value === "number" && !Number.isInteger(value)) throw new TypeError("no canonical form for a float");
    const n = BigInt(value);
    return n >= 0n ? head(0, n) : head(1, -1n - n);
  }
  if (typeof value === "string") {
    const bytes = Buffer.from(value, "utf8");
    return Buffer.concat([head(3, bytes.length), bytes]);
  }
  if (Buffer.isBuffer(value) || value instanceof Uint8Array) {
    return Buffer.concat([head(2, value.length), Buffer.from(value)]);
  }
  if (Array.isArray(value)) return Buffer.concat([head(4, value.length), ...value.map(encode)]);
  if (typeof value === "object") {
    const pairs = Object.entries(value)
      .map(([k, v]) => [encode(k), encode(v)])
      .sort(([a], [b]) => compare(a, b));
    return Buffer.concat([head(5, pairs.length), ...pairs.flat()]);
  }
  throw new TypeError(`no canonical form for ${typeof value}`);
}

// Enough of a decoder for what the server signs: the capability document.
export function decode(bytes) {
  let at = 0;
  const argument = (info) => {
    if (info < 24) return info;
    const size = { 24: 1, 25: 2, 26: 4, 27: 8 }[info];
    if (!size) throw new Error(`unsupported CBOR head ${info}`);
    let n = 0n;
    for (let i = 0; i < size; i++) n = (n << 8n) | BigInt(bytes[at++]);
    return n <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(n) : n;
  };
  const item = () => {
    const initial = bytes[at++];
    const major = initial >> 5;
    const info = initial & 0x1f;
    if (major === 7) return { 20: false, 21: true, 22: null }[info];
    const n = argument(info);
    switch (major) {
      case 0: return n;
      case 1: return -1 - n;
      case 2: return Buffer.from(bytes.subarray(at, (at += n)));
      case 3: return Buffer.from(bytes.subarray(at, (at += n))).toString("utf8");
      case 4: return Array.from({ length: n }, item);
      case 5: return Object.fromEntries(Array.from({ length: n }, () => [item(), item()]));
      default: throw new Error(`unsupported CBOR major type ${major}`);
    }
  };
  return item();
}
