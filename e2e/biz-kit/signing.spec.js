// The JS field-app client must sign exactly as the Android app and biz-kit
// do: these are the vectors biz-kit generated, which the Kotlin client's
// DeviceSigningTest also reproduces.

import { test, expect } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import { encode } from "../support/canonical.js";
import { DeviceKey, eventStatement, registrationStatement, unb64url } from "../support/device.js";

const vectors = JSON.parse(
  fs.readFileSync(
    path.join(__dirname, "../../apps/android-driver/core/model/src/test/resources/device-signing-vectors.json"),
    "utf8",
  ),
);

const key = new DeviceKey(unb64url(vectors.seed));

test("the device key derives biz-kit's public key", () => {
  expect(key.publicKey.equals(unb64url(vectors.public_key))).toBe(true);
});

test("the registration statement and proof are biz-kit's, byte for byte", () => {
  const { tenant_id, participant_id, installation_id, statement, proof } = vectors.registration;
  const made = registrationStatement({ tenantId: tenant_id, participantId: participant_id, installationId: installation_id, publicKey: key.publicKey });

  expect(made.equals(unb64url(statement))).toBe(true);
  expect(key.sign(made).equals(unb64url(proof))).toBe(true);
});

test("an event statement and signature are biz-kit's, byte for byte", () => {
  const made = eventStatement(vectors.event.fields);

  expect(made.equals(unb64url(vectors.event.statement))).toBe(true);
  expect(key.sign(made).equals(unb64url(vectors.event.signature))).toBe(true);
});

test("canonical heads and key order", () => {
  expect(encode(300).toString("hex")).toBe("19012c");
  expect(encode(-25).toString("hex")).toBe("3818");
  expect(encode({ aa: 2, b: 1 }).toString("hex")).toBe("a2616201626161" + "02");
});
