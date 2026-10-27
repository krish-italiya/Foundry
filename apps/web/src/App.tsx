import { useState } from "react";
import type { CreateDeploymentInput, CreateProjectInput, Deployment, Project, ProjectWorkspaceTab, WorkspaceView } from "./types/workspace";
import { useFoundryWorkspace } from "./hooks/useFoundryWorkspace";
import WorkspaceLayout from "./components/WorkspaceLayout";
import FeedbackBanners from "./components/FeedbackBanners";
import EmptyState from "./components/EmptyState";
import OverviewPage from "./pages/OverviewPage";
import ProjectsPage from "./pages/ProjectsPage";
import ProjectWorkspacePage from "./pages/ProjectWorkspacePage";

export default function App() {
  const workspace = useFoundryWorkspace();
  const [activeView, setActiveView] = useState<WorkspaceView>("overview");
  const [projectTab, setProjectTab] = useState<ProjectWorkspaceTab>("overview");
  const [projectFormOpen, setProjectFormOpen] = useState(false);

  function navigate(view: WorkspaceView) {
    setActiveView(view);
    workspace.clearFeedback();
    if (view !== "projects") setProjectFormOpen(false);
    if (view === "projects") setProjectFormOpen(false);
    if (view === "overview") setProjectTab("overview");
  }

  function openProject(project: Project) {
    workspace.setSelectedProjectId(project.id);
    setProjectTab("overview");
    setProjectFormOpen(false);
    workspace.clearFeedback();
    setActiveView("project-workspace");
  }

  function addProject() {
    workspace.clearFeedback();
    setProjectFormOpen(true);
    setActiveView("projects");
  }

  async function createProject(input: CreateProjectInput) {
    const created = await workspace.createProject(input);
    if (created) {
      setProjectFormOpen(false);
      setProjectTab("overview");
      setActiveView("project-workspace");
    }
    return created;
  }

  async function createDeployment(input: CreateDeploymentInput) {
    if (!workspace.selectedProject) return null;
    return workspace.createDeployment(workspace.selectedProject, input);
  }

  function openDeployment(deployment: Deployment) {
    workspace.setSelectedDeploymentId(deployment.id);
    setProjectTab("build-runtime");
    setActiveView("project-workspace");
    workspace.clearFeedback();
  }

  const projectWorkspace = activeView === "project-workspace" && workspace.selectedProject
    ? (
      <ProjectWorkspacePage
        project={workspace.selectedProject}
        deployments={workspace.deployments}
        selectedDeployment={workspace.selectedDeployment}
        logs={workspace.logs}
        logsHasMore={workspace.logsHasMore}
        loadingDeployments={workspace.loadingDeployments}
        loadingLogs={workspace.loadingLogs}
        creatingDeployment={workspace.creatingDeployment}
        updatingBuildTrust={workspace.updatingBuildTrust}
        sequence={workspace.lastLogSequence}
        activeTab={projectTab}
        onTabChange={setProjectTab}
        onBackToProjects={() => navigate("projects")}
        onToggleBuildTrust={workspace.toggleBuildTrust}
        onCreateDeployment={createDeployment}
        onSelectDeployment={(deployment) => workspace.setSelectedDeploymentId(deployment.id)}
      />
    )
    : null;

  return (
    <WorkspaceLayout
      activeView={activeView}
      apiState={workspace.apiState}
      projectCount={workspace.projects.length}
      onNavigate={navigate}
      onRefresh={() => void workspace.refreshProjects()}
    >
      <FeedbackBanners
        error={workspace.pageError}
        notice={workspace.notice}
        onDismissError={() => workspace.clearFeedback()}
        onDismissNotice={() => workspace.clearFeedback()}
      />

      {activeView === "overview" && (
        <OverviewPage
          projects={workspace.projects}
          selectedProject={workspace.selectedProject}
          deployments={workspace.deployments}
          loadingProjects={workspace.loadingProjects}
          loadingDeployments={workspace.loadingDeployments}
          onAddProject={addProject}
          onOpenProject={openProject}
          onViewProjects={() => navigate("projects")}
          onOpenDeployment={openDeployment}
        />
      )}

      {activeView === "projects" && (
        <ProjectsPage
          projects={workspace.projects}
          selectedProjectId={workspace.selectedProjectId}
          loadingProjects={workspace.loadingProjects}
          creatingProject={workspace.creatingProject}
          formOpen={projectFormOpen}
          onToggleForm={() => setProjectFormOpen((open) => !open)}
          onCancelForm={() => setProjectFormOpen(false)}
          onCreateProject={createProject}
          onOpenProject={openProject}
          onRefresh={() => void workspace.refreshProjects()}
        />
      )}

      {activeView === "project-workspace" && (
        projectWorkspace ?? (
          <EmptyState
            icon="◇"
            title="Select a project"
            description="Project deployments, build output, and runtime details belong to a specific project workspace."
            actionLabel="Browse projects"
            onAction={() => navigate("projects")}
          />
        )
      )}
    </WorkspaceLayout>
  );
}