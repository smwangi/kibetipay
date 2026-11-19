"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { Suspense, type ComponentType, type ReactNode } from "react";
import { useMe, useNotifications } from "@/lib/hooks";
import { cn, Loading } from "./ui";
import {
  ActivityIcon,
  BellIcon,
  ChartIcon,
  ChatIcon,
  HomeIcon,
  LoanIcon,
  Logo,
  LogoutIcon,
  SendIcon,
  TopUpIcon,
} from "./icons";

type NavItem = { href: string; label: string; icon: ComponentType; mobile: boolean };

const NAV: NavItem[] = [
  { href: "/", label: "Home", icon: HomeIcon, mobile: true },
  { href: "/send", label: "Send", icon: SendIcon, mobile: true },
  { href: "/top-up", label: "Top up", icon: TopUpIcon, mobile: true },
  { href: "/activity", label: "Activity", icon: ActivityIcon, mobile: true },
  { href: "/loans", label: "Loans", icon: LoanIcon, mobile: true },
  { href: "/insights", label: "Insights", icon: ChartIcon, mobile: false },
  { href: "/notifications", label: "Notifications", icon: BellIcon, mobile: false },
  { href: "/assistant", label: "Help", icon: ChatIcon, mobile: false },
];

function isActive(pathname: string | null, href: string) {
  if (!pathname) return false;
  return href === "/" ? pathname === "/" : pathname === href || pathname.startsWith(href + "/");
}

function useUnread() {
  const { data } = useNotifications();
  return data?.filter((n) => !n.readAt).length ?? 0;
}

function useSignOut() {
  const router = useRouter();
  return async () => {
    await fetch("/api/session", { method: "DELETE" });
    router.replace("/login");
    router.refresh();
  };
}

/**
 * The frame renders without reading the URL so it can be prerendered. Only the parts that
 * depend on the current path (active link) and the page itself stream in behind Suspense.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const { data: me } = useMe();
  const unread = useUnread();
  const signOut = useSignOut();

  return (
    <div className="min-h-screen md:flex">
      {/* Desktop sidebar */}
      <aside className="sticky top-0 hidden h-screen w-60 shrink-0 flex-col border-r border-border bg-surface px-3 py-5 md:flex">
        <Link href="/" className="mb-8 px-3">
          <Logo />
        </Link>
        <nav aria-label="Main" className="flex flex-1 flex-col gap-1">
          <Suspense fallback={<SideLinks pathname={null} unread={unread} />}>
            <CurrentPath>{(pathname) => <SideLinks pathname={pathname} unread={unread} />}</CurrentPath>
          </Suspense>
        </nav>
        <div className="border-t border-border pt-4">
          {me && (
            <p className="truncate px-3 pb-2 text-sm text-muted">
              Signed in as <span className="font-medium text-text">{me.username}</span>
            </p>
          )}
          <button
            onClick={signOut}
            className="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium text-muted hover:bg-surface-2 hover:text-text"
          >
            <LogoutIcon /> Sign out
          </button>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        {/* Mobile top bar */}
        <header className="sticky top-0 z-10 flex items-center justify-between border-b border-border bg-surface/90 px-4 py-3 backdrop-blur md:hidden">
          <Link href="/">
            <Logo />
          </Link>
          <div className="flex items-center gap-1">
            <Link href="/assistant" aria-label="Help" className="rounded-full p-2 text-muted hover:bg-surface-2">
              <ChatIcon />
            </Link>
            <Link href="/insights" aria-label="Insights" className="rounded-full p-2 text-muted hover:bg-surface-2">
              <ChartIcon />
            </Link>
            <Link
              href="/notifications"
              aria-label={`Notifications${unread ? `, ${unread} unread` : ""}`}
              className="relative rounded-full p-2 text-muted hover:bg-surface-2"
            >
              <BellIcon />
              {unread > 0 && <span className="absolute right-1.5 top-1.5 size-2 rounded-full bg-accent" />}
            </Link>
            <button onClick={signOut} aria-label="Sign out" className="rounded-full p-2 text-muted hover:bg-surface-2">
              <LogoutIcon />
            </button>
          </div>
        </header>

        <main className="mx-auto w-full max-w-4xl flex-1 px-4 pb-28 pt-6 md:px-8 md:pb-12 md:pt-10">
          <Suspense fallback={<Loading />}>{children}</Suspense>
        </main>

        {/* Mobile bottom navigation */}
        <nav
          aria-label="Main"
          className="fixed inset-x-0 bottom-0 z-10 grid grid-cols-5 border-t border-border bg-surface pb-[env(safe-area-inset-bottom)] md:hidden"
        >
          <Suspense fallback={<BottomLinks pathname={null} />}>
            <CurrentPath>{(pathname) => <BottomLinks pathname={pathname} />}</CurrentPath>
          </Suspense>
        </nav>
      </div>
    </div>
  );
}

function CurrentPath({ children }: { children: (pathname: string) => ReactNode }) {
  return children(usePathname());
}

function SideLinks({ pathname, unread }: { pathname: string | null; unread: number }) {
  return NAV.map(({ href, label, icon: Icon }) => (
    <Link
      key={href}
      href={href}
      aria-current={isActive(pathname, href) ? "page" : undefined}
      className={cn(
        "flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-colors",
        isActive(pathname, href) ? "bg-brand-soft text-brand" : "text-muted hover:bg-surface-2 hover:text-text",
      )}
    >
      <Icon />
      <span className="flex-1">{label}</span>
      {href === "/notifications" && unread > 0 && (
        <span className="rounded-full bg-accent px-2 text-xs font-semibold text-black">{unread}</span>
      )}
    </Link>
  ));
}

function BottomLinks({ pathname }: { pathname: string | null }) {
  return NAV.filter((n) => n.mobile).map(({ href, label, icon: Icon }) => (
    <Link
      key={href}
      href={href}
      aria-current={isActive(pathname, href) ? "page" : undefined}
      className={cn(
        "flex flex-col items-center gap-1 py-2.5 text-[11px] font-medium",
        isActive(pathname, href) ? "text-brand" : "text-muted",
      )}
    >
      <Icon />
      {label}
    </Link>
  ));
}
