import { Badge } from "@/components/ui/badge";
import { CLASSIFICATION_LABELS, type Classification } from "@/lib/training";

const VARIANTS: Record<Classification, "default" | "secondary" | "destructive" | "outline"> = {
  progressing: "default",
  stable: "secondary",
  possible_plateau: "destructive",
  declining: "destructive",
  insufficient_data: "outline",
  not_applicable: "outline",
};

export function ClassificationBadge({ classification }: { classification: Classification }) {
  return (
    <Badge variant={VARIANTS[classification]}>{CLASSIFICATION_LABELS[classification]}</Badge>
  );
}
