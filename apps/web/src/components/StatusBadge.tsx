import type { DeploymentStatus } from "../types/workspace";
import { statusLabel } from "../utils/format";

export default function StatusBadge({ status }: { status: DeploymentStatus }) {
  return (
    <span className={"status-badge status-" + status.toLowerCase().replace(/_/g, "-")}>
      <span className="status-dot" />
      {statusLabel(status)}
    </span>
  );
}