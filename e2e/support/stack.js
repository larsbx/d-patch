// The biz-kit end-to-end stack (biz-kit scripts/e2e-stack.sh): who is who,
// where /auth is, and its fixtures during a run.

import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";

const bizKit = process.env.BIZ_KIT_DIR ?? path.resolve(__dirname, "../../../biz-kit");

export const stack = JSON.parse(fs.readFileSync(process.env.E2E_STACK ?? path.join(bizKit, ".e2e/stack.json"), "utf8"));
export const authURL = process.env.E2E_AUTH_URL ?? "http://127.0.0.1:4001";
export const person = (name) => stack.people[name];

/** biz-kit's fixtures, as an administrator would act during a run, on the stack's databases. */
export function fixture(...args) {
  execFileSync("scripts/e2e-stack.sh", ["fixture", ...args], { cwd: bizKit, stdio: "pipe" });
}
