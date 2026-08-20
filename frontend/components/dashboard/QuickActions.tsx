import { Dumbbell, Salad, UserCog } from "lucide-react";
import Link from "next/link";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

const actions = [
  { label: "Log a workout", href: "/workouts/new", icon: Dumbbell },
  { label: "Log a meal", href: "/nutrition/meals/new", icon: Salad },
  { label: "Update profile", href: "/profile", icon: UserCog },
];

export function QuickActions() {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Quick actions</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-wrap gap-3">
        {actions.map((action) => (
          <Button key={action.href} asChild variant="outline" className="gap-2">
            <Link href={action.href}>
              <action.icon className="h-4 w-4" />
              {action.label}
            </Link>
          </Button>
        ))}
      </CardContent>
    </Card>
  );
}
