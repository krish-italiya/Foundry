export type DeploymentStatus =
  | "QUEUED"
  | "BUILDING"
  | "STARTING"
  | "HEALTH_CHECK"
  | "SUCCEEDED"
  | "FAILED";

export type RuntimeState = "NOT_STARTED" | "RUNNING" | "STOPPED" | "CRASHED" | "INTERRUPTED";
export type WorkspaceView = "overview" | "projects" | "project-workspace";
export type ProjectWorkspaceTab = "overview" | "deployments" | "build-runtime";
export type ApiState = "checking" | "online" | "offline";

export interface Project {
  id: string;
  name: string;
  repositoryUrl: string;
  createdAt: string;
  buildTrusted: boolean;
}

export interface Deployment {
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
}

export interface LogEntry {
  id: string;
  deploymentId: string;
  sequenceNumber: number;
  timestamp: string;
  stage: string;
  stream: string;
  severity: string;
  message: string;
}

export interface DeploymentLogs {
  deploymentId: string;
  entries: LogEntry[];
  nextAfterSequence: number;
  hasMore: boolean;
}

export interface CreateProjectInput {
  name: string;
  repositoryUrl: string;
  buildTrusted: boolean;
}

export interface CreateDeploymentInput {
  sourceRef: string;
  servicePath: string | null;
}