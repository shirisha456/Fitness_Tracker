import { authedBackendFetch, toNextResponse } from "@/lib/auth/authedFetch";

export async function GET(request: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const { search } = new URL(request.url);
  const result = await authedBackendFetch(`/v1/training/exercises/${id}/insights${search}`);
  return toNextResponse(result);
}
