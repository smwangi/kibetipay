import "server-only";
import { createCipheriv, createDecipheriv, createHash, randomBytes } from "node:crypto";
import { cookies } from "next/headers";

/**
 * The backend authenticates with HTTP Basic. The browser never sees those credentials:
 * they live in an AES-256-GCM sealed, httpOnly cookie that only these route handlers can open.
 */
export const SESSION_COOKIE = "kp_session";
const MAX_AGE_SECONDS = 60 * 60 * 8;

export type Session = { username: string; password: string; expiresAt: number };

function key(): Buffer {
  const secret = process.env.SESSION_SECRET;
  if (!secret) {
    if (process.env.NODE_ENV === "production") {
      throw new Error("SESSION_SECRET must be set in production");
    }
    return createHash("sha256").update("kibetipay-web-dev-only-secret").digest();
  }
  return createHash("sha256").update(secret).digest();
}

function seal(session: Session): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key(), iv);
  const body = Buffer.concat([cipher.update(JSON.stringify(session), "utf8"), cipher.final()]);
  return [iv, cipher.getAuthTag(), body].map((part) => part.toString("base64url")).join(".");
}

function unseal(value: string): Session | null {
  try {
    const [iv, tag, body] = value.split(".").map((part) => Buffer.from(part, "base64url"));
    const decipher = createDecipheriv("aes-256-gcm", key(), iv);
    decipher.setAuthTag(tag);
    const json = Buffer.concat([decipher.update(body), decipher.final()]).toString("utf8");
    const session = JSON.parse(json) as Session;
    return session.expiresAt > Date.now() ? session : null;
  } catch {
    return null;
  }
}

export async function readSession(): Promise<Session | null> {
  const value = (await cookies()).get(SESSION_COOKIE)?.value;
  return value ? unseal(value) : null;
}

export async function writeSession(username: string, password: string): Promise<void> {
  const session: Session = { username, password, expiresAt: Date.now() + MAX_AGE_SECONDS * 1000 };
  (await cookies()).set(SESSION_COOKIE, seal(session), {
    httpOnly: true,
    sameSite: "strict",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: MAX_AGE_SECONDS,
  });
}

export async function clearSession(): Promise<void> {
  (await cookies()).delete(SESSION_COOKIE);
}
