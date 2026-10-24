import { useEffect, useRef, useState, type FormEvent } from "react";

type Project = {
  id: string;
  name: string;
  repositoryUrl: string;
  createdAt: string;
  buildTrusted: boolean;
};

type DeploymentStatus =
  | "QUEUED"
  | "BUILDING"
  | "STARTING"
  | "HEALTH_CHECK"
  | "SUCCEEDED"
  | "FAILED";

type Deployment = {
  id: string;
  projectId: string;
  sourceRepositoryUrl: string;
  sourceRef: string;
  servicePath: string | null;
  status: DeploymentStatus;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  failureReason: string | null;
  applicationUrl: string | null;
};

type LogEntry = {
  id: string;
  deploymentId: string;
  sequenceNumber: number;
  timestamp: string;
  stage: string;
  stream: string;
  severity: string;
  message: string;
};

type DeploymentLogs = {
  deploymentId: string;
  entries: LogEntry[];
  nextAfterSequence: number;
  hasMore: boolean;
};

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");

async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(API_BASE_URL + path, { ...init, headers });
  if (!response.ok) {
    let detail = response.statusText || "Request failed";
    try {
      const body: unknown = await response.json();
      if (body && typeof body === "object") {
        const record = body as Record<string, unknown>;
        detail = String(record.message ?? record.detail ?? detail);
      }
    } catch {
      // Keep the HTTP status text when the response is not JSON.
    }
    throw new Error(detail + " (HTTP " + response.status + ")");
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

function timeLabel(value: string | null | undefined): string {
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

function shortId(value: string): string {
  return value.slice(0, 8);
}

function statusLabel(status: DeploymentStatus): string {
  return status.replace("_", " ").toLowerCase();
}

function StatusBadge({ status }: { status: DeploymentStatus }) {
  return (
    <span className={"status-badge status-" + status.toLowerCase().replace("_", "-")}>
      <span className="status-dot" />
      {statusLabel(status)}
    </span>
  );
}

function Mark() {
  return (
    <svg viewBox="0 0 42 42" aria-hidden="true" className="brand-mark">
      <path d="M21 2.5 39 12.7v16.6L21 39.5 3 29.3V12.7L21 2.5Z" fill="none" stroke="currentColor" strokeWidth="2.5" />
      <path d="m21 11 9.5 5.5v9L21 31l-9.5-5.5v-9L21 11Z" fill="currentColor" opacity=".22" />
      <path d="m21 11 9.5 5.5L21 22l-9.5-5.5L21 11Zm-9.5 5.5V25l9.5 6V22l-9.5-5.5Zm19 0V25L21 31V22l9.5-5.5Z" fill="currentColor" />
    </svg>
  );
}

function App() {
  const [projects, setProjects] = useState<Project[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState("");
  const [deployments, setDeployments] = useState<Deployment[]>([]);
  const [selectedDeploymentId, setSelectedDeploymentId] = useState("");
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [logsHasMore, setLogsHasMore] = useState(false);
  const [apiState, setApiState] = useState<"checking" | "online" | "offline">("checking");
  const [loadingProjects, setLoadingProjects] = useState(true);
  const [loadingDeployments, setLoadingDeployments] = useState(false);
  const [loadingLogs, setLoadingLogs] = useState(false);
  const [projectFormOpen, setProjectFormOpen] = useState(false);
  const [projectName, setProjectName] = useState("");
  const [repositoryUrl, setRepositoryUrl] = useState("");
  const [buildTrustedOnCreate, setBuildTrustedOnCreate] = useState(false);
  const [updatingBuildTrust, setUpdatingBuildTrust] = useState(false);
  const [sourceRef, setSourceRef] = useState("main");
  const [servicePath, setServicePath] = useState("");
  const [creatingProject, setCreatingProject] = useState(false);
  const [creatingDeployment, setCreatingDeployment] = useState(false);
  const [pageError, setPageError] = useState("");
  const [notice, setNotice] = useState("");
  const lastLogSequence = useRef(0);

  const selectedProject = projects.find((project) => project.id === selectedProjectId);
  const selectedDeployment = deployments.find((deployment) => deployment.id === selectedDeploymentId);
  const succeededCount = deployments.filter((item) => item.status === "SUCCEEDED").length;
  const failedCount = deployments.filter((item) => item.status === "FAILED").length;

  useEffect(() => {
    let cancelled = false;

    const load = async () => {
      try {
        const [projectItems] = await Promise.all([
          api<Project[]>("/api/v1/projects"),
          api<{ status: string }>("/actuator/health")
        ]);
        if (cancelled) return;
        setProjects(projectItems);
        setApiState("online");
        setSelectedProjectId((current) => {
          if (current && projectItems.some((project) => project.id === current)) return current;
          return projectItems[0]?.id ?? "";
        });
      } catch (error) {
        if (cancelled) return;
        setApiState("offline");
        setPageError(error instanceof Error ? error.message : "Could not connect to Foundry API.");
      } finally {
        if (!cancelled) setLoadingProjects(false);
      }
    };

    void load();
    const interval = window.setInterval(() => void load(), 15000);
    return () => {
      cancelled = true;
      window.clearInterval(interval);
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    if (!selectedProjectId) {
      setDeployments([]);
      setSelectedDeploymentId("");
      setLoadingDeployments(false);
      return;
    }

    const load = async () => {
      try {
        setLoadingDeployments(true);
        const items = await api<Deployment[]>(
          "/api/v1/projects/" + encodeURIComponent(selectedProjectId) + "/deployments"
        );
        if (cancelled) return;
        setDeployments(items);
        setApiState("online");
        setSelectedDeploymentId((current) =>
          current && items.some((deployment) => deployment.id === current)
            ? current
            : items[0]?.id ?? ""
        );
      } catch (error) {
        if (cancelled) return;
        setPageError(error instanceof Error ? error.message : "Could not load deployments.");
      } finally {
        if (!cancelled) setLoadingDeployments(false);
      }
    };

    void load();
    const interval = window.setInterval(() => void load(), 5000);
    return () => {
      cancelled = true;
      window.clearInterval(interval);
    };
  }, [selectedProjectId]);

  useEffect(() => {
    let cancelled = false;
    let inFlight = false;
    lastLogSequence.current = 0;
    setLogs([]);
    setLogsHasMore(false);

    if (!selectedDeploymentId) {
      setLoadingLogs(false);
      return;
    }

    const load = async () => {
      if (inFlight) return;
      inFlight = true;
      const afterSequence = lastLogSequence.current;
      try {
        setLoadingLogs(true);
        const page = await api<DeploymentLogs>(
          "/api/v1/deployments/" + encodeURIComponent(selectedDeploymentId) +
          "/logs?afterSequence=" + afterSequence + "&limit=100"
        );
        if (cancelled) return;
        setLogs((current) => afterSequence === 0 ? page.entries : [...current, ...page.entries]);
        lastLogSequence.current = page.nextAfterSequence;
        setLogsHasMore(page.hasMore);
      } catch (error) {
        if (!cancelled) {
          setPageError(error instanceof Error ? error.message : "Could not load deployment logs.");
        }
      } finally {
        inFlight = false;
        if (!cancelled) setLoadingLogs(false);
      }
    };

    void load();
    const interval = window.setInterval(() => void load(), 5000);
    return () => {
      cancelled = true;
      window.clearInterval(interval);
    };
  }, [selectedDeploymentId]);

  async function refreshProjects() {
    setPageError("");
    try {
      const items = await api<Project[]>("/api/v1/projects");
      setProjects(items);
      setApiState("online");
      if (!items.some((project) => project.id === selectedProjectId)) {
        setSelectedProjectId(items[0]?.id ?? "");
      }
    } catch (error) {
      setApiState("offline");
      setPageError(error instanceof Error ? error.message : "Could not refresh projects.");
    }
  }

  async function handleCreateProject(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPageError("");
    setNotice("");
    setCreatingProject(true);
    try {
      const created = await api<Project>("/api/v1/projects", {
        method: "POST",
        body: JSON.stringify({
          name: projectName.trim(),
          repositoryUrl: repositoryUrl.trim(),
          buildTrusted: buildTrustedOnCreate
        })
      });
      setProjects((current) => [created, ...current.filter((project) => project.id !== created.id)]);
      setSelectedProjectId(created.id);
      setProjectName("");
      setRepositoryUrl("");
      setBuildTrustedOnCreate(false);
      setProjectFormOpen(false);
      setNotice(created.buildTrusted
        ? "Project registered and trusted for local builds."
        : "Project registered. Enable Build trust before running repository build scripts.");
      setApiState("online");
    } catch (error) {
      setPageError(error instanceof Error ? error.message : "Could not register the project.");
    } finally {
      setCreatingProject(false);
    }
  }

  async function handleToggleBuildTrust() {
    if (!selectedProject || updatingBuildTrust) return;

    const buildTrusted = !selectedProject.buildTrusted;
    if (buildTrusted && !window.confirm(
      "Trust this repository for local builds? Foundry will execute repository-defined build and start scripts on this computer. Continue only if you trust this code."
    )) {
      return;
    }

    setPageError("");
    setNotice("");
    setUpdatingBuildTrust(true);
    try {
      const updated = await api<Project>(
        "/api/v1/projects/" + encodeURIComponent(selectedProject.id) + "/build-trust",
        {
          method: "PATCH",
          body: JSON.stringify({ buildTrusted })
        }
      );
      setProjects((current) => current.map((project) => project.id === updated.id ? updated : project));
      setNotice(buildTrusted
        ? "Build trust enabled for " + updated.name + ". You no longer need to add this project to the administrator allowlist."
        : "Build trust revoked for " + updated.name + ".");
    } catch (error) {
      setPageError(error instanceof Error ? error.message : "Could not update repository build trust.");
    } finally {
      setUpdatingBuildTrust(false);
    }
  }

  async function handleCreateDeployment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedProjectId) return;
    setPageError("");
    setNotice("");
    setCreatingDeployment(true);
    try {
      const created = await api<Deployment>(
        "/api/v1/projects/" + encodeURIComponent(selectedProjectId) + "/deployments",
        {
          method: "POST",
          body: JSON.stringify({ sourceRef: sourceRef.trim(), servicePath: servicePath.trim() || null })
        }
      );
      setDeployments((current) => [created, ...current.filter((deployment) => deployment.id !== created.id)]);
      setSelectedDeploymentId(created.id);
      setNotice("Deployment request accepted. Status will refresh automatically.");
      setApiState("online");
    } catch (error) {
      setPageError(error instanceof Error ? error.message : "Could not request deployment.");
    } finally {
      setCreatingDeployment(false);
    }
  }

  function scrollTo(id: string) {
    document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand-lockup">
          <Mark />
          <div>
            <div className="brand-name">foundry<span>.</span></div>
            <div className="brand-caption">DEPLOYMENT PLATFORM</div>
          </div>
        </div>

        <div className="side-label">WORKSPACE</div>
        <nav className="side-nav" aria-label="Workspace navigation">
          <button className="nav-item active" onClick={() => scrollTo("overview")}>
            <span className="nav-glyph">◫</span> Overview
          </button>
          <button className="nav-item" onClick={() => scrollTo("projects")}>
            <span className="nav-glyph">◇</span> Projects
            <span className="nav-count">{projects.length}</span>
          </button>
          <button className="nav-item" onClick={() => scrollTo("deployments")}>
            <span className="nav-glyph">↗</span> Deployments
            <span className="nav-count">{deployments.length}</span>
          </button>
        </nav>

        <div className="sidebar-spacer" />

        <div className="runtime-card">
          <div className="runtime-card-top">
            <span className="runtime-icon">⌘</span>
            <span>LOCAL ENVIRONMENT</span>
          </div>
          <div className="runtime-title">Control plane</div>
          <div className="runtime-status">
            <span className={"pulse-dot " + (apiState === "online" ? "pulse-green" : apiState === "offline" ? "pulse-red" : "")} />
            {apiState === "online" ? "API connected" : apiState === "offline" ? "API unavailable" : "Connecting to API"}
          </div>
          <div className="runtime-foot">localhost:8080</div>
        </div>

        <div className="sidebar-footer">
          <div className="avatar">F</div>
          <div className="footer-user">
            <strong>Developer workspace</strong>
            <span>Local development</span>
          </div>
          <span className="footer-dots">•••</span>
        </div>
      </aside>

      <main className="main-panel" id="overview">
        <header className="topbar">
          <div className="breadcrumbs"><span>Workspace</span><span className="crumb-divider">/</span><strong>Overview</strong></div>
          <div className="topbar-right">
            <span className={"api-pill " + (apiState === "online" ? "api-pill-online" : apiState === "offline" ? "api-pill-offline" : "")}>
              <span className="pulse-dot" />
              {apiState === "online" ? "All systems operational" : apiState === "offline" ? "API disconnected" : "Connecting"}
            </span>
            <button className="icon-button" onClick={() => void refreshProjects()} aria-label="Refresh projects" title="Refresh">
              ↻
            </button>
          </div>
        </header>

        <div className="page-content">
          <section className="welcome-row">
            <div>
              <div className="eyebrow"><span className="eyebrow-line" /> DEVELOPER PLATFORM <span className="eyebrow-dot">•</span> V1 PREVIEW</div>
              <h1>Your deployment <span>workspace.</span></h1>
              <p className="page-subtitle">Ship with confidence. Keep every project, release, and runtime signal in one place.</p>
            </div>
            <div className="welcome-decoration" aria-hidden="true">
              <div className="orbit orbit-one" />
              <div className="orbit orbit-two" />
              <div className="orbit-core"><Mark /></div>
            </div>
          </section>

          {pageError && (
            <div className="alert alert-error" role="alert">
              <span className="alert-symbol">!</span>
              <div><strong>Something needs attention</strong><p>{pageError}</p></div>
              <button className="alert-close" onClick={() => setPageError("")} aria-label="Dismiss error">×</button>
            </div>
          )}
          {notice && (
            <div className="alert alert-success" role="status">
              <span className="alert-symbol">✓</span>
              <p>{notice}</p>
              <button className="alert-close" onClick={() => setNotice("")} aria-label="Dismiss message">×</button>
            </div>
          )}

          <section className="stats-grid" aria-label="Workspace summary">
            <article className="stat-card">
              <div className="stat-top"><span className="stat-label">REGISTERED PROJECTS</span><span className="stat-icon stat-icon-violet">◇</span></div>
              <div className="stat-value">{loadingProjects ? "—" : projects.length.toString().padStart(2, "0")}</div>
              <div className="stat-foot"><span className="stat-foot-dot violet-dot" /> Source repositories connected</div>
            </article>
            <article className="stat-card">
              <div className="stat-top"><span className="stat-label">PROJECT DEPLOYMENTS</span><span className="stat-icon stat-icon-blue">↗</span></div>
              <div className="stat-value">{deployments.length.toString().padStart(2, "0")}</div>
              <div className="stat-foot"><span className="stat-foot-dot blue-dot" /> {selectedProject ? "For " + selectedProject.name : "Select a project to view history"}</div>
            </article>
            <article className="stat-card">
              <div className="stat-top"><span className="stat-label">SUCCEEDED</span><span className="stat-icon stat-icon-green">✓</span></div>
              <div className="stat-value">{succeededCount.toString().padStart(2, "0")}</div>
              <div className="stat-foot"><span className="stat-foot-dot green-dot" /> Health check passed</div>
            </article>
            <article className="stat-card">
              <div className="stat-top"><span className="stat-label">FAILED</span><span className="stat-icon stat-icon-amber">!</span></div>
              <div className="stat-value">{failedCount.toString().padStart(2, "0")}</div>
              <div className="stat-foot"><span className="stat-foot-dot amber-dot" /> Needs investigation</div>
            </article>
          </section>

          <section className="content-grid">
            <article className="panel projects-panel" id="projects">
              <div className="panel-heading">
                <div>
                  <div className="section-kicker">YOUR WORKSPACE</div>
                  <h2>Projects <span className="heading-count">{projects.length}</span></h2>
                  <p>Connect a repository to start a deployment.</p>
                </div>
                <button className="button button-primary button-small" onClick={() => setProjectFormOpen((open) => !open)}>
                  <span>＋</span> New project
                </button>
              </div>

              {projectFormOpen && (
                <form className="project-form" onSubmit={handleCreateProject}>
                  <div className="form-title">Register a project</div>
                  <label>
                    Project name
                    <input value={projectName} onChange={(event) => setProjectName(event.target.value)} placeholder="e.g. storefront-api" maxLength={80} required />
                  </label>
                  <label>
                    Repository URL
                    <input type="url" value={repositoryUrl} onChange={(event) => setRepositoryUrl(event.target.value)} placeholder="https://github.com/org/repository" maxLength={2048} required />
                  </label>
                  <label className="trust-consent">
                    <input
                      className="trust-consent-input"
                      type="checkbox"
                      checked={buildTrustedOnCreate}
                      onChange={(event) => setBuildTrustedOnCreate(event.target.checked)}
                    />
                    <span className="trust-consent-copy">
                      <strong>Trust this repository for local builds</strong>
                      <small>Foundry may run dependency install, build, test and start scripts from this repository on this computer. Enable only for code you trust.</small>
                    </span>
                  </label>
                  <div className="form-actions">
                    <button type="button" className="button button-quiet" onClick={() => setProjectFormOpen(false)}>Cancel</button>
                    <button className="button button-primary" type="submit" disabled={creatingProject}>
                      {creatingProject ? "Registering…" : "Register project"}
                    </button>
                  </div>
                </form>
              )}

              <div className="project-list">
                {loadingProjects && projects.length === 0 ? (
                  <div className="empty-state"><div className="loader" /><strong>Loading projects</strong><span>Connecting to the Foundry API…</span></div>
                ) : projects.length === 0 ? (
                  <div className="empty-state">
                    <div className="empty-illustration"><span>◇</span><i /><b /></div>
                    <strong>Your workspace starts here</strong>
                    <span>Register your first repository to see it in this list.</span>
                    {!projectFormOpen && <button className="text-action" onClick={() => setProjectFormOpen(true)}>Create your first project <span>→</span></button>}
                  </div>
                ) : (
                  projects.map((project, index) => (
                    <button
                      key={project.id}
                      className={"project-row " + (project.id === selectedProjectId ? "project-row-selected" : "")}
                      onClick={() => setSelectedProjectId(project.id)}
                    >
                      <span className={"repo-avatar repo-avatar-" + (index % 4)}>{project.name.slice(0, 1).toUpperCase()}</span>
                      <span className="project-row-main">
                        <strong>{project.name}</strong>
                        <span title={project.repositoryUrl}>{project.repositoryUrl.replace(/^https?:\/\/(www\.)?/, "")}</span>
                        <span className={"project-trust-label " + (project.buildTrusted ? "project-trust-enabled" : "project-trust-disabled")}>
                          {project.buildTrusted ? "Build trust enabled" : "Build trust off"}
                        </span>
                      </span>
                      <span className="project-row-meta">{timeLabel(project.createdAt).split(",")[0]}</span>
                      <span className="row-chevron">›</span>
                    </button>
                  ))
                )}
              </div>
              <div className="panel-bottom"><span className="mini-live-dot" /> Synced with Foundry API <span className="bottom-spacer" /><button onClick={() => void refreshProjects()} className="subtle-action">Refresh ↻</button></div>
            </article>

            <article className="panel deployment-panel" id="deployments">
              <div className="panel-heading">
                <div>
                  <div className="section-kicker">RELEASE ACTIVITY</div>
                  <h2>Deployments <span className="heading-count">{deployments.length}</span></h2>
                  <p>{selectedProject ? "History for " + selectedProject.name : "Select a project to view releases."}</p>
                </div>
              </div>

              {selectedProject && (
                <div className={"project-trust-banner " + (selectedProject.buildTrusted ? "project-trust-banner-enabled" : "project-trust-banner-disabled")}>
                  <div className="project-trust-banner-copy">
                    <strong>{selectedProject.buildTrusted ? "Repository trusted for local builds" : "Repository build trust is off"}</strong>
                    <p>{selectedProject.buildTrusted
                      ? "Foundry may execute this repository's build and start scripts on this computer. Revoke trust to block future builds unless an administrator allowlists it."
                      : "Trust this repository here to avoid manually editing the API allowlist. A configured administrator allowlist can still override this project setting."}</p>
                  </div>
                  <button
                    type="button"
                    className={"button " + (selectedProject.buildTrusted ? "button-quiet" : "button-primary")}
                    onClick={() => void handleToggleBuildTrust()}
                    disabled={updatingBuildTrust}
                  >
                    {updatingBuildTrust ? "Saving…" : selectedProject.buildTrusted ? "Revoke trust" : "Trust repository"}
                  </button>
                </div>
              )}

              {selectedProject && (
                <form className="deploy-form" onSubmit={handleCreateDeployment}>
                  <div className="deploy-form-label"><span className="deploy-form-icon">↗</span><strong>New deployment</strong></div>
                  <label className="source-ref-field">
                    <span>Source ref</span>
                    <input value={sourceRef} onChange={(event) => setSourceRef(event.target.value)} placeholder="main" maxLength={255} required />
                  </label>
                  <label className="source-ref-field service-path-field">
                    <span>Service path (optional)</span>
                    <input value={servicePath} onChange={(event) => setServicePath(event.target.value)} placeholder="Auto-detect or apps/api" maxLength={512} />
                  </label>
                  <button className="button button-primary deploy-button" type="submit" disabled={creatingDeployment}>
                    {creatingDeployment ? "Queueing…" : "Deploy"} <span>→</span>
                  </button>
                </form>
              )}

              <div className="deployment-list">
                {!selectedProject ? (
                  <div className="empty-state compact-empty"><div className="empty-illustration"><span>↗</span><i /><b /></div><strong>No project selected</strong><span>Choose a project on the left to view its deployments.</span></div>
                ) : loadingDeployments && deployments.length === 0 ? (
                  <div className="empty-state compact-empty"><div className="loader" /><strong>Loading deployment history</strong></div>
                ) : deployments.length === 0 ? (
                  <div className="empty-state compact-empty"><div className="empty-illustration"><span>↗</span><i /><b /></div><strong>No deployments yet</strong><span>Queue your first deployment. Leave service path blank to auto-detect a single project.</span></div>
                ) : deployments.map((deployment, index) => (
                  <button
                    key={deployment.id}
                    className={"deployment-row " + (deployment.id === selectedDeploymentId ? "deployment-row-selected" : "")}
                    onClick={() => setSelectedDeploymentId(deployment.id)}
                  >
                    <span className={"deployment-symbol deployment-symbol-" + deployment.status.toLowerCase().replace("_", "-")}>{deployment.status === "SUCCEEDED" ? "✓" : deployment.status === "FAILED" ? "!" : "↗"}</span>
                    <span className="deployment-main">
                      <span className="deployment-title">Release <b>#{shortId(deployment.id)}</b></span>
                      <span className="deployment-meta"><span>{deployment.sourceRef}</span>{deployment.servicePath && <><i>·</i><span>{deployment.servicePath}</span></>}<i>·</i>{timeLabel(deployment.createdAt)}</span>
                    </span>
                    <StatusBadge status={deployment.status} />
                    <span className="row-chevron">›</span>
                  </button>
                ))}
              </div>
              <div className="panel-bottom"><span className="mini-live-dot" /> Auto-refresh every 5 seconds <span className="bottom-spacer" /><span className="muted-small">{loadingDeployments ? "Refreshing…" : "Up to date"}</span></div>
            </article>
          </section>

          <section className="panel log-panel" aria-label="Selected deployment details">
            <div className="log-panel-heading">
              <div>
                <div className="section-kicker">OBSERVABILITY</div>
                <h2>{selectedDeployment ? "Deployment details" : "Runtime logs"} <span className="heading-count">{logs.length}</span></h2>
                <p>{selectedDeployment ? "Release #" + shortId(selectedDeployment.id) + " · " + selectedDeployment.sourceRepositoryUrl : "Select a deployment above to inspect status and logs."}</p>
              </div>
              {selectedDeployment && <StatusBadge status={selectedDeployment.status} />}
            </div>

            {selectedDeployment && (
              <div className="deployment-detail-strip">
                <div className="detail-item"><span>CREATED</span><strong>{timeLabel(selectedDeployment.createdAt)}</strong></div>
                <div className="detail-item"><span>STARTED</span><strong>{timeLabel(selectedDeployment.startedAt)}</strong></div>
                <div className="detail-item"><span>COMPLETED</span><strong>{timeLabel(selectedDeployment.completedAt)}</strong></div>
                <div className="detail-item detail-url"><span>APPLICATION URL</span>
                  {selectedDeployment.applicationUrl ? <a href={selectedDeployment.applicationUrl} target="_blank" rel="noreferrer">{selectedDeployment.applicationUrl}<span> ↗</span></a> : <strong className="no-url">Not available yet</strong>}
                </div>
              </div>
            )}

            {selectedDeployment?.failureReason && <div className="failure-reason"><strong>Failure reason</strong><span>{selectedDeployment.failureReason}</span></div>}

            {selectedDeployment?.status === "QUEUED" && (
              <div className="queue-note">
                <span className="queue-note-icon">i</span>
                <span><strong>Deployment is queued.</strong> Foundry will only execute repository builds for projects with Build trust enabled or repositories explicitly trusted by the local administrator.</span>
              </div>
            )}

            <div className="logs-toolbar">
              <div className="logs-toolbar-left"><span className="terminal-glyph">⌁</span><strong>Build &amp; runtime output</strong><span className="logs-count">{logs.length} entries</span></div>
              <span className="logs-polling"><span className="mini-live-dot" /> Live polling</span>
            </div>

            <div className="terminal">
              {!selectedDeployment ? (
                <div className="terminal-empty"><span className="terminal-prompt">$</span> Select a deployment to inspect its event stream<span className="terminal-cursor" /></div>
              ) : logs.length === 0 && loadingLogs ? (
                <div className="terminal-empty"><span className="terminal-prompt">$</span> Loading deployment events…</div>
              ) : logs.length === 0 ? (
                <div className="terminal-empty"><span className="terminal-prompt">$</span> No logs yet. Events will appear here as the worker processes this deployment.<span className="terminal-cursor" /></div>
              ) : (
                <div className="terminal-lines">
                  {logs.map((entry) => (
                    <div className="terminal-line" key={entry.id}>
                      <span className="log-sequence">{String(entry.sequenceNumber).padStart(3, "0")}</span>
                      <span className="log-time">{new Date(entry.timestamp).toLocaleTimeString([], { hour12: false })}</span>
                      <span className={"log-stage log-stage-" + entry.stage.toLowerCase().replace("_", "-")}>{entry.stage}</span>
                      <span className={"log-level log-level-" + entry.severity.toLowerCase()}>{entry.severity}</span>
                      <span className="log-message">{entry.message}</span>
                    </div>
                  ))}
                  {logsHasMore && <div className="logs-more-note">Showing the first page of events. Additional older entries are available from the logs API.</div>}
                  <div className="terminal-tail"><span className="terminal-prompt">$</span> Listening for new events<span className="terminal-cursor" /></div>
                </div>
              )}
            </div>
            <div className="log-footer"><span>SEQUENCE CURSOR</span><strong>{lastLogSequence.current.toString().padStart(3, "0")}</strong><span className="footer-divider" /><span>API SOURCE</span><code>/api/v1/deployments/{selectedDeployment ? shortId(selectedDeployment.id) : "{id}"}/logs</code><span className="bottom-spacer" />{loadingLogs && <span className="muted-small">Refreshing…</span>}
            </div>
          </section>

          <footer className="page-footer"><span>FOUNDRY <b>•</b> LOCAL PREVIEW</span><span>Built for developers who ship.</span><span>API <b>·</b> Spring Boot <b>·</b> React</span></footer>
        </div>
      </main>
    </div>
  );
}

export default App;
