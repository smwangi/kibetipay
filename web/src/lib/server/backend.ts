import "server-only";
import type { Session } from "./session";

export const BACKEND_URL = (process.env.KIBETIPAY_API_URL ?? "http://localhost:8080").replace(/\/$/, "");

export function basicAuth(session: Pick<Session, "username" | "password">): string {
  return "Basic " + Buffer.from(`${session.username}:${session.password}`, "utf8").toString("base64");
}

export function backendFetch(path: string, session: Pick<Session, "username" | "password">, init: RequestInit = {}) {
  const headers = new Headers(init.headers);
  headers.set("Authorization", basicAuth(session));
  headers.set("Accept", "application/json");
  return fetch(BACKEND_URL + path, { ...init, headers, cache: "no-store", redirect: "manual" });
}
