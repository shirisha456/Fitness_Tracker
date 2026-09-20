/** Every recommendation shows the facts it was computed from. This is the point of
 * the feature — a user should never have to trust an unexplained verdict. */
export function EvidenceList({ evidence }: { evidence: string[] }) {
  if (evidence.length === 0) return null;
  return (
    <div className="text-sm">
      <p className="font-medium">Based on:</p>
      <ul className="mt-1 list-outside list-disc space-y-1 pl-5 text-muted-foreground">
        {evidence.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </div>
  );
}
