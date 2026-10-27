import type { ReactNode } from "react";
import type { ApiState, WorkspaceView } from "../types/workspace";
import BrandMark from "./BrandMark";

interface WorkspaceLayoutProps {
  activeView: WorkspaceView;
  apiState: ApiState;
  projectCount: number;
  onNavigate: (view: WorkspaceView) => void;
  onRefresh: () => void;
  children: ReactNode;
}

const viewLabels: Record<WorkspaceView, string> = {
  overview: "Overview",
  projects: "Projects",
  "project-workspace": "Project workspace"
};

export default function WorkspaceLayout({
  activeView,
  apiState,
  projectCount,
  onNavigate,
  onRefresh,
  children
}: WorkspaceLayoutProps) {
  const navItems: { view: WorkspaceView; icon: string; label: string; count?: number }[] = [
    { view: "overview", icon: "◫", label: "Overview" },
    { view: "projects", icon: "◇", label: "Projects", count: projectCount }
  ];

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <button className="brand-lockup" onClick={() => onNavigate("overview")} aria-label="Foundry overview">
          <BrandMark />
          <span className="brand-copy">
            <span className="brand-name">foundry<span>.</span></span>
            <span className="brand-caption">DEPLOYMENT PLATFORM</span>
          </span>
        </button>
        <div className="side-label">WORKSPACE</div>
        <nav className="side-nav" aria-label="Workspace navigation">
          {navItems.map((item) => (
            <button
              key={item.view}
              className={"nav-item " + (activeView === item.view || (activeView === "project-workspace" && item.view === "projects") ? "active" : "")}
              onClick={() => onNavigate(item.view)}
            >
              <span className="nav-glyph">{item.icon}</span><span>{item.label}</span>
              {item.count !== undefined && <span className="nav-count">{item.count}</span>}
            </button>
          ))}
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
          <div className="breadcrumbs"><span>Workspace</span><span className="crumb-divider">/</span><strong>{viewLabels[activeView]}</strong></div>
          <div className="topbar-right">
            <span className={"api-pill " + (apiState === "online" ? "api-pill-online" : apiState === "offline" ? "api-pill-offline" : "")}>
              <span className="pulse-dot" />
              {apiState === "online" ? "API connected" : apiState === "offline" ? "API unavailable" : "Connecting"}
            </span>
            <button className="icon-button" onClick={onRefresh} aria-label="Refresh workspace data" title="Refresh">↻</button>
          </div>
        </header>
        <nav className="mobile-nav" aria-label="Workspace navigation">
          {navItems.map((item) => (
            <button
              key={item.view}
              className={activeView === item.view || (activeView === "project-workspace" && item.view === "projects") ? "active" : ""}
              onClick={() => onNavigate(item.view)}
            >
              <span>{item.icon}</span>{item.label}
            </button>
          ))}
        </nav>
        <div className="page-content">{children}
          <footer className="page-footer"><span>FOUNDRY <b>•</b> LOCAL PREVIEW</span><span>Built for developers who ship.</span><span>API <b>·</b> Spring Boot <b>·</b> React</span></footer>
        </div>
      </main>
    </div>
  );
}