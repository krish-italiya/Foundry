import type { Deployment } from "../types/workspace";
import { shortId, timeLabel } from "../utils/format";
import StatusBadge from "./StatusBadge";

interface DeploymentListProps {
  deployments: Deployment[];
  selectedDeploymentId: string;
  loading: boolean;
  emptyTitle?: string;
  emptyDescription?: string;
  onSelect: (deployment: Deployment) => void;
}

export default function DeploymentList({
  deployments, selectedDeploymentId, loading, emptyTitle = "No deployments yet",
  emptyDescription = "Create a deployment to see release activity here.", onSelect
}: DeploymentListProps) {
  if (loading && deployments.length === 0) {
    return <div className="empty-state"><div className="loader" /><strong>Loading deployment history</strong></div>;
  }
  if (deployments.length === 0) {
    return <div className="empty-state"><div className="empty-illustration"><span>↗</span><i /><b /></div><strong>{emptyTitle}</strong><span>{emptyDescription}</span></div>;
  }
  return (
    <div className="deployment-list">
      {deployments.map((deployment) => (
        <button
          key={deployment.id}
          className={"deployment-row " + (deployment.id === selectedDeploymentId ? "deployment-row-selected" : "")}
          onClick={() => onSelect(deployment)}
        >
          <span className={"deployment-symbol deployment-symbol-" + deployment.status.toLowerCase().replace(/_/g, "-")}>{deployment.status === "SUCCEEDED" ? "✓" : deployment.status === "FAILED" ? "!" : "↗"}</span>
          <span className="deployment-main">
            <span className="deployment-title">Release <b>#{shortId(deployment.id)}</b></span>
            <span className="deployment-meta"><span>{deployment.sourceRef}</span>{deployment.servicePath && <><i>·</i><span>{deployment.servicePath}</span></>}<i>·</i>{timeLabel(deployment.createdAt)}</span>
          </span>
          <StatusBadge status={deployment.status} />
          <span className="row-chevron">›</span>
        </button>
      ))}
    </div>
  );
}