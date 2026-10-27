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

type RuntimeState = "NOT_STARTED" | "RUNNING" | "STOPPED" | "CRASHED" | "INTERRUPTED";
type WorkspaceView = "overview" | "projects" | "deployments" | "build-runtime";

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
  runtimeState: RuntimeState;
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
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");

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
  return date.toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

function shortId(value: string): string {
  return value.slice(0, 8);
}

function statusLabel(status: DeploymentStatus): string {
  return status.replace(/_/g, " ").toLowerCase();
}

function runtimeStateLabel(state: RuntimeState): string {
  return state.replace(/_/g, " ").toLowerCase();
}

function StatusBadge({ status }: { status: DeploymentStatus }) {
  return (
    <span className={"status-badge status-" + status.toLowerCase().replace(/_/g, "-")}>
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
  const [activeView, setActiveView] = useState<WorkspaceView>("overview");
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
  const [deploymentQuery, setDeploymentQuery] = useState("");
  const [deploymentStatusFilter, setDeploymentStatusFilter] = useState("ALL");
  const [deploymentPage, setDeploymentPage] = useState(1);
  const lastLogSequence = useRef(0);

  const selectedProject = projects.find((project) => project.id === selectedProjectId);
  const selectedDeployment = deployments.find((deployment) => deployment.id === selectedDeploymentId);
  const succeededCount = deployments.filter((item) => item.status === "SUCCEEDED").length;
  const failedCount = deployments.filter((item) => item.status === "FAILED").length;
  const filteredDeployments = deployments.filter((deployment) => {
    const query = deploymentQuery.trim().toLowerCase();
    const matchesQuery = !query
      || deployment.id.toLowerCase().includes(query)
      || deployment.sourceRef.toLowerCase().includes(query)
      || (deployment.servicePath ?? "").toLowerCase().includes(query)
      || deployment.status.toLowerCase().includes(query);
    return matchesQuery && (deploymentStatusFilter === "ALL" || deployment.status === deploymentStatusFilter);
  });
  const pageSize = 8;
  const deploymentPageCount = Math.max(1, Math.ceil(filteredDeployments.length / pageSize));
  const visibleDeployments = filteredDeployments.slice((deploymentPage - 1) * pageSize, deploymentPage * pageSize);
  const viewLabels: Record<WorkspaceView, { title: string; eyebrow: string; subtitle: string }> = {
    overview: { title: "Overview", eyebrow: "WORKSPACE / OVERVIEW", subtitle: "Your projects, releases, and runtime health at a glance." },
    projects: { title: "Projects", eyebrow: "WORKSPACE / PROJECTS", subtitle: "Connect repositories and manage which code Foundry is allowed to build." },
    deployments: { title: "Deployments", eyebrow: "DELIVERY / DEPLOYMENTS", subtitle: "Create releases, filter deployment history, and follow each rollout." },
    "build-runtime": { title: "Build & Runtime", eyebrow: "OBSERVABILITY / BUILD & RUNTIME", subtitle: "Inspect one release from build output through its running application." }
  };

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
        setSelectedProjectId((current) => current && projectItems.some((project) => project.id === current)
          ? current : projectItems[0]?.id ?? "");
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
        const items = await api<Deployment[]>("/api/v1/projects/" + encodeURIComponent(selectedProjectId) + "/deployments");
        if (cancelled) return;
        setDeployments(items);
        setApiState("online");
        setSelectedDeploymentId((current) => current && items.some((deployment) => deployment.id === current)
          ? current : items[0]?.id ?? "");
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
    setDeploymentPage(1);
  }, [selectedProjectId, deploymentQuery, deploymentStatusFilter]);

  useEffect(() => {
    if (deploymentPage > deploymentPageCount) setDeploymentPage(deploymentPageCount);
  }, [deploymentPage, deploymentPageCount]);

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
        if (!cancelled) setPageError(error instanceof Error ? error.message : "Could not load deployment logs.");
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
      if (!items.some((project) => project.id === selectedProjectId)) setSelectedProjectId(items[0]?.id ?? "");
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
      setActiveView("projects");
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
    )) return;

    setPageError("");
    setNotice("");
    setUpdatingBuildTrust(true);
    try {
      const updated = await api<Project>(
        "/api/v1/projects/" + encodeURIComponent(selectedProject.id) + "/build-trust",
        { method: "PATCH", body: JSON.stringify({ buildTrusted }) }
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
        { method: "POST", body: JSON.stringify({ sourceRef: sourceRef.trim(), servicePath: servicePath.trim() || null }) }
      );
      setDeployments((current) => [created, ...current.filter((deployment) => deployment.id !== created.id)]);
      setSelectedDeploymentId(created.id);
      setNotice("Deployment request accepted. Build and runtime events will appear in Build & Runtime.");
      setActiveView("build-runtime");
      setApiState("online");
    } catch (error) {
      setPageError(error instanceof Error ? error.message : "Could not request deployment.");
    } finally {
      setCreatingDeployment(false);
    }
  }

  function openDeployment(deploymentId: string) {
    setSelectedDeploymentId(deploymentId);
    setActiveView("build-runtime");
  }

  function navigate(view: WorkspaceView) {
    setActiveView(view);
    setPageError("");
    if (view === "projects") setNotice("");
  }

  function openNewProject() {
    setProjectFormOpen(true);
    setActiveView("projects");
    setNotice("");
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <button className="brand-lockup" onClick={() => navigate("overview")} aria-label="Foundry overview">
          <Mark />
          <span className="brand-copy">
            <span className="brand-name">foundry<span>.</span></span>
            <span className="brand-caption">DEPLOYMENT PLATFORM</span>
          </span>
        </button>

        <div className="side-label">WORKSPACE</div>
        <nav className="side-nav" aria-label="Workspace navigation">
          <button className={"nav-item " + (activeView === "overview" ? "active" : "")} onClick={() => navigate("overview")}>
            <span className="nav-glyph">◫</span><span>Overview</span>
          </button>
          <button className={"nav-item " + (activeView === "projects" ? "active" : "")} onClick={() => navigate("projects")}>
            <span className="nav-glyph">◇</span><span>Projects</span><span className="nav-count">{projects.length}</span>
          </button>
          <button className={"nav-item " + (activeView === "deployments" ? "active" : "")} onClick={() => navigate("deployments")}>
            <span className="nav-glyph">↗</span><span>Deployments</span><span className="nav-count">{deployments.length}</span>
          </button>
          <button className={"nav-item " + (activeView === "build-runtime" ? "active" : "")} onClick={() => navigate("build-runtime")}>
            <span className="nav-glyph">⌁</span><span>Build &amp; Runtime</span>
            {selectedDeployment && <span className="nav-indicator" title={"Release #" + shortId(selectedDeployment.id)} />}
          </button>
        </nav>

        <div className="sidebar-spacer" />

        <div className="runtime-card">
          <div className="runtime-card-top"><span className="runtime-icon">⌘</span><span>LOCAL ENVIRONMENT</span></div>
          <div className="runtime-title">Control plane</div>
          <div className="runtime-status">
            <span className={"pulse-dot " + (apiState === "online" ? "pulse-green" : apiState === "offline" ? "pulse-red" : "")} />
            {apiState === "online" ? "API connected" : apiState === "offline" ? "API unavailable" : "Connecting to API"}
          </div>
          <div className="runtime-foot">localhost:8080</div>
        </div>

        <div className="sidebar-footer">
          <div className="avatar">F</div>
          <div className="footer-user"><strong>Developer workspace</strong><span>Local development</span></div>
          <span className="footer-dots">•••</span>
        </div>
      </aside>

      <main className="main-panel">
        <header className="topbar">
          <div className="breadcrumbs"><span>Workspace</span><span className="crumb-divider">/</span><strong>{viewLabels[activeView].title}</strong></div>
          <div className="topbar-right">
            <span className={"api-pill " + (apiState === "online" ? "api-pill-online" : apiState === "offline" ? "api-pill-offline" : "")}>
              <span className="pulse-dot" />
              {apiState === "online" ? "API connected" : apiState === "offline" ? "API unavailable" : "Connecting"}
            </span>
            <button className="icon-button" onClick={() => void refreshProjects()} aria-label="Refresh workspace data" title="Refresh">↻</button>
            {activeView === "projects" && <button className="button button-primary header-action" onClick={openNewProject}>＋ New project</button>}
            {activeView === "deployments" && <button className="button button-primary header-action" onClick={() => document.getElementById("deployment-create-form")?.scrollIntoView({ behavior: "smooth", block: "center" })}>＋ New deployment</button>}
          </div>
        </header>

        <nav className="mobile-nav" aria-label="Workspace navigation">
          <button className={activeView === "overview" ? "active" : ""} onClick={() => navigate("overview")}><span>◫</span>Overview</button>
          <button className={activeView === "projects" ? "active" : ""} onClick={() => navigate("projects")}><span>◇</span>Projects</button>
          <button className={activeView === "deployments" ? "active" : ""} onClick={() => navigate("deployments")}><span>↗</span>Deployments</button>
          <button className={activeView === "build-runtime" ? "active" : ""} onClick={() => navigate("build-runtime")}><span>⌁</span>Build &amp; Runtime</button>
        </nav>

        <div className="page-content">
          {pageError && (
            <div className="alert alert-error" role="alert">
              <span className="alert-symbol">!</span>
              <div><strong>Something needs attention</strong><p>{pageError}</p></div>
              <button className="alert-close" onClick={() => setPageError("")} aria-label="Dismiss error">×</button>
            </div>
          )}
          {notice && (
            <div className="alert alert-success" role="status">
              <span className="alert-symbol">✓</span><p>{notice}</p>
              <button className="alert-close" onClick={() => setNotice("")} aria-label="Dismiss message">×</button>
            </div>
          )}

          {activeView === "overview" && (
            <>
              <section className="page-intro overview-intro">
                <div>
                  <div className="eyebrow"><span className="eyebrow-line" />{viewLabels.overview.eyebrow}</div>
                  <h1>Ship software.<br /><span>Know what’s running.</span></h1>
                  <p className="page-subtitle">A clear view of your repositories, deployments, and runtime signals — without digging through one long page.</p>
                </div>
                <div className="intro-actions">
                  <button className="button button-quiet" onClick={openNewProject}>＋ Add project</button>
                  <button className="button button-primary" onClick={() => navigate("deployments")}>Deploy a project <span>→</span></button>
                </div>
              </section>

              <section className="stats-grid" aria-label="Workspace summary">
                <article className="stat-card">
                  <div className="stat-top"><span className="stat-label">PROJECTS</span><span className="stat-icon stat-icon-violet">◇</span></div>
                  <div className="stat-value">{loadingProjects ? "—" : projects.length.toString().padStart(2, "0")}</div>
                  <div className="stat-foot"><span className="stat-foot-dot violet-dot" /> Repositories connected</div>
                </article>
                <article className="stat-card">
                  <div className="stat-top"><span className="stat-label">DEPLOYMENTS</span><span className="stat-icon stat-icon-blue">↗</span></div>
                  <div className="stat-value">{deployments.length.toString().padStart(2, "0")}</div>
                  <div className="stat-foot"><span className="stat-foot-dot blue-dot" /> For {selectedProject?.name ?? "selected project"}</div>
                </article>
                <article className="stat-card">
                  <div className="stat-top"><span className="stat-label">SUCCEEDED</span><span className="stat-icon stat-icon-green">✓</span></div>
                  <div className="stat-value">{succeededCount.toString().padStart(2, "0")}</div>
                  <div className="stat-foot"><span className="stat-foot-dot green-dot" /> Health check passed</div>
                </article>
                <article className="stat-card">
                  <div className="stat-top"><span className="stat-label">FAILED</span><span className="stat-icon stat-icon-amber">!</span></div>
                  <div className="stat-value">{failedCount.toString().padStart(2, "0")}</div>
                  <div className="stat-foot"><span className="stat-foot-dot amber-dot" /> Needs attention</div>
                </article>
              </section>

              <section className="overview-grid">
                <article className="surface-card">
                  <div className="card-heading">
                    <div><div className="section-kicker">LATEST ACTIVITY</div><h2>Recent deployments</h2><p>Latest releases for {selectedProject?.name ?? "your selected project"}.</p></div>
                    <button className="text-action" onClick={() => navigate("deployments")}>View history <span>→</span></button>
                  </div>
                  <div className="recent-list">
                    {loadingDeployments && deployments.length === 0 ? <div className="empty-state"><div className="loader" /><strong>Loading deployment history</strong></div>
                    : deployments.length === 0 ? (
                      <div className="empty-state">
                        <div className="empty-illustration"><span>↗</span><i /><b /></div>
                        <strong>No deployments yet</strong><span>Register a repository, then create your first release.</span>
                        <button className="text-action" onClick={() => navigate("deployments")}>Start a deployment <span>→</span></button>
                      </div>
                    ) : deployments.slice(0, 5).map((deployment) => (
                      <button className="recent-deployment-row" key={deployment.id} onClick={() => openDeployment(deployment.id)}>
                        <span className={"deployment-symbol deployment-symbol-" + deployment.status.toLowerCase().replace(/_/g, "-")}>{deployment.status === "SUCCEEDED" ? "✓" : deployment.status === "FAILED" ? "!" : "↗"}</span>
                        <span className="deployment-main">
                          <span className="deployment-title">Release <b>#{shortId(deployment.id)}</b></span>
                          <span className="deployment-meta"><span>{deployment.sourceRef}</span><i>·</i>{timeLabel(deployment.createdAt)}</span>
                        </span>
                        <StatusBadge status={deployment.status} /><span className="row-chevron">›</span>
                      </button>
                    ))}
                  </div>
                  <div className="card-footer"><span className="mini-live-dot" /> Auto-refreshes every 5 seconds <span className="bottom-spacer" /><span className="muted-small">{loadingDeployments ? "Refreshing…" : "Up to date"}</span></div>
                </article>

                <div className="overview-side-column">
                  <article className="surface-card quickstart-card">
                    <div className="section-kicker">GET STARTED</div>
                    <h2>Your next step</h2>
                    <p>{projects.length === 0 ? "Connect a repository to begin your first build." : deployments.length === 0 ? "Your repository is connected. Create a deployment to build and launch it." : failedCount > 0 ? "Review the latest failure and its logs to find the cause." : "Open a release to inspect its build output, health check, and runtime."}</p>
                    <div className="quickstart-actions">
                      <button className="button button-primary" onClick={() => projects.length === 0 ? openNewProject() : navigate("deployments")}>{projects.length === 0 ? "Connect repository" : "Open deployments"} <span>→</span></button>
                      <button className="button button-quiet" onClick={() => navigate("build-runtime")}>Build &amp; Runtime</button>
                    </div>
                  </article>
                  <article className="surface-card pipeline-card">
                    <div className="section-kicker">DEPLOYMENT FLOW</div>
                    <h2>From repo to runtime</h2>
                    <div className="pipeline-steps">
                      <div className="pipeline-step"><span>01</span><div><strong>Checkout &amp; build</strong><small>Resolve the manifest and produce an artifact.</small></div></div>
                      <div className="pipeline-step"><span>02</span><div><strong>Package</strong><small>Create an image for a supported runtime.</small></div></div>
                      <div className="pipeline-step"><span>03</span><div><strong>Start &amp; verify</strong><small>Wait for the application health check.</small></div></div>
                    </div>
                  </article>
                </div>
              </section>
            </>
          )}

          {activeView === "projects" && (
            <>
              <section className="page-heading-row">
                <div><div className="eyebrow"><span className="eyebrow-line" />{viewLabels.projects.eyebrow}</div><h1>Projects</h1><p className="page-subtitle">{viewLabels.projects.subtitle}</p></div>
                <button className="button button-primary" onClick={() => setProjectFormOpen((open) => !open)}>{projectFormOpen ? "Close form" : "＋ New project"}</button>
              </section>

              {projectFormOpen && (
                <form className="surface-card project-form-card" onSubmit={handleCreateProject}>
                  <div className="form-title">Register a repository</div>
                  <p className="form-description">Give the repository a recognizable name. Trust is optional now and can be enabled later.</p>
                  <div className="form-grid">
                    <label>Project name<input value={projectName} onChange={(event) => setProjectName(event.target.value)} placeholder="e.g. storefront-api" maxLength={80} required /></label>
                    <label>Repository URL<input type="url" value={repositoryUrl} onChange={(event) => setRepositoryUrl(event.target.value)} placeholder="https://github.com/org/repository" maxLength={2048} required /></label>
                  </div>
                  <label className="trust-consent">
                    <input className="trust-consent-input" type="checkbox" checked={buildTrustedOnCreate} onChange={(event) => setBuildTrustedOnCreate(event.target.checked)} />
                    <span className="trust-consent-copy"><strong>Trust this repository for local builds</strong><small>Foundry may execute dependency installation, build, test, and start scripts from this repository on this computer. Enable only for code you trust.</small></span>
                  </label>
                  <div className="form-actions">
                    <button type="button" className="button button-quiet" onClick={() => setProjectFormOpen(false)}>Cancel</button>
                    <button className="button button-primary" type="submit" disabled={creatingProject}>{creatingProject ? "Registering…" : "Register project"}</button>
                  </div>
                </form>
              )}

              <section className="projects-layout">
                <article className="surface-card">
                  <div className="card-heading"><div><div className="section-kicker">YOUR WORKSPACE</div><h2>Repositories <span className="heading-count">{projects.length}</span></h2><p>Select a project to inspect its configuration.</p></div><button className="text-action" onClick={() => void refreshProjects()}>Refresh ↻</button></div>
                  <div className="project-list">
                    {loadingProjects && projects.length === 0 ? <div className="empty-state"><div className="loader" /><strong>Loading projects</strong></div>
                    : projects.length === 0 ? <div className="empty-state"><div className="empty-illustration"><span>◇</span><i /><b /></div><strong>No projects yet</strong><span>Start by connecting a GitHub repository.</span><button className="text-action" onClick={() => setProjectFormOpen(true)}>Add your first project <span>→</span></button></div>
                    : projects.map((project, index) => (
                      <button key={project.id} className={"project-row " + (project.id === selectedProjectId ? "project-row-selected" : "")} onClick={() => setSelectedProjectId(project.id)}>
                        <span className={"repo-avatar repo-avatar-" + (index % 4)}>{project.name.slice(0, 1).toUpperCase()}</span>
                        <span className="project-row-main"><strong>{project.name}</strong><span title={project.repositoryUrl}>{project.repositoryUrl.replace(/^https?:\/\/(www\.)?/, "")}</span><span className={"project-trust-label " + (project.buildTrusted ? "project-trust-enabled" : "project-trust-disabled")}>{project.buildTrusted ? "Build trust enabled" : "Build trust off"}</span></span>
                        <span className="row-chevron">›</span>
                      </button>
                    ))}
                  </div>
                  <div className="card-footer"><span className="mini-live-dot" /> Synced with Foundry API <span className="bottom-spacer" /><span className="muted-small">{projects.length} registered</span></div>
                </article>

                <article className="surface-card project-inspector">
                  {!selectedProject ? (
                    <div className="empty-state"><div className="empty-illustration"><span>◇</span><i /><b /></div><strong>Select a project</strong><span>Repository settings and build trust will appear here.</span></div>
                  ) : (
                    <>
                      <div className="section-kicker">PROJECT DETAILS</div>
                      <div className="project-inspector-title"><span className="repo-avatar repo-avatar-0">{selectedProject.name.slice(0, 1).toUpperCase()}</span><div><h2>{selectedProject.name}</h2><span className={"project-trust-label " + (selectedProject.buildTrusted ? "project-trust-enabled" : "project-trust-disabled")}>{selectedProject.buildTrusted ? "Build trust enabled" : "Build trust off"}</span></div></div>
                      <div className="inspector-field"><span>REPOSITORY</span><a href={selectedProject.repositoryUrl} target="_blank" rel="noreferrer">{selectedProject.repositoryUrl}<span> ↗</span></a></div>
                      <div className="inspector-field"><span>REGISTERED</span><strong>{timeLabel(selectedProject.createdAt)}</strong></div>
                      <div className={"trust-panel " + (selectedProject.buildTrusted ? "trust-panel-enabled" : "trust-panel-disabled")}>
                        <div className="trust-panel-icon">{selectedProject.buildTrusted ? "✓" : "!"}</div><div><strong>{selectedProject.buildTrusted ? "Repository trusted" : "Build trust required"}</strong><p>{selectedProject.buildTrusted ? "Foundry can run this repository’s build and start scripts for local deployments." : "Trust this repository before running its build scripts. Registration alone does not authorize execution."}</p></div>
                        <button type="button" className={"button " + (selectedProject.buildTrusted ? "button-quiet" : "button-primary")} onClick={() => void handleToggleBuildTrust()} disabled={updatingBuildTrust}>{updatingBuildTrust ? "Saving…" : selectedProject.buildTrusted ? "Revoke trust" : "Trust repository"}</button>
                      </div>
                      <button className="button button-primary full-width" onClick={() => navigate("deployments")}>Create deployment for this project <span>→</span></button>
                    </>
                  )}
                </article>
              </section>
            </>
          )}

          {activeView === "deployments" && (
            <>
              <section className="page-heading-row">
                <div><div className="eyebrow"><span className="eyebrow-line" />{viewLabels.deployments.eyebrow}</div><h1>Deployments</h1><p className="page-subtitle">{viewLabels.deployments.subtitle}</p></div>
                <label className="project-picker"><span>PROJECT</span><select value={selectedProjectId} onChange={(event) => setSelectedProjectId(event.target.value)} disabled={projects.length === 0}><option value="">Select project</option>{projects.map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}</select></label>
              </section>

              {!selectedProject ? (
                <section className="surface-card empty-page-card"><div className="empty-illustration"><span>↗</span><i /><b /></div><h2>Choose a project to deploy</h2><p>Register a repository first. Each deployment belongs to a project so its build history stays organized.</p><button className="button button-primary" onClick={openNewProject}>＋ Create project</button></section>
              ) : (
                <>
                  {!selectedProject.buildTrusted && (
                    <div className="trust-callout"><span className="queue-note-icon">!</span><div><strong>Build trust is off for {selectedProject.name}</strong><p>Foundry will refuse to execute repository scripts until you trust this repository. Review the source and enable trust in Projects.</p></div><button className="button button-quiet" onClick={() => navigate("projects")}>Project settings</button></div>
                  )}
                  <form className="surface-card deployment-create-card" id="deployment-create-form" onSubmit={handleCreateDeployment}>
                    <div className="card-heading"><div><div className="section-kicker">NEW RELEASE</div><h2>Deploy {selectedProject.name}</h2><p>Build a commit or branch into a runnable application.</p></div><span className="deploy-form-icon">↗</span></div>
                    <div className="deployment-form-grid">
                      <label className="field-label">Source ref<input value={sourceRef} onChange={(event) => setSourceRef(event.target.value)} placeholder="main" maxLength={255} required /></label>
                      <label className="field-label">Service path <span className="field-hint">Optional · for monorepos</span><input value={servicePath} onChange={(event) => setServicePath(event.target.value)} placeholder="Leave blank to auto-detect" maxLength={512} /></label>
                      <button className="button button-primary deploy-button" type="submit" disabled={creatingDeployment || !selectedProject.buildTrusted}>{creatingDeployment ? "Queueing…" : "Deploy now"} <span>→</span></button>
                    </div>
                    <div className="form-helper"><span className="mini-live-dot" /> Build, image packaging, startup, and health checks will appear under Build &amp; Runtime.</div>
                  </form>
                </>
              )}

              <section className="surface-card deployment-history-card">
                <div className="card-heading"><div><div className="section-kicker">RELEASE ACTIVITY</div><h2>Deployment history <span className="heading-count">{deployments.length}</span></h2><p>{selectedProject ? "History for " + selectedProject.name : "Select a project to load its deployment history."}</p></div><button className="text-action" onClick={() => setDeploymentQuery("")}>Clear filters</button></div>
                <div className="history-toolbar">
                  <label className="search-field"><span>⌕</span><input value={deploymentQuery} onChange={(event) => setDeploymentQuery(event.target.value)} placeholder="Search release ID, ref, path…" aria-label="Search deployments" /></label>
                  <label className="filter-select"><span>STATUS</span><select value={deploymentStatusFilter} onChange={(event) => setDeploymentStatusFilter(event.target.value)}><option value="ALL">All statuses</option><option value="QUEUED">Queued</option><option value="BUILDING">Building</option><option value="STARTING">Starting</option><option value="HEALTH_CHECK">Health check</option><option value="SUCCEEDED">Succeeded</option><option value="FAILED">Failed</option></select></label>
                </div>
                <div className="deployment-list">
                  {!selectedProject ? <div className="empty-state"><strong>No project selected</strong><span>Choose a project above to see its releases.</span></div>
                  : loadingDeployments && deployments.length === 0 ? <div className="empty-state"><div className="loader" /><strong>Loading deployment history</strong></div>
                  : filteredDeployments.length === 0 ? <div className="empty-state"><strong>No matching deployments</strong><span>Change the search or status filter, or start a new deployment.</span></div>
                  : visibleDeployments.map((deployment) => (
                    <button key={deployment.id} className={"deployment-row " + (deployment.id === selectedDeploymentId ? "deployment-row-selected" : "")} onClick={() => openDeployment(deployment.id)}>
                      <span className={"deployment-symbol deployment-symbol-" + deployment.status.toLowerCase().replace(/_/g, "-")}>{deployment.status === "SUCCEEDED" ? "✓" : deployment.status === "FAILED" ? "!" : "↗"}</span>
                      <span className="deployment-main"><span className="deployment-title">Release <b>#{shortId(deployment.id)}</b></span><span className="deployment-meta"><span>{deployment.sourceRef}</span>{deployment.servicePath && <><i>·</i><span>{deployment.servicePath}</span></>}<i>·</i>{timeLabel(deployment.createdAt)}</span></span>
                      <StatusBadge status={deployment.status} /><span className="row-chevron">›</span>
                    </button>
                  ))}
                </div>
                {filteredDeployments.length > pageSize && (
                  <div className="pagination-bar">
                    <span>Showing {(deploymentPage - 1) * pageSize + 1}–{Math.min(deploymentPage * pageSize, filteredDeployments.length)} of {filteredDeployments.length} releases</span>
                    <div className="pagination-actions">
                      <button className="button button-quiet" onClick={() => setDeploymentPage((page) => Math.max(1, page - 1))} disabled={deploymentPage === 1}>← Previous</button>
                      <span className="page-indicator">{deploymentPage} / {deploymentPageCount}</span>
                      <button className="button button-quiet" onClick={() => setDeploymentPage((page) => Math.min(deploymentPageCount, page + 1))} disabled={deploymentPage === deploymentPageCount}>Next →</button>
                    </div>
                  </div>
                )}
                <div className="card-footer"><span className="mini-live-dot" /> Auto-refresh every 5 seconds <span className="bottom-spacer" /><span className="muted-small">{filteredDeployments.length} of {deployments.length} shown</span></div>
              </section>
            </>
          )}

          {activeView === "build-runtime" && (
            <>
              <section className="page-heading-row">
                <div><div className="eyebrow"><span className="eyebrow-line" />{viewLabels["build-runtime"].eyebrow}</div><h1>Build &amp; Runtime</h1><p className="page-subtitle">{selectedDeployment ? "Release #" + shortId(selectedDeployment.id) + " · " + selectedDeployment.sourceRepositoryUrl : viewLabels["build-runtime"].subtitle}</p></div>
                <div className="heading-actions"><button className="button button-quiet" onClick={() => navigate("deployments")}>← All deployments</button>{selectedDeployment && <StatusBadge status={selectedDeployment.status} />}</div>
              </section>

              {!selectedDeployment ? (
                <section className="surface-card empty-page-card"><div className="empty-illustration"><span>⌁</span><i /><b /></div><h2>Select a release to inspect</h2><p>Choose any deployment from history to inspect its build stages, health check, application URL, and runtime logs.</p><button className="button button-primary" onClick={() => navigate("deployments")}>Open deployments <span>→</span></button></section>
              ) : (
                <>
                  <section className="runtime-summary-grid">
                    <article className="surface-card runtime-summary-main">
                      <div className="runtime-summary-top"><div><div className="section-kicker">SELECTED RELEASE</div><h2>Release <span className="release-id">#{shortId(selectedDeployment.id)}</span></h2><p>{selectedDeployment.sourceRef}{selectedDeployment.servicePath ? " · " + selectedDeployment.servicePath : " · repository root"}</p></div><StatusBadge status={selectedDeployment.status} /></div>
                      <div className="deployment-detail-strip">
                        <div className="detail-item"><span>CREATED</span><strong>{timeLabel(selectedDeployment.createdAt)}</strong></div>
                        <div className="detail-item"><span>STARTED</span><strong>{timeLabel(selectedDeployment.startedAt)}</strong></div>
                        <div className="detail-item"><span>COMPLETED</span><strong>{timeLabel(selectedDeployment.completedAt)}</strong></div>
                        <div className={"detail-item runtime-state-item runtime-state-" + selectedDeployment.runtimeState.toLowerCase().replace(/_/g, "-")}><span>RUNTIME</span><strong>{runtimeStateLabel(selectedDeployment.runtimeState)}</strong></div>
                      </div>
                      {selectedDeployment.failureReason && <div className="failure-reason"><strong>Failure reason</strong><span>{selectedDeployment.failureReason}</span></div>}
                      {selectedDeployment.status === "QUEUED" && <div className="queue-note"><span className="queue-note-icon">i</span><span><strong>Deployment is queued.</strong> Foundry will execute repository builds only for trusted projects or administrator-allowlisted repositories.</span></div>}
                    </article>
                    <article className="surface-card application-card">
                      <div className="section-kicker">LIVE APPLICATION</div>
                      <h2>{selectedDeployment.runtimeState === "RUNNING" ? "Your app is running" : selectedDeployment.status === "FAILED" ? "Deployment needs attention" : "Waiting for a live URL"}</h2>
                      <p>{selectedDeployment.runtimeState === "RUNNING" ? "The runtime is available through Foundry’s local preview router." : "The URL becomes available after a supported application starts and passes its health check."}</p>
                      {selectedDeployment.applicationUrl ? <a className="application-link" href={selectedDeployment.applicationUrl} target="_blank" rel="noreferrer"><span>{selectedDeployment.applicationUrl}</span><span>↗</span></a> : <div className="application-link-disabled">URL not available yet</div>}
                      {selectedDeployment.applicationUrl && <a className="button button-primary full-width application-open-button" href={selectedDeployment.applicationUrl} target="_blank" rel="noreferrer">Open application <span>↗</span></a>}
                    </article>
                  </section>

                  <section className="surface-card build-timeline-card">
                    <div className="card-heading"><div><div className="section-kicker">PIPELINE STATUS</div><h2>Build to runtime</h2><p>Each stage emits logs below. A deployment is successful only after health check passes.</p></div><span className="logs-polling"><span className="mini-live-dot" /> Live updates</span></div>
                    <div className="stage-timeline">
                      {[
                        { title: "Checkout & build", detail: "Repository fetch, dependency install, tests, and compilation", stage: "BUILD" },
                        { title: "Package image", detail: "Build the runtime image from the prepared artifact", stage: "BUILD" },
                        { title: "Start application", detail: "Create the constrained runtime and begin log capture", stage: "STARTUP" },
                        { title: "Health check", detail: "Verify the application responds over HTTP", stage: "HEALTH_CHECK" }
                      ].map((step, index) => {
                        const relevantLogs = logs.filter((entry) => entry.stage === step.stage);
                        const stageDone = selectedDeployment.status === "SUCCEEDED"
                          || (index === 0 && logs.some((entry) => entry.stage === "BUILD" && entry.severity === "INFO"))
                          || (index === 1 && logs.some((entry) => entry.message.toLowerCase().includes("naming to docker.io") || entry.message.toLowerCase().includes("image built")))
                          || (index === 2 && logs.some((entry) => entry.message.toLowerCase().includes("started docker container")))
                          || (index === 3 && logs.some((entry) => entry.message.toLowerCase().includes("health check passed")));
                        const stageFailed = selectedDeployment.status === "FAILED" && !stageDone && (index === 0 || index === 1 || index === 2 || index === 3);
                        return <div className={"stage-card " + (stageFailed ? "stage-failed" : stageDone ? "stage-done" : "stage-waiting")} key={step.title}>
                          <span className="stage-number">{stageFailed ? "!" : stageDone ? "✓" : String(index + 1).padStart(2, "0")}</span>
                          <div><strong>{step.title}</strong><p>{step.detail}</p><small>{stageFailed ? "Failed" : stageDone ? "Activity recorded" : relevantLogs.length ? "In progress" : "Waiting"}</small></div>
                        </div>;
                      })}
                    </div>
                  </section>

                  <section className="surface-card log-panel">
                    <div className="log-panel-heading"><div><div className="section-kicker">OBSERVABILITY</div><h2>Build &amp; runtime output <span className="heading-count">{logs.length}</span></h2><p>Command output, image build events, container logs, and health-check results.</p></div><span className="logs-polling"><span className="mini-live-dot" /> Live polling</span></div>
                    <div className="logs-toolbar"><div className="logs-toolbar-left"><span className="terminal-glyph">⌁</span><strong>Event stream</strong><span className="logs-count">{logs.length} entries</span></div><span className="logs-polling"><span className="mini-live-dot" /> Refreshes every 5s</span></div>
                    <div className="terminal">
                      {logs.length === 0 && loadingLogs ? <div className="terminal-empty"><div className="loader" /><span>Loading deployment events…</span></div>
                      : logs.length === 0 ? <div className="terminal-empty"><span className="terminal-prompt">$</span>No logs yet. Events appear as the worker processes the deployment.<span className="terminal-cursor" /></div>
                      : <div className="terminal-lines">
                        {logs.map((entry) => <div className="terminal-line" key={entry.id}>
                          <span className="log-sequence">{String(entry.sequenceNumber).padStart(3, "0")}</span>
                          <span className="log-time">{new Date(entry.timestamp).toLocaleTimeString([], { hour12: false })}</span>
                          <span className={"log-stage log-stage-" + entry.stage.toLowerCase().replace(/_/g, "-")}>{entry.stage}</span>
                          <span className={"log-level log-level-" + entry.severity.toLowerCase()}>{entry.severity}</span>
                          <span className="log-message">{entry.message}</span>
                        </div>)}
                        {logsHasMore && <div className="logs-more-note">More events are available from the paginated logs API.</div>}
                        <div className="terminal-tail"><span className="terminal-prompt">$</span> Listening for new events<span className="terminal-cursor" /></div>
                      </div>}
                    </div>
                    <div className="log-footer"><span>SEQUENCE CURSOR</span><strong>{lastLogSequence.current.toString().padStart(3, "0")}</strong><span className="footer-divider" /><span>API SOURCE</span><code>/api/v1/deployments/{selectedDeployment.id}/logs</code><span className="bottom-spacer" />{loadingLogs && <span className="muted-small">Refreshing…</span>}</div>
                  </section>
                </>
              )}
            </>
          )}

          <footer className="page-footer"><span>FOUNDRY <b>•</b> LOCAL PREVIEW</span><span>Built for developers who ship.</span><span>API <b>·</b> Spring Boot <b>·</b> React</span></footer>
        </div>
      </main>
    </div>
  );
}

export default App;
