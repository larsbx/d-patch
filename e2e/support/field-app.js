// The field app's protocol, spoken as the Android app speaks it (ADR-0011,
// ADR-0012): sign-in through biz-kit with PKCE, the verified capability
// document, a registered Ed25519 device key, and declarations signed when they
// are queued, sent unchanged however late.

import crypto from "node:crypto";
import { request as playwrightRequest } from "@playwright/test";
import { decode } from "./canonical.js";
import { DeviceKey, b64url, eventStatement, registrationStatement, unb64url } from "./device.js";
import { authURL } from "./stack.js";

const REDIRECT = "com.dispatch.driver:/oauth2redirect";

export class FieldApp {
  static async open(baseURL, who) {
    return new FieldApp(await playwrightRequest.newContext({ baseURL }), who);
  }

  constructor(http, who) {
    this.http = http;
    this.who = who;
    this.outbox = [];
    this.sequence = 0;
  }

  async signIn() {
    const verifier = b64url(crypto.randomBytes(32));
    const challenge = b64url(crypto.createHash("sha256").update(verifier).digest());
    const login = await this.http.get(`${authURL}/auth/${this.who.provider}/native/login`, {
      params: { redirect_uri: REDIRECT, code_challenge: challenge, code_challenge_method: "S256", state: "app-state" },
      maxRedirects: 0,
    });
    // The system browser follows the provider back to biz-kit's callback, which
    // hands it to the app's scheme.
    const callback = await this.http.get(login.headers().location, { maxRedirects: 0 });
    const back = new URL(callback.headers().location);
    if (back.searchParams.get("state") !== "app-state") throw new Error(`unexpected redirect ${back}`);

    const token = await this.http.post(`${authURL}/auth/token`, {
      form: { grant_type: "authorization_code", code: back.searchParams.get("code"), code_verifier: verifier, redirect_uri: REDIRECT },
    });
    this.grant = await token.json();
    this.token = this.grant.access_token;
    return this.grant;
  }

  headers() {
    return { authorization: `Bearer ${this.token}` };
  }

  async principal() {
    return (await (await this.http.get(`${authURL}/auth/me`, { headers: this.headers() })).json()).principal;
  }

  /** The capability document's claims. Its signature is the app's to verify; biz-kit's tests verify it. */
  async capabilities() {
    const response = await this.http.get("/v1/role-capabilities", { headers: this.headers() });
    if (!response.ok()) return { status: response.status(), body: await response.json() };
    return { status: 200, claims: decode(unb64url((await response.json()).document)) };
  }

  async registerDevice() {
    const { claims } = await this.capabilities();
    this.key = new DeviceKey();
    const installationId = crypto.randomUUID();
    const statement = registrationStatement({
      tenantId: claims.tenant_id,
      participantId: claims.participant_id,
      installationId,
      publicKey: this.key.publicKey,
    });
    const response = await this.http.post("/v1/me/devices", {
      headers: this.headers(),
      data: { installation_id: installationId, public_key: b64url(this.key.publicKey), proof: b64url(this.key.sign(statement)) },
    });
    this.deviceId = (await response.json()).device_id;
    return response;
  }

  /** Queues a declaration, signed now, as `StatusOutbox.declare` does. */
  declare(status, { note, occurredAt = new Date() } = {}) {
    const fields = {
      event_id: crypto.randomUUID(),
      status,
      occurred_at: occurredAt.toISOString(),
      ...(note ? { note } : {}),
      ...(this.deviceId ? { device_id: this.deviceId } : {}),
      device_sequence: ++this.sequence,
    };
    const body = this.key && this.deviceId ? { ...fields, device_signature: b64url(this.key.sign(eventStatement(fields))) } : fields;
    this.outbox.push(body);
    return body;
  }

  /** Sends everything queued, oldest first, unchanged; returns each answer. */
  async sync() {
    const answers = [];
    for (const body of this.outbox.splice(0)) {
      const response = await this.http.post("/v1/me/status-events", {
        headers: { ...this.headers(), "idempotency-key": body.event_id },
        data: body,
      });
      answers.push({ status: response.status(), body: await response.json() });
    }
    return answers;
  }

  async signOut() {
    return this.http.post(`${authURL}/auth/logout`, { headers: this.headers() });
  }
}
