import { useState } from "react";
import type { CreateDeploymentInput, Deployment, LogEntry, Project, ProjectWorkspaceTab } from "../types/workspace";
import ProjectOverviewTab from "./ProjectOverviewTab";
import ProjectDeploymentsTab from "./ProjectDeploymentsTab";
import BuildRuntimeTab from "./BuildRuntimeTab";

interface ProjectWorkspacePageProps {
  project: Project;
  deployments: Deployment[];
  selectedDeployment?: Deployment;
  logs: LogEntry[];
  logsHasMore: boolean;
  loadingDeployments: boolean;
  loadingLogs: boolean;
  creatingDeployment: boolean;
  updatingBuildTrust: boolean;
  sequence: number;
  onBackToProjects: () => void;
  onToggleBuildTrust: (project: Project) => Promise<boolean>;
  onCreateDeployment: (input: CreateDeploymentInput) => Promise<Deployment | null>;
  onSelectDeployment: (deployment: Deployment) => void;
}

export default function ProjectWorkspacePage({
  project, deployments, selectedDeployment, logs, logsHasMore, loadingDeployments, loadingLogs,
  creatingDeployment, updatingBuildTrust, sequence, onBackToProjects, onToggleBuildTrust, onCreateDeployment, onSelectDeployment
}: ProjectWorkspacePageProps) {
  const [activeTab, setActiveTab] = useState<ProjectWorkspaceTab>("overview");

  function openDeployment(deployment: Deployment) {
    onSelectDeployment(deployment);
    setActiveTab("build-runtime");
  }

  const tabs: { id: ProjectWorkspaceTab; label: string; icon: string; count?: number }[] = [
    { id: "overview", label: "Overview", icon: "⌂" },
    { id: "deployments", label: "Deployments", icon: "↗", count: deployments.length },
    { id: "build-runtime", label: "Build & Runtime", icon: "⌁" }
  ];

  return (
    <>
      <section className="project-workspace-heading">
        <button className="back-link" onClick={onBackToProjects}>← All projects</button>
        <div className="project-workspace-title-row">
          <div className="project-workspace-title">
            <span className="repo-avatar repo-avatar-0 project-detail-avatar">{project.name.slice(0, 1).toUpperCase()}</span>
            <div><div className="eyebrow"><span className="eyebrow-line" />PROJECT WORKSPACE</div><h1>{project.name}</h1><p className="page-subtitle">{project.repositoryUrl}</p></div>
          </div>
          {selectedDeployment && <div className="workspace-current-release"><span className="section-kicker">LATEST SELECTED RELEASE</span><strong>#{selectedDeployment.id.slice(0, 8)}</strong></div>}
        </div>
      </section>

      <nav className="project-tabs" aria-label="Project sections">
        {tabs.map((tab) => (
          <button key={tab.id} className={"project-tab " + (activeTab === tab.id ? "active" : "")} onClick={() => setActiveTab(tab.id)}>
            <span>{tab.icon}</span>{tab.label}{tab.count !== undefined && <small>{tab.count}</small>}
          </button>
        ))}
      </nav>

      {activeTab === "overview" && <ProjectOverviewTab
        project={project} deployments={deployments} loadingDeployments={loadingDeployments}
        updatingBuildTrust={updatingBuildTrust} onToggleBuildTrust={onToggleBuildTrust}
        onOpenDeployment={openDeployment} onGoToDeployments={() => setActiveTab("deployments")}
      />}
      {activeTab === "deployments" && <ProjectDeploymentsTab
        project={project} deployments={deployments} loadingDeployments={loadingDeployments}
        creatingDeployment={creatingDeployment} selectedDeploymentId={selectedDeployment?.id ?? ""}
        onCreateDeployment={onCreateDeployment} onOpenDeployment={openDeployment}
      />}
      {activeTab === "build-runtime" && <BuildRuntimeTab
        deployment={selectedDeployment} logs={logs} logsHasMore={logsHasMore}
        loadingLogs={loadingLogs} sequence={sequence} onChooseDeployment={() => setActiveTab("deployments")}
      />}
    </>
  );
}