import type { Deployment, LogEntry } from "../types/workspace";
import { runtimeStateLabel, shortId, timeLabel } from "../utils/format";
import StatusBadge from "../components/StatusBadge";
import BuildPipeline from "../components/BuildPipeline";
import TerminalLog from "../components/TerminalLog";
import EmptyState from "../components/EmptyState";

interface BuildRuntimeTabProps {
  deployment?: Deployment;
  logs: LogEntry[];
  logsHasMore: boolean;
  loadingLogs: boolean;
  sequence: number;
  onChooseDeployment: () => void;
}

export default function BuildRuntimeTab({
  deployment, logs, logsHasMore, loadingLogs, sequence, onChooseDeployment
}: BuildRuntimeTabProps) {
  if (!deployment) {
    return <EmptyState icon="⌁" title="Select a release to inspect" description="Choose a deployment from this project's history to inspect build stages, health checks, application URL, and runtime logs." actionLabel="Open deployments" onAction={onChooseDeployment} />;
  }

  return (
    <div className="project-tab-content">
      <section className="runtime-summary-grid">
        <article className="surface-card runtime-summary-main">
          <div className="runtime-summary-top"><div><div className="section-kicker">SELECTED RELEASE</div><h2>Release <span className="release-id">#{shortId(deployment.id)}</span></h2><p>{deployment.sourceRef}{deployment.servicePath ? " · " + deployment.servicePath : " · repository root"}</p></div><StatusBadge status={deployment.status} /></div>
          <div className="deployment-detail-strip">
            <div className="detail-item"><span>CREATED</span><strong>{timeLabel(deployment.createdAt)}</strong></div>
            <div className="detail-item"><span>STARTED</span><strong>{timeLabel(deployment.startedAt)}</strong></div>
            <div className="detail-item"><span>COMPLETED</span><strong>{timeLabel(deployment.completedAt)}</strong></div>
            <div className={"detail-item runtime-state-item runtime-state-" + deployment.runtimeState.toLowerCase().replace(/_/g, "-")}><span>RUNTIME</span><strong>{runtimeStateLabel(deployment.runtimeState)}</strong></div>
          </div>
          {deployment.failureReason && <div className="failure-reason"><strong>Failure reason</strong><span>{deployment.failureReason}</span></div>}
          {deployment.status === "QUEUED" && <div className="queue-note"><span className="queue-note-icon">i</span><span><strong>Deployment is queued.</strong> Foundry will execute repository builds only for trusted projects or administrator-allowlisted repositories.</span></div>}
        </article>
        <article className="surface-card application-card">
          <div className="section-kicker">LIVE APPLICATION</div>
          <h2>{deployment.runtimeState === "RUNNING" ? "Your app is running" : deployment.status === "FAILED" ? "Deployment needs attention" : "Waiting for a live URL"}</h2>
          <p>{deployment.runtimeState === "RUNNING" ? "Available through Foundry's local preview router." : "A URL is available after a supported application starts and passes its health check."}</p>
          {deployment.applicationUrl
            ? <a className="application-link" href={deployment.applicationUrl} target="_blank" rel="noreferrer"><span>{deployment.applicationUrl}</span><span>↗</span></a>
            : <div className="application-link-disabled">URL not available yet</div>}
          {deployment.applicationUrl && <a className="button button-primary full-width application-open-button" href={deployment.applicationUrl} target="_blank" rel="noreferrer">Open application <span>↗</span></a>}
        </article>
      </section>
      <BuildPipeline deployment={deployment} logs={logs} />
      <TerminalLog logs={logs} loading={loadingLogs} hasMore={logsHasMore} sequence={sequence} deploymentId={deployment.id} />
    </div>
  );
}