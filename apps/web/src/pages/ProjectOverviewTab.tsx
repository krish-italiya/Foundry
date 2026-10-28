import type { Deployment, Project } from "../types/workspace";
import { timeLabel } from "../utils/format";
import DeploymentList from "../components/DeploymentList";

interface ProjectOverviewTabProps {
  project: Project;
  deployments: Deployment[];
  loadingDeployments: boolean;
  updatingBuildTrust: boolean;
  onToggleBuildTrust: (project: Project) => Promise<boolean>;
  onOpenDeployment: (deployment: Deployment) => void;
  onGoToDeployments: () => void;
}

export default function ProjectOverviewTab({
  project, deployments, loadingDeployments, updatingBuildTrust, onToggleBuildTrust, onOpenDeployment, onGoToDeployments
}: ProjectOverviewTabProps) {
  const latest = deployments.slice(0, 3);
  return (
    <div className="project-tab-content">
      <section className="project-detail-hero surface-card">
        <div className="project-detail-hero-main">
          <span className="repo-avatar repo-avatar-0 project-detail-avatar">{project.name.slice(0, 1).toUpperCase()}</span>
          <div><div className="section-kicker">PROJECT OVERVIEW</div><h2>{project.name}</h2><p>Registered {timeLabel(project.createdAt)}</p></div>
        </div>
        <a className="repository-link" href={project.repositoryUrl} target="_blank" rel="noreferrer">{project.repositoryUrl}<span>↗</span></a>
      </section>

      <section className={"trust-panel trust-panel-wide " + (project.buildTrusted ? "trust-panel-enabled" : "trust-panel-disabled")}>
        <div className="trust-panel-icon">{project.buildTrusted ? "✓" : "!"}</div>
        <div><strong>{project.buildTrusted ? "Repository trusted" : "Build trust required"}</strong><p>{project.buildTrusted ? "Foundry may execute this repository’s build and start scripts on this computer. Only keep trust enabled for repositories you control or have reviewed." : "Registration does not authorize code execution. Enable trust after reviewing the repository, or use your administrator's configured allowlist."}</p></div>
        <button type="button" className={"button " + (project.buildTrusted ? "button-quiet" : "button-primary")} onClick={() => void onToggleBuildTrust(project)} disabled={updatingBuildTrust}>{updatingBuildTrust ? "Saving…" : project.buildTrusted ? "Revoke trust" : "Trust repository"}</button>
      </section>

      <section className="stats-grid project-stats-grid">
        <article className="stat-card"><div className="stat-top"><span className="stat-label">RELEASES</span><span className="stat-icon stat-icon-blue">↗</span></div><div className="stat-value">{deployments.length.toString().padStart(2,"0")}</div><div className="stat-foot"><span className="stat-foot-dot blue-dot" /> Recorded for this project</div></article>
        <article className="stat-card"><div className="stat-top"><span className="stat-label">SUCCEEDED</span><span className="stat-icon stat-icon-green">✓</span></div><div className="stat-value">{deployments.filter((deployment) => deployment.status === "SUCCEEDED").length.toString().padStart(2,"0")}</div><div className="stat-foot"><span className="stat-foot-dot green-dot" /> Health check passed</div></article>
        <article className="stat-card"><div className="stat-top"><span className="stat-label">FAILED</span><span className="stat-icon stat-icon-amber">!</span></div><div className="stat-value">{deployments.filter((deployment) => deployment.status === "FAILED").length.toString().padStart(2,"0")}</div><div className="stat-foot"><span className="stat-foot-dot amber-dot" /> Needs attention</div></article>
      </section>

      <section className="surface-card">
        <div className="card-heading"><div><div className="section-kicker">RECENT ACTIVITY</div><h2>Recent deployments</h2><p>The latest releases for this project.</p></div><button className="text-action" onClick={onGoToDeployments}>All deployments <span>→</span></button></div>
        {latest.length === 0 && !loadingDeployments
          ? <div className="empty-state"><div className="empty-illustration"><span>↗</span><i /><b /></div><strong>No deployments yet</strong><span>Create your first release from the Deployments tab.</span><button className="text-action" onClick={onGoToDeployments}>Create deployment <span>→</span></button></div>
          : <DeploymentList deployments={latest} selectedDeploymentId="" loading={loadingDeployments} onSelect={onOpenDeployment} />}
        <div className="card-footer"><span className="mini-live-dot" /> Auto-refresh every 5 seconds <span className="bottom-spacer" /><span className="muted-small">{deployments.length} total releases</span></div>
      </section>
    </div>
  );
}