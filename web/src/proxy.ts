import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

/**
 * Optimistic check only: no cookie -> go to /login. The route handlers still verify the sealed
 * session on every API call, so a forged cookie gets a 401, not data.
 */
export function proxy(request: NextRequest) {
  if (request.cookies.has("kp_session")) {
    return NextResponse.next();
  }
  const login = new URL("/login", request.url);
  const next = request.nextUrl.pathname + request.nextUrl.search;
  if (next !== "/") {
    login.searchParams.set("next", next);
  }
  return NextResponse.redirect(login);
}

export const config = {
  // Everything except the login page, the session/API routes and static assets.
  matcher: ["/((?!login|api/|_next/|favicon\\.ico|.*\\.(?:svg|png|jpg|ico|webp)$).*)"],
};
