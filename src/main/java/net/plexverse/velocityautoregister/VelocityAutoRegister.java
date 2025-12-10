package net.plexverse.velocityautoregister;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Service;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import javax.inject.Inject;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Plugin(
    id = "velocity-auto-register",
    name = "Velocity Auto Register",
    version = "1.0.0",
    description = "Automatically registers Minecraft servers with Velocity using Docker API",
    authors = {"Plexverse"}
)
public class VelocityAutoRegister {
    
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private DockerClient dockerClient;
    private final Set<String> registeredServers = new HashSet<>();
    private volatile String defaultServerName = null;
    private final Map<java.util.UUID, String> pendingConnections = new HashMap<>();
    private static final Pattern COMPOSE_SCALE_PATTERN = Pattern.compile("^(.+)_(\\d+)$");
    
    @Inject
    public VelocityAutoRegister(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }
    
    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("Velocity Auto Register plugin enabled");
        
        // Initialize Docker client
        try {
            // Connect to Docker socket
            DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("unix:///var/run/docker.sock")
                .build();
            
            ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .build();
            
            dockerClient = DockerClientImpl.getInstance(config, httpClient);
            
            logger.info("Connected to Docker socket at unix:///var/run/docker.sock");
        } catch (Exception e) {
            logger.error("Failed to connect to Docker socket", e);
            return;
        }
        
        // Initial discovery
        logger.info("Starting initial server discovery...");
        discoverAndRegisterServers();
        logger.info("Initial server discovery complete. Found {} registered server(s)", registeredServers.size());
        
        // Schedule periodic discovery
        server.getScheduler().buildTask(this, this::discoverAndRegisterServers)
            .repeat(10, TimeUnit.SECONDS)
            .schedule();
        logger.info("Scheduled periodic server discovery every 10 seconds");
    }
    
    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (dockerClient != null) {
            try {
                dockerClient.close();
            } catch (Exception e) {
                logger.warn("Error closing Docker client", e);
            }
        }
    }
    
    @Subscribe
    public void onPlayerLogin(LoginEvent event) {
        // Wait for player to fully join before connecting
        server.getScheduler().buildTask(this, () -> {
            String targetServer = getDefaultServerName();
            if (targetServer != null) {
                server.getServer(targetServer).ifPresent(target -> {
                    // Track this connection attempt so we can handle kicks
                    pendingConnections.put(event.getPlayer().getUniqueId(), targetServer);
                    
                    ConnectionRequestBuilder connectionRequest = event.getPlayer().createConnectionRequest(target);
                    connectionRequest.connect().thenAccept(result -> {
                        if (result.isSuccessful()) {
                            logger.info("Successfully connected player {} to default server: {}", 
                                event.getPlayer().getUsername(), targetServer);
                            // Remove from pending after successful connection
                            pendingConnections.remove(event.getPlayer().getUniqueId());
                        } else {
                            // Connection failed - try to get reason from result
                            String reason = "Connection failed";
                            if (result.getReasonComponent().isPresent()) {
                                Component reasonComponent = result.getReasonComponent().get();
                                reason = LegacyComponentSerializer.legacySection().serialize(reasonComponent);
                            } else if (result.getStatus() != null) {
                                reason = result.getStatus().name();
                            }
                            
                            logger.warn("Failed to connect player {} to default server {}: {}", 
                                event.getPlayer().getUsername(), targetServer, reason);
                            
                            // Remove from pending
                            pendingConnections.remove(event.getPlayer().getUniqueId());
                            
                            // Forward the failure reason to the player
                            event.getPlayer().disconnect(Component.text("Failed to connect to " + targetServer + ": " + reason));
                        }
                    }).exceptionally(throwable -> {
                        logger.error("Error connecting player {} to default server {}: {}", 
                            event.getPlayer().getUsername(), targetServer, throwable.getMessage(), throwable);
                        pendingConnections.remove(event.getPlayer().getUniqueId());
                        event.getPlayer().disconnect(Component.text("Failed to connect to " + targetServer + ": " + 
                            (throwable.getMessage() != null ? throwable.getMessage() : "Connection error")));
                        return null;
                    });
                    
                    logger.info("Connecting player {} to default server: {}", event.getPlayer().getUsername(), targetServer);
                });
            } else {
                logger.warn("No default server available for player {}", event.getPlayer().getUsername());
                event.getPlayer().disconnect(Component.text("No servers available. Please try again later."));
            }
        }).schedule();
    }
    
    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        // Check if this is a kick from a pending default server connection
        String pendingServer = pendingConnections.get(event.getPlayer().getUniqueId());
        if (pendingServer != null && event.getServer().getServerInfo().getName().equals(pendingServer)) {
            // Get the server's kick reason
            Component kickReason = event.getServerKickReason().orElse(Component.text("Disconnected from server"));
            String reasonText = LegacyComponentSerializer.legacySection().serialize(kickReason);
            
            logger.warn("Player {} was kicked from default server {}: {}", 
                event.getPlayer().getUsername(), pendingServer, reasonText);
            
            // Remove from pending
            pendingConnections.remove(event.getPlayer().getUniqueId());
            
            // Forward the server's rejection reason to the player
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(kickReason));
        }
    }
    
    private String getDefaultServerName() {
        // Return cached default if still valid
        if (defaultServerName != null && server.getServer(defaultServerName).isPresent()) {
            return defaultServerName;
        }
        
        // Find new default server
        updateDefaultServer();
        return defaultServerName;
    }
    
    private void updateDefaultServer() {
        // Prefer server with 'lobby' in the name
        Optional<String> lobbyServer = registeredServers.stream()
            .filter(name -> name.toLowerCase().contains("lobby"))
            .filter(name -> server.getServer(name).isPresent())
            .findFirst();
        
        if (lobbyServer.isPresent()) {
            defaultServerName = lobbyServer.get();
            logger.info("Default server set to: {} (contains 'lobby')", defaultServerName);
            return;
        }
        
        // Otherwise use first available server
        Optional<String> firstServer = registeredServers.stream()
            .filter(name -> server.getServer(name).isPresent())
            .sorted()
            .findFirst();
        
        if (firstServer.isPresent()) {
            defaultServerName = firstServer.get();
            logger.info("Default server set to: {} (first available)", defaultServerName);
        } else {
            defaultServerName = null;
            logger.warn("No servers available for default connection");
        }
    }
    
    private void discoverAndRegisterServers() {
        try {
            Set<String> currentServers = new HashSet<>();
            int foundCount = 0;
            int registeredCount = 0;
            
            // Try Docker Swarm mode first
            try {
                List<Service> services = dockerClient.listServicesCmd().exec();
                logger.debug("Found {} Docker service(s) in Swarm mode", services.size());
                if (!services.isEmpty()) {
                    foundCount = discoverFromSwarmServices(services, currentServers);
                    registeredCount = currentServers.size();
                    logger.info("Swarm mode: Found {} server(s), {} currently registered", foundCount, registeredCount);
                } else {
                    logger.debug("No services found in Swarm mode, trying container mode");
                    discoverFromContainers(currentServers);
                }
            } catch (Exception e) {
                // Not in Swarm mode or error, try container mode
                logger.debug("Swarm mode not available ({}), trying container mode", e.getMessage());
                foundCount = discoverFromContainers(currentServers);
                registeredCount = currentServers.size();
                logger.info("Container mode: Found {} server(s), {} currently registered", foundCount, registeredCount);
            }
            
            // Unregister servers that are no longer running
            Set<String> toRemove = new HashSet<>(registeredServers);
            toRemove.removeAll(currentServers);
            if (!toRemove.isEmpty()) {
                logger.info("Removing {} server(s) that are no longer running: {}", toRemove.size(), toRemove);
                for (String serverName : toRemove) {
                    server.getServer(serverName).ifPresent(registeredServer -> {
                        server.unregisterServer(registeredServer.getServerInfo());
                        registeredServers.remove(serverName);
                        logger.info("Unregistered server: {} (no longer running)", serverName);
                    });
                }
            }
            
            // Update default server after registration changes
            updateDefaultServer();
            
            logger.debug("Discovery complete. Total registered: {}, Current running: {}", 
                registeredServers.size(), currentServers.size());
            
        } catch (Exception e) {
            logger.error("Error discovering servers from Docker", e);
        }
    }
    
    private int discoverFromSwarmServices(List<Service> services, Set<String> currentServers) {
        int totalFound = 0;
        int newlyRegistered = 0;
        
        logger.debug("Scanning {} service(s) in Swarm mode", services.size());
        
        for (Service service : services) {
            String fullServiceName = service.getSpec().getName();
            String serviceName = fullServiceName;
            
            // Remove stack prefix if present (e.g., "local-docker_" prefix)
            if (fullServiceName.contains("_")) {
                serviceName = fullServiceName.substring(fullServiceName.indexOf("_") + 1);
            }
            
            // Skip velocity service
            if ("velocity".equals(serviceName)) {
                logger.debug("Skipping velocity service");
                continue;
            }
            
            // Check if this is a Minecraft game server service
            // Labels are on ContainerSpec, not Service Spec
            Map<String, String> labels = null;
            if (service.getSpec().getTaskTemplate() != null && 
                service.getSpec().getTaskTemplate().getContainerSpec() != null) {
                labels = service.getSpec().getTaskTemplate().getContainerSpec().getLabels();
            }
            
            // Fallback to service labels if container labels not available
            if (labels == null || labels.isEmpty()) {
                labels = service.getSpec().getLabels();
            }
            
            if (labels == null || labels.isEmpty()) {
                logger.debug("Service {} has no labels, skipping", fullServiceName);
                continue;
            }
            
            String projectId = labels.get("com.plexverse.project.id");
            
            // Skip if not a game server (no project label)
            if (projectId == null) {
                logger.debug("Service {} has no com.plexverse.project.id label, skipping", fullServiceName);
                continue;
            }
            
            logger.debug("Found game server service: {} (project ID: {})", fullServiceName, projectId);
            
            // Get running tasks for this service
            List<com.github.dockerjava.api.model.Task> tasks = dockerClient.listTasksCmd()
                .withServiceFilter(service.getId())
                .exec();
            
            // Filter to only running and healthy tasks
            tasks = tasks.stream()
                .filter(task -> {
                    com.github.dockerjava.api.model.TaskState state = task.getStatus().getState();
                    if (state != com.github.dockerjava.api.model.TaskState.RUNNING) {
                        logger.debug("Task {} is not running (state: {})", task.getId(), state);
                        return false;
                    }
                    
                    // Check if task has an error message (indicates unhealthy)
                    String err = task.getStatus().getErr();
                    if (err != null && !err.isEmpty()) {
                        logger.debug("Task {} has error: {}", task.getId(), err);
                        return false;
                    }
                    
                    // Check container health status if available
                    // In Swarm mode, we check the task's desired state matches running state
                    // and there's no error message
                    return true;
                })
                .collect(java.util.stream.Collectors.toList());
            
            logger.debug("Service {} has {} healthy running task(s) (filtered from {} total)", 
                fullServiceName, tasks.size(), dockerClient.listTasksCmd().withServiceFilter(service.getId()).exec().size());
            
            // Register each replica as a separate server (gamename-1, gamename-2, etc.)
            int replicaIndex = 1;
            for (com.github.dockerjava.api.model.Task task : tasks) {
                String serverName = serviceName + "-" + replicaIndex;
                currentServers.add(serverName);
                totalFound++;
                
                if (!server.getServer(serverName).isPresent()) {
                    // Use full service name for internal Docker network DNS resolution
                    // No host ports exposed - all communication is internal
                    String address = fullServiceName; // Full service name resolves in Docker overlay network
                    int port = 25565; // Internal port (not exposed on host)
                    
                    ServerInfo serverInfo = new ServerInfo(serverName, new java.net.InetSocketAddress(address, port));
                    server.registerServer(serverInfo);
                    registeredServers.add(serverName);
                    newlyRegistered++;
                    logger.info("Registered healthy server: {} at {}:{} (internal network, replica {}/{}, service: {})", 
                        serverName, address, port, replicaIndex, tasks.size(), fullServiceName);
                } else {
                    logger.debug("Server {} already registered", serverName);
                }
                
                replicaIndex++;
            }
        }
        
        if (newlyRegistered > 0) {
            logger.info("Swarm discovery: Registered {} new server(s) from {} service(s)", newlyRegistered, services.size());
        }
        
        return totalFound;
    }
    
    private int discoverFromContainers(Set<String> currentServers) {
        // List all running containers
        List<Container> containers = dockerClient.listContainersCmd()
            .withStatusFilter(Collections.singleton("running"))
            .exec();
        
        logger.debug("Found {} running container(s)", containers.size());
        
        // Filter to only healthy containers
        containers = containers.stream()
            .filter(container -> {
                // Check container health status
                String health = container.getStatus();
                if (health != null) {
                    // Status format: "Up X seconds (healthy)" or "Up X seconds (unhealthy)" or "Up X seconds"
                    // If explicitly unhealthy, skip it
                    if (health.contains("(unhealthy)")) {
                        logger.debug("Container {} is unhealthy: {}", 
                            container.getNames() != null && container.getNames().length > 0 ? container.getNames()[0] : container.getId(), 
                            health);
                        return false;
                    }
                    // If healthcheck exists and shows starting, wait for it to become healthy
                    // Format: "Up X seconds (health: starting)" -> wait
                    if (health.contains("(health: starting)")) {
                        logger.debug("Container {} health check still starting: {}", 
                            container.getNames() != null && container.getNames().length > 0 ? container.getNames()[0] : container.getId(), 
                            health);
                        return false;
                    }
                    // If healthcheck exists and shows unhealthy, skip it
                    if (health.contains("(health: unhealthy)")) {
                        logger.debug("Container {} health check unhealthy: {}", 
                            container.getNames() != null && container.getNames().length > 0 ? container.getNames()[0] : container.getId(), 
                            health);
                        return false;
                    }
                    // Containers without healthcheck or with "healthy" status are allowed
                    // Status like "Up X seconds" (no health info) means no healthcheck configured - allow it
                }
                return true;
            })
            .collect(java.util.stream.Collectors.toList());
        
        logger.debug("Found {} healthy running container(s) (filtered from {} total)", 
            containers.size(), dockerClient.listContainersCmd().withStatusFilter(Collections.singleton("running")).exec().size());
        
        // Group containers by base service name
        Map<String, List<Container>> serviceContainers = new HashMap<>();
        int gameServerContainers = 0;
        
        for (Container container : containers) {
            // Get labels from container
            Map<String, String> labels = container.getLabels();
            if (labels == null) {
                logger.debug("Container {} has no labels, skipping", 
                    container.getNames() != null && container.getNames().length > 0 ? container.getNames()[0] : "unknown");
                continue;
            }
            
            String projectId = labels.get("com.plexverse.project.id");
            if (projectId == null) continue; // Not a game server
            
            // Get container names
            String[] names = container.getNames();
            if (names == null || names.length == 0) continue;
            
            // Container name format: /local-docker_micro-battles_1 or /local-docker-micro-battles-1
            // Extract base service name
            String containerName = names[0].startsWith("/") ? names[0].substring(1) : names[0];
            
            // Skip velocity container
            if (containerName.contains("velocity")) {
                logger.debug("Skipping velocity container: {}", containerName);
                continue;
            }
            
            logger.debug("Found game server container: {} (project ID: {})", containerName, projectId);
            gameServerContainers++;
            
            // Extract base service name (remove scale suffix and stack prefix)
            String baseServiceName = extractBaseServiceName(containerName);
            logger.debug("Extracted base service name: {} from container: {}", baseServiceName, containerName);
            
            serviceContainers.computeIfAbsent(baseServiceName, k -> new ArrayList<>()).add(container);
        }
        
        logger.debug("Found {} game server container(s) across {} service(s)", 
            gameServerContainers, serviceContainers.size());
        
        int totalFound = 0;
        int newlyRegistered = 0;
        
        // Register each container as a server
        for (Map.Entry<String, List<Container>> entry : serviceContainers.entrySet()) {
            String baseServiceName = entry.getKey();
            List<Container> serviceContainersList = entry.getValue();
            
            logger.debug("Processing service: {} with {} container(s)", baseServiceName, serviceContainersList.size());
            
            // Sort containers by name to ensure consistent numbering
            serviceContainersList.sort(Comparator.comparing(c -> {
                String[] names = c.getNames();
                return names != null && names.length > 0 ? names[0] : "";
            }));
            
            int replicaIndex = 1;
            for (Container container : serviceContainersList) {
                String serverName = baseServiceName + "-" + replicaIndex;
                currentServers.add(serverName);
                totalFound++;
                
                if (!server.getServer(serverName).isPresent()) {
                    // Use container name for address (Docker network DNS)
                    String[] names = container.getNames();
                    String containerName = names != null && names.length > 0 
                        ? (names[0].startsWith("/") ? names[0].substring(1) : names[0])
                        : baseServiceName;
                    
                    // In docker-compose, use the service name (base name) for DNS resolution
                    // No host ports exposed - all communication is internal
                    String address = baseServiceName; // Service name resolves in Docker bridge network
                    int port = 25565; // Internal port (not exposed on host)
                    
                    ServerInfo serverInfo = new ServerInfo(serverName, new java.net.InetSocketAddress(address, port));
                    server.registerServer(serverInfo);
                    registeredServers.add(serverName);
                    newlyRegistered++;
                    logger.info("Registered healthy server: {} at {}:{} (internal network, container: {}, replica {}/{})", 
                        serverName, address, port, containerName, replicaIndex, serviceContainersList.size());
                } else {
                    logger.debug("Server {} already registered", serverName);
                }
                
                replicaIndex++;
            }
        }
        
        if (newlyRegistered > 0) {
            logger.info("Container discovery: Registered {} new server(s) from {} container(s)", 
                newlyRegistered, gameServerContainers);
        }
        
        return totalFound;
    }
    
    private String extractBaseServiceName(String containerName) {
        // Handle docker-compose naming: local-docker_micro-battles_1 or local-docker-micro-battles-1
        // Remove stack prefix if present
        String name = containerName;
        if (name.contains("_")) {
            // Format: stack_service_replica
            String[] parts = name.split("_");
            if (parts.length >= 2) {
                // Check if last part is a number (replica number)
                try {
                    Integer.parseInt(parts[parts.length - 1]);
                    // Last part is replica number, service name is everything before it
                    name = String.join("_", Arrays.copyOf(parts, parts.length - 1));
                } catch (NumberFormatException e) {
                    // Not a replica number, use as-is
                }
            }
            // Remove stack prefix (first part before _)
            if (name.contains("_")) {
                name = name.substring(name.indexOf("_") + 1);
            }
        } else if (name.contains("-")) {
            // Format: stack-service-replica
            // Try to extract base name (remove last number if it's a replica)
            java.util.regex.Matcher matcher = COMPOSE_SCALE_PATTERN.matcher(name);
            if (matcher.matches()) {
                name = matcher.group(1);
            }
            // Remove stack prefix if present (first part before first -)
            if (name.contains("-") && name.split("-").length > 1) {
                // Check if it looks like stack-service format
                String[] parts = name.split("-");
                // If first part looks like a stack name (e.g., "local", "docker"), remove it
                if (parts.length > 1 && (parts[0].equals("local") || parts[0].equals("docker"))) {
                    name = String.join("-", Arrays.copyOfRange(parts, 1, parts.length));
                }
            }
        }
        
        return name;
    }
}
