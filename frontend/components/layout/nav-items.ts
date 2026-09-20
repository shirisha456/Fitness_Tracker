import {
  Activity,
  Dumbbell,
  LayoutDashboard,
  Salad,
  Sparkles,
  TrendingUp,
  User,
  type LucideIcon,
} from "lucide-react";

export type NavItem = {
  label: string;
  href: string;
  icon: LucideIcon;
  enabled: boolean;
};

export const navItems: NavItem[] = [
  { label: "Dashboard", href: "/dashboard", icon: LayoutDashboard, enabled: true },
  { label: "Workouts", href: "/workouts", icon: Dumbbell, enabled: true },
  { label: "Nutrition", href: "/nutrition", icon: Salad, enabled: true },
  { label: "Training", href: "/training", icon: Activity, enabled: true },
  { label: "Progress", href: "/progress", icon: TrendingUp, enabled: true },
  { label: "AI Coach", href: "/ai", icon: Sparkles, enabled: true },
  { label: "Profile", href: "/profile", icon: User, enabled: true },
];
