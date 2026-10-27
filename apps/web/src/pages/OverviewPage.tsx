import type { Deployment, Project } from "../types/workspace";
import { shortId, timeLabel } from "../utils/format";
import StatusBadge from "../components/StatusBadge";

interface OverviewPageProps {
  projects: Project[];
  selectedProject?: Project;
  deployments: Deployment[];
  loadingProjects: boolean;
  loadingDeployments: boolean;
  onAddProject: () => void;
  onOpenProject: (project: Project) => void;
  onViewProjects: () => void;
  onOpenDeployment: (deployment: Deployment) => void;
}

export default function OverviewPage({
  projects, selectedProject, deployments, loadingProjects, loadingDeployments,
  onAddProject, onOpenProject, onViewProjects, onOpenDeployment
}: OverviewPageProps) {
  const succeeded = deployments.filter((deployment) => deployment.status === "SUCCEEDED").length;
  const failed = deployments.filter((deployment) => deployment.status === "FAILED").length;
  const recent = deployments.slice(0, 5);

  return (
    <>
      <section className="page-intro overview-intro">
        <div>
          <div className="eyebrow"><span className="eyebrow-line" />WORKSPACE / OVERVIEW</div>
          <h1>Ship software.<br /><span>Know what’s running.</span></h1>
          <p className="page-subtitle">Your repositories, their releases, and runtime health—organized around the projects you own.</p>
        </div>
        <div className="intro-actions">
          <button className="button button-quiet" onClick={onAddProject}>＋ Add project</button>
          {selectedProject && <button className="button button-primary" onClick={() => onOpenProject(selectedProject)}>Open {selectedProject.name} <span>→</span></button>}
        </div>
      </section>

      <section className="stats-grid" aria-label="Workspace summary">
        <article className="stat-card"><div className="stat-top"><span className="stat-label">PROJECTS</span><span className="stat-icon stat-icon-violet">◇</span></div><div className="stat-value">{loadingProjects ? "—" : projects.length.toString().padStart(2, "0")}</div><div className="stat-foot"><span className="stat-foot-dot violet-dot" /> Repositories connected</div></article>
        <article className="stat-card"><div className="stat-top"><span className="stat-label">DEPLOYMENTS</span><span className="stat-icon stat-icon-blue">↗</span></div><div className="stat-value">{deployments.length.toString().padStart(2, "0")}</div><div className="stat-foot"><span className="stat-foot-dot blue-dot" /> {selectedProject ? selectedProject.name : "Select a project"}</div></article>
        <article className="stat-card"><div className="stat-top"><span className="stat-label">SUCCEEDED</span><span className="stat-icon stat-icon-green">✓</span></div><div className="stat-value">{succeeded.toString().padStart(2, "0")}</div><div className="stat-foot"><span className="stat-foot-dot green-dot" /> Health check passed</div></article>
        <article className="stat-card"><div className="stat-top"><span className="stat-label">FAILED</span><span className="stat-icon stat-icon-amber">!</span></div><div className="stat-value">{failed.toString().padStart(2, "0")}</div><div className="stat-foot"><span className="stat-foot-dot amber-dot" /> Needs attention</div></article>
      </section>

      <section className="overview-grid">
        <article className="surface-card">
          <div className="card-heading">
            <div><div className="section-kicker">RECENT ACTIVITY</div><h2>Recent deployments</h2><p>{selectedProject ? "Latest releases for " + selectedProject.name + "." : "Choose a project to see its recent releases."}</p></div>
            {selectedProject && <button className="text-action" onClick={() => onOpenProject(selectedProject)}>Open project workspace <span>→</span></button>}
          </div>
          <div className="recent-list">
            {!selectedProject ? <div className="empty-state"><strong>No project selected</strong><span>Open a project to see deployment activity.</span><button className="text-action" onClick={onViewProjects}>Browse projects <span>→</span></button></div>
            : loadingDeployments && deployments.length === 0 ? <div className="empty-state"><div className="loader" /><strong>Loading deployment history</strong></div>
            : recent.length === 0 ? <div className="empty-state"><div className="empty-illustration"><span>↗</span><i /><b /></div><strong>No deployments yet</strong><span>Create the first deployment inside this project's workspace.</span><button className="text-action" onClick={() => onOpenProject(selectedProject)}>Open project <span>→</span></button></div>
            : recent.map((deployment) => (
              <button className="recent-deployment-row" key={deployment.id} onClick={() => onOpenDeployment(deployment)}>
                <span className={"deployment-symbol deployment-symbol-" + deployment.status.toLowerCase().replace(/_/g, "-")}>{deployment.status === "SUCCEEDED" ? "✓" : deployment.status === "FAILED" ? "!" : "↗"}</span>
                <span className="deployment-main"><span className="deployment-title">Release <b>#{shortId(deployment.id)}</b></span><span className="deployment-meta"><span>{deployment.sourceRef}</span><i>·</i>{timeLabel(deployment.createdAt)}</span></span>
                <StatusBadge status={deployment.status} /><span className="row-chevron">›</span>
              </button>
            ))}
          </div>
          <div className="card-footer"><span className="mini-live-dot" /> Activity for selected project <span className="bottom-spacer" /><span className="muted-small">{deployments.length} releases</span></div>
        </article>

        <div className="overview-side-column">
          <article className="surface-card quickstart-card">
            <div className="section-kicker">YOUR PROJECTS</div><h2>Where do you want to go?</h2>
            <p>{projects.length === 0 ? "Connect a repository and start your first build." : selectedProject ? "Deployment history, build output, and runtime controls are grouped inside each project workspace." : "Open a project to view its releases and logs."}</p>
            <div className="quickstart-actions">
              <button className="button button-primary" onClick={onViewProjects}>{projects.length === 0 ? "Create project" : "Browse projects"} <span>→</span></button>
              {selectedProject && <button className="button button-quiet" onClick={() => onOpenProject(selectedProject)}>Open selected project</button>}
            </div>
          </article>
          <article className="surface-card pipeline-card">
            <div className="section-kicker">DEPLOYMENT FLOW</div><h2>From repo to runtime</h2>
            <div className="pipeline-steps">
              <div className="pipeline-step"><span>01</span><div><strong>Checkout &amp; build</strong><small>Resolve the project manifest and produce an artifact.</small></div></div>
              <div className="pipeline-step"><span>02</span><div><strong>Package</strong><small>Build an image using a supported runtime.</small></div></div>
              <div className="pipeline-step"><span>03</span><div><strong>Start &amp; verify</strong><small>Pass the application's health check.</small></div></div>
            </div>
          </article>
          {projects.length > 0 && (
            <article className="surface-card overview-projects-card">
              <div className="card-heading"><div><div className="section-kicker">WORKSPACE</div><h2>Projects</h2><p>Recently registered repositories.</p></div><button className="text-action" onClick={onViewProjects}>View all <span>→</span></button></div>
              {projects.slice(0, 3).map((project, index) => (
                <button className="project-row" key={project.id} onClick={() => onOpenProject(project)}>
                  <span className={"repo-avatar repo-avatar-" + (index % 4)}>{project.name.slice(0, 1).toUpperCase()}</span>
                  <span className="project-row-main"><strong>{project.name}</strong><span>{project.repositoryUrl.replace(/^https?:\/\/(www\.)?/, "")}</span><span className={"project-trust-label " + (project.buildTrusted ? "project-trust-enabled" : "project-trust-disabled")}>{project.buildTrusted ? "Build trust enabled" : "Build trust off"}</span></span>
                  <span className="row-chevron">›</span>
                </button>
              ))}
            </article>
          )}
        </div>
      </section>
    </>
  );
}