// The field app's protocol, spoken as the Android app speaks it (ADR-0011,
// ADR-0012): sign-in through biz-kit with PKCE, the verified capability
// document, a registered Ed25519 device key, and declarations signed when they
// are queued, sent unchanged however late.

import crypto from "node:crypto";
import { request as playwrightRequest } from "@playwright/test";
import { decode } from "./canonical.js";
import { DeviceKey, b64url, eventStatement, registrationStatement, sampleStatement, unb64url } from "./device.js";
import { authURL } from "./stack.js";

const REDIRECT = "com.dispatch.driver:/oauth2redirect";

// Each person's session, kept as the app keeps it: until sign-out (ADR-0011).
// Signing in afresh for every test would also run into /auth's per-client
// sign-in limit, which is right to refuse that.
const sessions = new Map();

export class FieldApp {
  static async open(baseURL, who) {
    return new FieldApp(await playwrightRequest.newContext({ baseURL }), who, baseURL);
  }

  /** An app holding `who`'s session from earlier in the run, or a new one. */
  static async signedIn(baseURL, who) {
    const app = await FieldApp.open(baseURL, who);
    const grant = sessions.get(who.provider);
    if (grant) app.keep(grant);
    else await app.signIn();
    return app;
  }

  keep(grant) {
    this.grant = grant;
    this.token = grant.access_token;
    sessions.set(this.who.provider, grant);
  }

  constructor(http, who, baseURL) {
    this.http = http;
    this.who = who;
    this.baseURL = baseURL;
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
    if (!login.headers().location) throw new Error(`sign-in as ${this.who.provider}: ${login.status()} ${await login.text()}`);
    // The system browser follows the provider back to biz-kit's callback, which
    // hands it to the app's scheme.
    const callback = await this.http.get(login.headers().location, { maxRedirects: 0 });
    const back = new URL(callback.headers().location);
    if (back.searchParams.get("state") !== "app-state") throw new Error(`unexpected redirect ${back}`);

    const token = await this.http.post(`${authURL}/auth/token`, {
      form: { grant_type: "authorization_code", code: back.searchParams.get("code"), code_verifier: verifier, redirect_uri: REDIRECT },
    });
    this.keep(await token.json());
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

  /** The caller's trips not yet over, each with the consent covering it (or null). */
  async assignments() {
    const response = await this.http.get("/v1/me/assignments", { headers: this.headers() });
    return { status: response.status(), body: await response.json() };
  }

  /** Section 25.3: sharing begins with consent for one of one's own trips. */
  async beginLocationConsent(assignmentId) {
    const response = await this.http.post("/v1/me/location-consents", {
      headers: this.headers(),
      data: { assignment_id: assignmentId },
    });
    return { status: response.status(), body: await response.json() };
  }

  async revokeLocationConsent(grantId) {
    const response = await this.http.post(`/v1/me/location-consents/${grantId}/revoke`, { headers: this.headers() });
    return { status: response.status(), body: await response.json() };
  }

  /**
   * A position acquired now (or at `occurredAt`), signed as it is acquired.
   * Fixed-point integers, as the canonical form has no floats.
   */
  acquire({ grantId, assignmentId, lat, lng, accuracyM, occurredAt = new Date() }) {
    const fields = {
      sample_id: crypto.randomUUID(),
      assignment_id: assignmentId,
      lat_e7: Math.round(lat * 1e7),
      lng_e7: Math.round(lng * 1e7),
      accuracy_cm: Math.round(accuracyM * 100),
      provider: "fused",
      is_mock: false,
      occurred_at: occurredAt.toISOString(),
      device_id: this.deviceId,
      device_sequence: ++this.sequence,
      consent_grant_id: grantId,
    };
    return { ...fields, device_signature: b64url(this.key.sign(sampleStatement(fields))) };
  }

  /** Section 24.2: one result per sample, in the order sent. */
  async uploadSamples(samples) {
    const response = await this.http.post("/v1/me/location-samples:batch", {
      headers: this.headers(),
      data: { samples },
    });
    return { status: response.status(), results: (await response.json()).results };
  }

  /**
   * Section 25.3's demand stream: whether anyone entitled to this position is
   * watching it, as `location-demand` events. Playwright's request client
   * cannot read a response that never ends, so this is Node's fetch.
   */
  async openDemand() {
    const abort = new AbortController();
    const response = await fetch(new URL("/v1/me/location-demand", this.baseURL), {
      headers: { ...this.headers(), accept: "text/event-stream" },
      signal: abort.signal,
    });
    if (!response.ok) return { status: response.status, body: await response.json() };
    return { status: response.status, stream: new EventStream(response.body, abort) };
  }

  async signOut() {
    if (sessions.get(this.who.provider) === this.grant) sessions.delete(this.who.provider);
    return this.http.post(`${authURL}/auth/logout`, { headers: this.headers() });
  }
}

/** A server-sent-event stream read frame by frame; comments (heartbeats) are skipped. */
class EventStream {
  constructor(body, abort) {
    this.abort = abort;
    this.events = [];
    this.waiters = [];
    this.ended = this.#read(body.pipeThrough(new TextDecoderStream()).getReader());
  }

  async #read(reader) {
    let buffer = "";
    try {
      for (let chunk = await reader.read(); !chunk.done; chunk = await reader.read()) {
        buffer += chunk.value;
        for (let end = buffer.indexOf("\n\n"); end >= 0; end = buffer.indexOf("\n\n")) {
          this.#frame(buffer.slice(0, end));
          buffer = buffer.slice(end + 2);
        }
      }
    } catch (error) {
      if (error.name !== "AbortError") throw error;
    } finally {
      this.closed = true;
      for (const waiter of this.waiters.splice(0)) waiter(null);
    }
  }

  #frame(text) {
    const lines = text.split("\n");
    const event = lines.find((line) => line.startsWith("event: "))?.slice("event: ".length);
    if (!event) return;
    const data = lines.filter((line) => line.startsWith("data: ")).map((line) => line.slice("data: ".length));
    const received = { event, data: JSON.parse(data.join("\n")) };
    const waiter = this.waiters.shift();
    if (waiter) waiter(received);
    else this.events.push(received);
  }

  /** The next event, or null once the stream has ended; rejects after `timeout` ms. */
  next(timeout = 10_000) {
    if (this.events.length) return Promise.resolve(this.events.shift());
    if (this.closed) return Promise.resolve(null);
    return new Promise((resolve, reject) => {
      const waiter = (event) => {
        clearTimeout(timer);
        resolve(event);
      };
      const timer = setTimeout(() => {
        this.waiters.splice(this.waiters.indexOf(waiter), 1);
        reject(new Error(`no event within ${timeout} ms`));
      }, timeout);
      this.waiters.push(waiter);
    });
  }

  close() {
    this.abort.abort();
  }
}
