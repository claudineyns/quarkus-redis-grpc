# infra/podman — local SonarQube + analysis

Scripts to run the test suite with SonarQube analysis against a local
SonarQube started on podman. Intended for occasional/local quality checks.

## Scripts

| Script | Purpose |
|---|---|
| `env.sh` | Shared variables (container name, image, port, token file). |
| `start-sonarqube.sh` | Start SonarQube on podman, wait until UP, set the admin password, generate a global analysis token, and save it to `temp/sonar-token` (git-ignored). |
| `run-analysis.sh` | `mvn clean verify` + the Sonar scanner, using the saved token (or `$SONAR_TOKEN`). |
| `stop-sonarqube.sh` | Stop and remove the SonarQube container. |

## Usage

```bash
bash infra/podman/start-sonarqube.sh     # one-time; takes a few minutes
bash infra/podman/run-analysis.sh        # tests + coverage + analysis
bash infra/podman/stop-sonarqube.sh      # when done
```

Defaults (override by exporting): `SONAR_HOST_PORT=9090`,
`SONAR_CONTAINER=redis-grpc-sonarqube`. Console at `http://localhost:9090`
(`admin` / `SonarAdmin1!`).

The project's Sonar settings (project key, Java source level, Jacoco XML
coverage path, the `java:S6813` field-injection exemption) live in `pom.xml`.
