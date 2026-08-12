import { Dumbbell, Salad, Sparkles, TrendingUp } from "lucide-react";
import Link from "next/link";

import { Button } from "@/components/ui/button";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

const features = [
  { icon: Dumbbell, title: "Workouts", description: "Log workouts and track your exercise history." },
  { icon: Salad, title: "Nutrition", description: "Track meals, macros, and water intake." },
  { icon: TrendingUp, title: "Progress", description: "Chart your weight and body measurements over time." },
  { icon: Sparkles, title: "AI Coach", description: "Get generated workouts, meal ideas, and a coach to chat with." },
];

export default function HomePage() {
  return (
    <main className="flex min-h-screen flex-col bg-background">
      <header className="flex items-center justify-between px-6 py-4">
        <span className="text-lg font-bold tracking-tight">Fitness Tracker</span>
        <div className="flex items-center gap-2">
          <Button asChild variant="ghost">
            <Link href="/login">Log in</Link>
          </Button>
          <Button asChild>
            <Link href="/register">Sign up</Link>
          </Button>
        </div>
      </header>

      <div className="flex flex-1 flex-col items-center justify-center px-6 py-16">
        <div className="max-w-xl text-center">
          <p className="mb-3 text-sm font-medium uppercase tracking-widest text-muted-foreground">
            AI-Powered Fitness Platform
          </p>
          <h1 className="text-5xl font-bold tracking-tight text-foreground sm:text-6xl">
            Fitness Tracker
          </h1>
          <p className="mt-6 text-lg text-muted-foreground">
            Train smarter with personalized workouts, nutrition tracking, and intelligent
            coaching.
          </p>
          <div className="mt-8 flex justify-center gap-3">
            <Button asChild size="lg">
              <Link href="/register">Get started</Link>
            </Button>
            <Button asChild size="lg" variant="outline">
              <Link href="/login">Log in</Link>
            </Button>
          </div>
        </div>

        <div className="mt-16 grid w-full max-w-3xl gap-4 sm:grid-cols-2">
          {features.map((feature) => (
            <Card key={feature.title}>
              <CardHeader>
                <feature.icon className="mb-2 h-6 w-6 text-muted-foreground" />
                <CardTitle className="text-base">{feature.title}</CardTitle>
                <CardDescription>{feature.description}</CardDescription>
              </CardHeader>
            </Card>
          ))}
        </div>
      </div>
    </main>
  );
}
