import type { DeploymentStatus, RuntimeState } from "../types/workspace";

export function timeLabel(value: string | null | undefined): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString([], {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit"
  });
}

export function shortId(value: string): string {
  return value.slice(0, 8);
}

export function statusLabel(status: DeploymentStatus): string {
  return status.replace(/_/g, " ").toLowerCase();
}

export function runtimeStateLabel(state: RuntimeState): string {
  return state.replace(/_/g, " ").toLowerCase();
}

export function repositoryLabel(repositoryUrl: string): string {
  return repositoryUrl.replace(/^https?:\/\/(www\.)?/, "").replace(/\/$/, "");
}