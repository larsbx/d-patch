// Section 26.6 fixes the map staleness thresholds exactly. They are display
// state and nothing more: Section 8.2 forbids any priority meaning attaching to
// them, so these tests pin the boundaries and assert the vocabulary stays
// descriptive.

import { test } from "node:test";
import assert from "node:assert/strict";

import { freshness } from "../../central/priv/static/js/maps/map-freshness.js";

test("a fresh fix is neither gray nor labelled", () => {
  const { color, label } = freshness(0);
  assert.equal(label, null);
  assert.notEqual(color, "#6b7280");
});

test("the marker turns gray at exactly two minutes", () => {
  assert.equal(freshness(119).color, "#1d4ed8");
  assert.equal(freshness(120).color, "#6b7280");
});

test("the STALE label appears at exactly five minutes", () => {
  assert.equal(freshness(299).label, null);
  assert.equal(freshness(300).label, "STALE");
});

test("a gray marker is not yet labelled STALE", () => {
  const gray = freshness(200);
  assert.equal(gray.color, "#6b7280");
  assert.equal(gray.label, null);
});

test("staleness vocabulary carries no priority or severity term", () => {
  const forbidden = ["urgent", "emergency", "critical", "priority", "severity", "high", "low"];

  for (const seconds of [0, 120, 300, 3600]) {
    const label = (freshness(seconds).label ?? "").toLowerCase();
    for (const term of forbidden) {
      assert.ok(!label.includes(term), `label for ${seconds}s must not contain "${term}"`);
    }
  }
});
