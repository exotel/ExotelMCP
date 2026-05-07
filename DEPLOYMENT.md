# ExotelMCP - Deployment & Maintenance Guide

## Repository

- **GitHub**: https://github.com/exotel/ExotelMCP
- **Branch strategy**: `main` is production. Feature branches merge via PR.
- **Tech stack**: Java 21, Spring Boot 3, Spring AI MCP, Maven

## Server Details

| Field | Value |
|-------|-------|
| Instance ID | `i-06ece1e4512cc41c2` |
| Name | VerlakeServer |
| Region | ap-south-1 (Mumbai) |
| Type | t3.medium |
| Private IP | 10.22.4.100 |
| Public IP | 13.127.242.200 |
| Key pair | `demo-collections` |
| OS | Ubuntu (Linux) |

## DNS & Networking

| URL | Points to |
|-----|-----------|
| `https://mcp.exotel.com/mcp` | ALB → EC2 (port 8080) |
| DNS record | `mcp.exotel.com` → CNAME → `mcp-alb-1223548065.ap-south-1.elb.amazonaws.com` |
| ALB | Terminates SSL, forwards to EC2:8080 |
| Container | Host port 8080 → Container port 8085 |

## SSH Access

The server uses the `demo-collections` key pair. If you don't have the `.pem` file, use EC2 Instance Connect:

```bash
aws ec2-instance-connect send-ssh-public-key \
  --region ap-south-1 \
  --instance-id i-06ece1e4512cc41c2 \
  --instance-os-user ubuntu \
  --ssh-public-key file://~/.ssh/id_rsa.pub

# Immediately SSH (within 60 seconds):
ssh ubuntu@13.127.242.200
```

## Docker Setup

The app runs as a Docker container on the EC2 instance.

| Path | Purpose |
|------|---------|
| `/home/ubuntu/javadocker/` | Deployment directory |
| `/home/ubuntu/javadocker/mcp_api-0.0.1-SNAPSHOT.jar` | Active JAR |
| `/home/ubuntu/javadocker/Dockerfile` | Container definition (eclipse-temurin:21-jre) |
| `/home/ubuntu/javadocker/docker-compose.yml` | Service config (ports, env, volumes) |
| `/home/ubuntu/javadocker/data/` | H2 database (persisted) |
| `/home/ubuntu/javadocker/logs/` | Application logs |

## How to Deploy

### 1. Build the JAR locally

```bash
cd ExotelMCP
./mvnw package -DskipTests
# Output: target/mcp_api-0.0.1-SNAPSHOT.jar
```

### 2. SSH into the server

```bash
aws ec2-instance-connect send-ssh-public-key \
  --region ap-south-1 \
  --instance-id i-06ece1e4512cc41c2 \
  --instance-os-user ubuntu \
  --ssh-public-key file://~/.ssh/id_rsa.pub
```

### 3. Upload the JAR

```bash
scp target/mcp_api-0.0.1-SNAPSHOT.jar ubuntu@13.127.242.200:/home/ubuntu/javadocker/mcp_api-0.0.1-SNAPSHOT-new.jar
```

### 4. Backup and swap on the server

```bash
ssh ubuntu@13.127.242.200
cd /home/ubuntu/javadocker

# Backup current JAR
cp mcp_api-0.0.1-SNAPSHOT.jar mcp_api-0.0.1-SNAPSHOT-backup-$(date +%Y%m%d).jar

# Swap in new JAR
mv mcp_api-0.0.1-SNAPSHOT-new.jar mcp_api-0.0.1-SNAPSHOT.jar
```

### 5. Rebuild and restart

```bash
sudo docker compose down
sudo docker compose up --build -d
```

### 6. Verify

```bash
# Check container is running
sudo docker ps

# Check logs
sudo docker logs mcp-api-app --tail 30

# Test endpoint
curl -s https://mcp.exotel.com/mcp
```

## How to Revert

```bash
ssh ubuntu@13.127.242.200
cd /home/ubuntu/javadocker

# Restore backup JAR (replace date with actual backup date)
cp mcp_api-0.0.1-SNAPSHOT-backup-YYYYMMDD.jar mcp_api-0.0.1-SNAPSHOT.jar

# Rebuild and restart
sudo docker compose down
sudo docker compose up --build -d
```

## Monitoring

```bash
# Container status
sudo docker ps

# Live logs
sudo docker logs -f mcp-api-app

# Recent logs
sudo docker logs mcp-api-app --tail 100

# Disk usage
du -sh /home/ubuntu/javadocker/data/
du -sh /home/ubuntu/javadocker/logs/
```

## Existing Backup JARs

| File | Description |
|------|-------------|
| `mcp_api-0.0.1-SNAPSHOT-old-20260501.jar` | Pre-CQA version (Aug 2025 build) |
| `mcp_api-0.0.1-SNAPSHOT.jarbck` | Original backup (Aug 2025) |

## Notes

- The EC2 instance is shared with other unrelated services. Only the `/home/ubuntu/javadocker/` directory is relevant to ExotelMCP.
- There is an older Python-based MCP server at `/home/ubuntu/mcpserver_new/` which is not in use.
