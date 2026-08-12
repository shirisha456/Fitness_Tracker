"use client";

import { LogOut, Menu } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { type ReactNode, useState } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Sheet, SheetContent, SheetTitle } from "@/components/ui/sheet";
import { navItems } from "@/components/layout/nav-items";
import { useAuth } from "@/lib/auth/AuthContext";
import { cn } from "@/lib/utils";

function NavList({ pathname, onNavigate }: { pathname: string; onNavigate?: () => void }) {
  return (
    <nav className="flex flex-col gap-1">
      {navItems.map((item) => {
        const isActive = pathname === item.href;
        if (!item.enabled) {
          return (
            <div
              key={item.href}
              className="flex cursor-not-allowed items-center justify-between rounded-md px-3 py-2 text-sm text-muted-foreground/60"
            >
              <span className="flex items-center gap-3">
                <item.icon className="h-4 w-4" />
                {item.label}
              </span>
              <Badge variant="secondary" className="text-[10px]">
                Soon
              </Badge>
            </div>
          );
        }
        return (
          <Link
            key={item.href}
            href={item.href}
            onClick={onNavigate}
            className={cn(
              "flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors",
              isActive
                ? "bg-primary/10 text-primary"
                : "text-foreground hover:bg-accent hover:text-accent-foreground",
            )}
          >
            <item.icon className="h-4 w-4" />
            {item.label}
          </Link>
        );
      })}
    </nav>
  );
}

function UserFooter() {
  const { user, logout } = useAuth();
  return (
    <div className="flex items-center justify-between gap-2 border-t border-border pt-4">
      <span className="truncate text-sm text-muted-foreground">{user?.email}</span>
      <Button variant="ghost" size="icon" aria-label="Log out" onClick={() => logout()}>
        <LogOut className="h-4 w-4" />
      </Button>
    </div>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  return (
    <div className="flex min-h-screen">
      <aside className="hidden md:flex md:w-64 md:flex-col md:border-r md:border-border md:p-4">
        <div className="mb-6 px-2 text-lg font-bold tracking-tight">Fitness Tracker</div>
        <div className="flex-1">
          <NavList pathname={pathname} />
        </div>
        <UserFooter />
      </aside>

      <div className="flex flex-1 flex-col">
        <header className="flex items-center justify-between border-b border-border px-4 py-3 md:hidden">
          <span className="text-lg font-bold tracking-tight">Fitness Tracker</span>
          <Button
            variant="ghost"
            size="icon"
            aria-label="Open navigation"
            onClick={() => setMobileNavOpen(true)}
          >
            <Menu className="h-5 w-5" />
          </Button>
        </header>

        <Sheet open={mobileNavOpen} onOpenChange={setMobileNavOpen}>
          <SheetContent side="left" className="flex w-64 flex-col p-4">
            <SheetTitle className="mb-6 px-2 text-lg font-bold tracking-tight">
              Fitness Tracker
            </SheetTitle>
            <div className="flex-1">
              <NavList pathname={pathname} onNavigate={() => setMobileNavOpen(false)} />
            </div>
            <UserFooter />
          </SheetContent>
        </Sheet>

        <main className="flex-1 p-6">{children}</main>
      </div>
    </div>
  );
}
