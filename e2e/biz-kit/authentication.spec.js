import crypto from "node:crypto";
import { expect, test } from "@playwright/test";
import { b64url } from "../support/device.js";
import { signInToPortal } from "../support/portal.js";
import { authURL, person } from "../support/stack.js";

const dispatcher = person("dispatcher");
const redirectURI = "com.dispatch.driver:/oauth2redirect";

test("TaskHost binds native sign-in to the callback provider and PKCE verifier", async ({ request }) => {
  const auth = new URL(authURL);
  const verifier = b64url(crypto.randomBytes(32));
  const challenge = b64url(crypto.createHash("sha256").update(verifier).digest());
  const login = async () => {
    const response = await request.get(new URL(`/auth/${dispatcher.provider}/native/login`, auth).href, {
      params: {
        redirect_uri: redirectURI,
        code_challenge: challenge,
        code_challenge_method: "S256",
        state: "d-patch-auth-acceptance",
      },
      maxRedirects: 0,
    });
    expect(response.status()).toBe(302);
    const callback = new URL(response.headers().location);
    expect(callback.origin).toBe(auth.origin);
    expect(callback.pathname).toBe(`/auth/${dispatcher.provider}/callback`);
    return callback;
  };

  const wrongProvider = await login();
  wrongProvider.pathname = `/auth/${person("driver").provider}/callback`;
  const refused = await request.get(wrongProvider.href, { maxRedirects: 0 });
  expect(refused.status()).toBe(401);
  expect(refused.headers().location).toBeUndefined();

  const callback = await request.get((await login()).href, { maxRedirects: 0 });
  expect(callback.status()).toBe(302);
  const app = new URL(callback.headers().location);
  expect(app.protocol + app.pathname).toBe(redirectURI);
  expect(app.searchParams.get("state")).toBe("d-patch-auth-acceptance");
  const code = app.searchParams.get("code");
  expect(Boolean(code)).toBe(true);

  const exchange = (codeVerifier) => request.post(new URL("/auth/token", auth).href, {
    form: { grant_type: "authorization_code", code, code_verifier: codeVerifier, redirect_uri: redirectURI },
  });
  const invalid = await exchange(b64url(crypto.randomBytes(32)));
  expect(invalid.status()).toBe(400);
  expect(await invalid.json()).toEqual({ error: "invalid_grant" });
  expect((await exchange(verifier)).status()).toBe(400);
});

test("Dispatch accepts a TaskHost session and refuses it after native logout", async ({ page, baseURL }) => {
  const submitToken = async (token) => {
    await page.goto("/login");
    await page.locator("#token").fill(token);
    const [response] = await Promise.all([
      page.waitForResponse((response) =>
        new URL(response.url()).pathname === "/session" && response.request().method() === "POST"),
      page.locator('form[action="/session"] button').click(),
    ]);
    return response.status();
  };

  expect(await submitToken("nat_invalid")).toBe(401);
  const native = await signInToPortal(page, baseURL, dispatcher);
  try {
    expect(await native.principal()).toBe(dispatcher.principal);
    expect((await page.goto("/operations")).status()).toBe(200);
    await expect(page).toHaveURL(/\/operations$/);
    await page.reload();
    await expect(page).toHaveURL(/\/operations$/);

    expect((await native.signOut()).status()).toBe(204);
    const me = await native.http.get(new URL("/auth/me", authURL).href, { headers: native.headers() });
    expect(me.status()).toBe(401);
    await page.goto("/operations");
    await expect(page).toHaveURL(/\/login$/);
    expect(await submitToken(native.token)).toBe(401);
  } finally {
    await native.http.dispose();
  }
});
