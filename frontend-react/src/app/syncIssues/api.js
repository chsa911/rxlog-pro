import { apiFetch } from "../../lib/apiFetch";

export async function listIssues(status = "open", limit = 50) {
  const params = new URLSearchParams();
  params.set("status", status);
  params.set("limit", String(limit));

  const res = await apiFetch(`/api/mobile/issues?${params.toString()}`);
  if (!res.ok) throw new Error(`listIssues failed: ${res.status}`);
  return res.json();
}

export async function getSuggestions(issueId, limit = 200) {
  const params = new URLSearchParams();
  params.set("limit", String(limit));

  const res = await apiFetch(`/api/mobile/issues/${issueId}/suggestions?${params.toString()}`);
  if (!res.ok) throw new Error(`getSuggestions failed: ${res.status}`);
  return res.json();
}

export async function resolveIssue(issueId, bookId) {
  const res = await apiFetch(`/api/mobile/issues/${issueId}/resolve`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ bookId }),
  });
  if (!res.ok) throw new Error(`resolveIssue failed: ${res.status}`);
}

export async function ignoreIssue(issueId) {
  const res = await apiFetch(`/api/mobile/issues/${issueId}/ignore`, { method: "POST" });
  if (!res.ok) throw new Error(`ignoreIssue failed: ${res.status}`);
}

export async function retryIssue(issueId) {
  const res = await apiFetch(`/api/mobile/issues/${issueId}/retry`, { method: "POST" });
  if (!res.ok) throw new Error(`retryIssue failed: ${res.status}`);
}