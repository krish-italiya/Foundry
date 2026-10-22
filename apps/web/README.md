# Foundry Web Dashboard

The first Foundry dashboard is a React + TypeScript SPA backed by the existing Spring Boot API.

## Local development

Prerequisites:
- Node.js 20.19+ (or a compatible newer LTS release) and npm
- Java 25 for the API
- The Foundry repository cloned locally

Start the backend in one terminal from the repository root:

```powershell
cd apps/api/foundry-api
.\mvnw.cmd spring-boot:run
```

Start the dashboard in another terminal:

```powershell
cd apps/web
npm install
npm run dev
```

Open http://127.0.0.1:5173. Vite proxies `/api` and `/actuator` requests to the API at http://127.0.0.1:8080, so local development does not require browser CORS configuration.

## First dashboard slice

- Register and select projects.
- Inspect deployments for the selected project.
- Request a deployment from a source ref.
- Follow deployment status and view cursor-fetched logs.
- Open the application URL after a deployment succeeds.

The backend worker and local runtime are disabled by default. A new deployment may remain `QUEUED` unless you explicitly enable the trusted local worker/runtime and build the sample JAR. The current runtime only launches a prebuilt trusted sample JAR; it does not clone or build arbitrary repositories.

For a separately hosted frontend, set `VITE_API_BASE_URL` (see `.env.example`) to the API origin. That deployment also needs an intentional CORS and authentication configuration on the API.

This is a local development SPA, not yet a public production deployment.
