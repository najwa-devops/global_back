# Docker Installation (PC)

This guide installs Docker and runs this project with Docker Compose.

## 1) Install Docker Desktop (Windows 10/11)

Run PowerShell as Administrator:

```powershell
winget install -e --id Docker.DockerDesktop
```

Then start Docker Desktop once and wait until it shows `Engine running`.

## 2) Verify installation

```powershell
docker --version
docker compose version
```

## 3) Run this project

From project root:

```powershell
cd "D:\scan oualid\Invoices"
docker compose up -d --build
```

## 4) Check status

```powershell
docker compose ps
curl.exe -s http://localhost:8089/actuator/health
```

Expected health response contains: `"status":"UP"`.

## 5) Stop project

```powershell
docker compose down
```

## 6) Reset project database (optional)

This removes MariaDB data volume and recreates schema from `db.sql`.

```powershell
docker compose down -v
docker compose up -d --build
```

---

## Optional: Linux (Ubuntu/Debian)

```bash
sudo apt-get update
sudo apt-get install -y docker.io docker-compose-plugin
sudo usermod -aG docker $USER
newgrp docker
docker --version
docker compose version
```
