// Section 26.6 constrains what the map element may put into the DOM and what it
// may do with coordinates. These assert the parts that are checkable without a
// browser: the element's attribute contract, and that the module registers a
// custom element without exposing a global.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const source = readFileSync(
  new URL("../../central/priv/static/js/maps/participant-map.js", import.meta.url),
  "utf8",
);

test("observed attributes carry only an opaque ID, a version, and a provider", () => {
  const match = source.match(/observedAttributes\(\)\s*\{[\s\S]*?return \[(.*?)\];/);
  assert.ok(match, "observedAttributes must be declared");

  const attributes = match[1].split(",").map((s) => s.trim().replace(/["']/g, "")).filter(Boolean);
  assert.deepEqual(attributes.sort(), [
    "data-map-provider",
    "data-map-version",
    "data-participant-id",
  ]);
});

test("no coordinate is ever written to an attribute", () => {
  assert.ok(!/setAttribute\(\s*["'][^"']*(lat|lng|coord)/i.test(source));
});

test("map data is fetched with no-store, as Section 26.6 requires", () => {
  assert.ok(/cache:\s*["']no-store["']/.test(source));
});

test("the module exposes no global", () => {
  assert.ok(!/\b(window|globalThis)\.[A-Za-z_$]+\s*=/.test(source));
});

test("an in-flight request is aborted before a new one starts", () => {
  assert.ok(/abortController\?\.abort\(\)/.test(source));
});
