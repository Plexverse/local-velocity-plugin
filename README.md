<img width="4096" height="843" alt="Github Repository Header" src="https://github.com/user-attachments/assets/dda2f9bd-4ef3-4df2-875a-30a18272ae3a" />
</br>
</br>

A Velocity plugin that automatically discovers and registers Minecraft servers with Velocity using the Docker socket API.

## Overview

This plugin automatically discovers Docker Swarm services and registers them with Velocity. It's designed to work with the local-docker setup where Minecraft servers are managed via Docker Swarm.

## How It Works

1. The plugin connects to the Docker socket (`/var/run/docker.sock`) mounted in the Velocity container
2. Every 10 seconds, it scans for game servers:
   - **Docker Swarm mode**: Scans Swarm services and their tasks
   - **docker-compose mode**: Scans running containers
3. It identifies game servers by looking for `com.plexverse.project.id` labels
4. For each service/container group, it registers each replica as a separate server:
   - Service `micro-battles` with 3 replicas → `micro-battles-1`, `micro-battles-2`, `micro-battles-3`
5. Servers are automatically unregistered when services/containers are removed or scaled down

The plugin automatically detects which mode (Swarm or Compose) is being used and adapts accordingly.

## Server Naming

- Server names follow the pattern: `{service-name}-{replica-number}`
- Service names are derived from the lowercased game name from project config
- Each replica gets its own server entry (e.g., `gamename-1`, `gamename-2`, etc.)

## Requirements

- Docker socket must be mounted in the Velocity container (configured in docker-compose.yml)
- Services must have `com.plexverse.project.id` label to be recognized as game servers

## Building

```bash
./gradlew build
```

The JAR will be in `build/libs/velocity-auto-register-1.0.0.jar`

## Installation

The plugin is automatically installed by the `build-minecraft-images.py` script:

1. The script downloads the latest release from GitHub (Plexverse/local-velocity-plugin) by default
2. Alternatively, you can provide a local JAR path when prompted
3. The plugin JAR is placed in `local-docker/velocity/config/` and mounted to `/data/plugins` in the Velocity container
4. The plugin will automatically start watching for server changes when Velocity starts

