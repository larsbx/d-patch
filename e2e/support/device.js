// The field app's device key and statements (ADR-0012), as the app makes
// them: Ed25519 over canonical statements with a `schema` and no `type`.

import crypto from "node:crypto";
import { encode } from "./canonical.js";

export const b64url = (bytes) => Buffer.from(bytes).toString("base64url");
export const unb64url = (text) => Buffer.from(text, "base64url");

// PKCS#8 for a raw Ed25519 seed (RFC 8410).
const PKCS8_PREFIX = Buffer.from("302e020100300506032b657004220420", "hex");

export class DeviceKey {
  constructor(seed = crypto.randomBytes(32)) {
    this.seed = Buffer.from(seed);
    this.key = crypto.createPrivateKey({ key: Buffer.concat([PKCS8_PREFIX, this.seed]), format: "der", type: "pkcs8" });
    this.publicKey = unb64url(crypto.createPublicKey(this.key).export({ format: "jwk" }).x);
  }

  sign(message) {
    return crypto.sign(null, message, this.key);
  }
}

export const registrationStatement = ({ tenantId, participantId, installationId, publicKey }) =>
  encode({
    schema: "DispatchDeviceRegistrationV1",
    tenant_id: tenantId,
    participant_id: participantId,
    installation_id: installationId,
    public_key: publicKey,
  });

/** `fields`: the body exactly as it will be sent, without device_signature. */
export const eventStatement = (fields) => encode({ ...fields, schema: "DispatchStatusEventV1" });
