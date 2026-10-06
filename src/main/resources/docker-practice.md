# Docker 学习练手文档（Java 初级开发向）

面向 Java 初级开发。完成本文全部练习后，应能：用 Docker 跑起 MySQL/Redis/RabbitMQ、看懂并编写简单 Dockerfile 把 Spring Boot 项目打成镜像、用 Docker Compose 一键编排本地开发环境。

学习环境：Linux（Ubuntu/Debian/CentOS），bash 执行命令。

---

## 一、先搞清 5 个核心概念

| 概念 | 类比说明（仅辅助理解） | 关键点 |
|---|---|---|
| 镜像 Image | 程序的安装包 + 运行环境的只读模板 | 只读、分层、用 `镜像名:标签` 定位，如 `mysql:8.0` |
| 容器 Container | 镜像跑起来的一个运行实例 | 一个镜像可以同时跑多个容器；容器删除后内部数据默认丢失 |
| 仓库 Registry | 存放镜像的服务器 | 默认 Docker Hub；命令 `docker pull/push` 与之交互 |
| 数据卷 Volume | 容器外独立管理的存储 | 解决容器删了数据就没的问题 |
| Dockerfile | 构建镜像的配方文本 | `docker build` 按它生成自定义镜像 |

核心关系：

```
Dockerfile --build--> 镜像 --run--> 容器（可多个）
                        ↑ pull/push
                      仓库（Docker Hub）
```

> 初级阶段只需知道：Docker 用 Linux namespace/cgroup 做隔离和资源限制，不要求掌握底层。

---

## 二、环境准备

### 1. 安装

Ubuntu/Debian 使用官方源安装（CentOS 用 yum/dnf，包名相同）：

```bash
# Ubuntu/Debian
sudo apt-get update
sudo apt-get install -y docker.io docker-compose-plugin

# CentOS/RHEL
sudo yum install -y docker docker-compose-plugin
sudo systemctl enable --now docker
```

普通用户执行 docker 会报 permission denied，将当前用户加入 docker 组（重新登录后生效）：

```bash
sudo usermod -aG docker $USER
# 重新登录，或临时生效：newgrp docker
```

安装完成后验证：

```bash
docker version         # 能同时看到 Client 和 Server 两段信息，说明引擎正常
docker run hello-world # 跑通官方测试镜像
```

### 2. 配置镜像加速（国内网络）

编辑 `/etc/docker/daemon.json`（没有则新建）：

```bash
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": ["https://你的加速地址"]
}
EOF
sudo systemctl daemon-reload
sudo systemctl restart docker
```

加速地址时效性强、经常变动，以当前可用的国内镜像源为准（需确认）。公司内网通常有私有加速器，问运维要地址。

### 3. 命令与当前目录的关系（高频困惑点）

| 命令 | 是否依赖当前终端所在目录 |
|---|---|
| `docker run`、`docker ps`、`docker pull`、`docker images` | 不依赖，在哪执行都一样 |
| `docker build .` | 依赖，`.` 表示构建上下文目录，该目录下必须有 Dockerfile |
| `docker compose up` | 依赖，当前目录必须有 `docker-compose.yml`（或用 `-f` 指定路径） |
| `-v` 挂载中的相对路径 `./data` | 依赖，相对于执行命令时的目录解析 |

---

## 三、练习 0：跑起第一个容器并观察生命周期

```bash
# 拉取镜像
docker pull nginx:1.27

# 启动容器：后台运行、命名、映射 8080 到容器 80
docker run -d --name web-demo -p 8080:80 nginx:1.27
```

浏览器访问 `http://localhost:8080`，看到 Nginx 欢迎页即成功。

按顺序执行，观察输出：

```bash
docker ps                    # 查看运行中的容器，STATUS 为 Up
docker stop web-demo         # 停止
docker ps                    # 列表空了
docker ps -a                 # 加 -a 才能看到已停止的容器
docker start web-demo        # 再次启动（数据和配置还在）
docker logs web-demo         # 查看容器日志
docker logs -f web-demo     # 持续跟踪日志，Ctrl+C 退出（不会停容器）
docker rm -f web-demo        # 强制删除容器
```

### 观察结论

- `stop/start` 操作同一个容器，容器内之前的改动还在；`rm` 后一切消失（未挂载卷的数据一并删除）。
- 容器名是你管理容器的唯一标识，`--name` 必加。

---

## 四、docker run 常用参数（必须掌握）

```bash
docker run -d \
  --name 容器名 \
  -p 宿主机端口:容器端口 \
  -e 环境变量名=值 \
  -v 数据卷名或宿主机目录:容器内目录 \
  --network 网络名 \
  镜像名:标签
```

| 参数 | 作用 | 说明 |
|---|---|---|
| `-d` | 后台运行 | 服务类容器必加；不加会占用终端打印日志 |
| `--name` | 指定容器名 | 后续 stop/start/logs/exec 都靠它 |
| `-p 主机端口:容器端口` | 端口映射 | 可写多个，每条建立一条独立转发规则 |
| `-e KEY=VALUE` | 注入环境变量 | 中间件的密码、配置常靠它传入 |
| `-v A:B` | 挂载数据卷/目录 | 持久化数据、注入配置文件 |
| `--network` | 加入指定网络 | 同一自定义网络内容器可用容器名互访 |
| `-it` | 交互式 + 分配终端 | 进容器排查时配合 shell 使用 |
| `--rm` | 容器停止后自动删除 | 临时执行一次性任务时用 |

### 关于多个 -p（重点）

- `-p` 是 Docker 引擎的能力，对所有镜像都能用，与镜像无关。
- 容器内服务监听哪个端口由服务程序自己决定（Redis 默认 6379、MySQL 3306），Docker 只做转发。
- 映射到容器内没有程序监听的端口：容器照常启动不报错，但访问该端口报 Connection refused；其他映射正确的端口不受影响。

```bash
# 宿主机 6379 和 6380 两个入口都转发到同一个 Redis（6379）
docker run -d --name redis-demo -p 6379:6379 -p 6380:6379 redis:7
```

### 进入正在运行的容器排查

```bash
docker exec -it 容器名 bash      # 大多数 Linux 基础镜像有 bash
docker exec -it 容器名 sh        # 精简镜像（Alpine 系）只有 sh
```

---

## 五、练习 1：用 Docker 跑起项目依赖的三件套

项目（Spring Boot + MyBatis/JPA + Redis + RabbitMQ）本地开发所需中间件，全部容器化。

### 1. MySQL 8

```bash
docker run -d --name mysql-learning \
  -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=123456 \
  -e MYSQL_DATABASE=demo \
  -v mysql-data:/var/lib/mysql \
  mysql:8.0
```

- 行尾 `\` 是 bash 的换行续行符；写成一行则不需要。
- `-v mysql-data:/var/lib/mysql`：命名卷挂载，数据库文件保存在卷里，删容器不丢数据。

验证：

```bash
docker exec -it mysql-learning mysql -uroot -p123456
# 进入后执行 show databases;
```

### 2. Redis 7

```bash
docker run -d --name redis-learning \
  -p 6379:6379 \
  -v redis-data:/data \
  redis:7 redis-server --appendonly yes
```

- 镜像名后的 `redis-server --appendonly yes` 是传给容器主进程的启动参数，开启 AOF 持久化。

验证：

```bash
docker exec -it redis-learning redis-cli
# 进入后执行 ping，返回 PONG
```

### 3. RabbitMQ（与 rabbitmq-practice.md 一致）

```bash
docker run -d --name rabbitmq-learning \
  -p 5672:5672 \
  -p 15672:15672 \
  rabbitmq:3.13-management
```

- 5672：应用 AMQP 连接；15672：Web 管理台（`guest/guest`）。
- `management` 后缀的镜像才带管理台插件。

### 应用连接方式

应用直接在本机（IDEA）跑时，`application.properties` 连本机映射端口：

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/demo?useSSL=false&serverTimezone=Asia/Shanghai
spring.datasource.username=root
spring.datasource.password=123456
spring.redis.host=localhost
spring.redis.port=6379
spring.rabbitmq.host=localhost
spring.rabbitmq.port=5672
```

---

## 六、镜像与容器管理命令速查

### 镜像

```bash
docker images                 # 本地镜像列表
docker pull mysql:8.0         # 拉取镜像（不写标签默认 latest）
docker rmi mysql:8.0          # 删除镜像（有容器引用时删不掉，先删容器）
docker image inspect 镜像名    # 查看镜像详情（环境变量、暴露端口、默认启动命令）
docker tag 旧名:标签 新名:标签 # 重命名/打标签，常用于推送前改名
```

### 容器

```bash
docker ps                     # 运行中的容器
docker ps -a                  # 所有容器（含已停止）
docker stop/start/restart 名  # 停止/启动/重启
docker rm 容器名               # 删除已停止的容器；加 -f 强删运行中的
docker logs [-f] [--tail 100] 容器名
docker exec -it 容器名 sh      # 进容器
docker stats                  # 实时查看各容器 CPU/内存占用
docker cp 容器名:/路径 本地路径 # 容器与宿主机互相拷贝文件
```

### 清理（执行前确认）

```bash
docker container prune        # 删除所有已停止的容器
docker volume prune           # 删除未被使用的卷（注意数据）
docker system prune           # 清理停止容器、悬空镜像、无用网络（不含卷，加 --volumes 才含）
```

---

## 七、数据持久化：Volume 与目录挂载

不挂载时，容器内写入的所有数据随 `docker rm` 消失。两种挂载方式：

| 方式 | 写法 | 特点 | 适用 |
|---|---|---|---|
| 命名卷 Volume | `-v mysql-data:/var/lib/mysql` | Docker 统一管理实际存储位置，跨平台行为一致 | 数据库数据（推荐） |
| 绑定挂载 Bind Mount | `-v /opt/docker/nginx/conf.d:/etc/nginx/conf.d:ro` | 直接挂宿主机指定目录，改文件容器内即时可见 | 挂配置文件、开发时挂代码 |

- `:ro` 表示只读，容器内只能读不能改，挂配置时建议加。
- 宿主机路径用绝对路径，如 `-v /opt/docker/redis/redis.conf:/etc/redis/redis.conf`，且文件需提前创建。
- CentOS/RHEL 开启 SELinux 时挂载可能报权限拒绝，可在挂载末尾加 `:Z`（如 `-v ./conf:/etc/nginx/conf.d:Z`），学习机也可临时 `sudo setenforce 0` 验证。

```bash
docker volume ls              # 查看所有卷
docker volume inspect mysql-data   # 查看卷在宿主机的实际位置
```

> 易错点：MySQL/Redis 容器首次启动时，空数据卷才会触发初始化；卷里已有数据后，再改 `MYSQL_ROOT_PASSWORD` 等环境变量不会生效。

---

## 八、练习 2：容器网络与互联

目标：让容器之间用「容器名」互相访问，而不是写死 IP。

### 步骤

```bash
# 1. 创建自定义桥接网络
docker network create app-net

# 2. 三件套都加入该网络（先删掉练习 1 中的旧容器再执行）
docker run -d --name mysql-learning    --network app-net -e MYSQL_ROOT_PASSWORD=123456 -v mysql-data:/var/lib/mysql mysql:8.0
docker run -d --name redis-learning    --network app-net redis:7
docker run -d --name rabbitmq-learning --network app-net -p 5672:5672 -p 15672:15672 rabbitmq:3.13-management

# 3. 起一个临时容器验证用容器名连通 Redis
docker run -it --rm --network app-net redis:7 redis-cli -h redis-learning ping
```

返回 `PONG` 即成功，`-h redis-learning` 用的是容器名而非 IP。

### 规则

| 场景 | 连接地址写法 |
|---|---|
| 应用跑在本机 IDEA | `localhost:映射端口` |
| 应用也跑在容器里，且与中间件同一自定义网络 | `容器名:容器内端口`（如 `mysql-learning:3306`，不经过 `-p`） |

默认 bridge 网络不能用容器名解析，必须自己 `docker network create`。

---

## 九、练习 3：把本项目 Spring Boot 打成镜像

### 1. 先打出可执行 jar

```bash
mvn clean package -DskipTests
# 产物：target/untitled-1.0-SNAPSHOT.jar
```

### 2. 在项目根目录（pom.xml 同级）新建 Dockerfile

文件名固定为 `Dockerfile`，无后缀：

```dockerfile
# 基础镜像：只含 JRE 11，体积比 JDK 小
FROM eclipse-temurin:11-jre

# 容器内工作目录，后续相对路径都基于它
WORKDIR /app

# 把 jar 拷进镜像
COPY target/*.jar app.jar

# 声明容器对外端口（仅文档作用，真正映射仍需 run -p）
EXPOSE 8080

# 容器启动命令，exec 形式，Java 进程作为 1 号进程能接收停止信号
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

### 3. 同目录新建 .dockerignore

避免把无关文件打进构建上下文、拖慢构建：

```
target/*
!target/*.jar
.git
.idea
*.md
```

### 4. 构建并运行

```bash
# 构建：-t 给镜像打名字和标签，最后的 . 是构建上下文目录
docker build -t demo-app:1.0 .

# 运行（与中间件同一网络，主机名用容器名）
docker run -d --name demo-app \
  --network app-net \
  -p 8080:8080 \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://mysql-learning:3306/demo?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" \
  -e SPRING_DATASOURCE_USERNAME=root \
  -e SPRING_DATASOURCE_PASSWORD=123456 \
  -e SPRING_REDIS_HOST=redis-learning \
  -e SPRING_RABBITMQ_HOST=rabbitmq-learning \
  demo-app:1.0
```

- Spring Boot 支持用环境变量覆盖任意配置：`SPRING_DATASOURCE_URL` 对应 `spring.datasource.url`。
- 验证：`docker logs -f demo-app` 看到启动成功，访问 `http://localhost:8080`。
- 注意：此时数据库 `demo` 需要已存在（MySQL 容器加 `-e MYSQL_DATABASE=demo` 创建，或手动建库）。

### 5. 修改代码后的更新流程

```bash
mvn clean package -DskipTests
docker build -t demo-app:1.1 .
docker rm -f demo-app
docker run ... demo-app:1.1
```

### Dockerfile 初级必知指令

| 指令 | 作用 |
|---|---|
| FROM | 基础镜像，一切构建从它开始 |
| WORKDIR | 设工作目录（不存在会创建），替代多层 `cd` |
| COPY | 拷贝宿主机文件进镜像 |
| ENV | 设环境变量 |
| EXPOSE | 声明端口（文档性质） |
| ENTRYPOINT / CMD | 容器启动执行的命令；Java 服务固定用 ENTRYPOINT |

---

## 十、练习 4：Docker Compose 一键编排

Compose 解决的问题：练习 1~3 要手敲 4 条 `docker run` 和一堆参数。把它们写进一个 YAML，一条命令全起。

### 1. 项目根目录新建 docker-compose.yml

```yaml
services:
  mysql:
    image: mysql:8.0
    container_name: mysql-learning
    environment:
      MYSQL_ROOT_PASSWORD: "123456"
      MYSQL_DATABASE: "demo"
    ports:
      - "3306:3306"
    volumes:
      - mysql-data:/var/lib/mysql

  redis:
    image: redis:7
    container_name: redis-learning
    command: redis-server --appendonly yes
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data

  rabbitmq:
    image: rabbitmq:3.13-management
    container_name: rabbitmq-learning
    ports:
      - "5672:5672"
      - "15672:15672"

  app:
    build: .                 # 用当前目录 Dockerfile 构建
    container_name: demo-app
    depends_on:              # 只保证启动顺序，不保证中间件已就绪
      - mysql
      - redis
      - rabbitmq
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/demo?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
      SPRING_DATASOURCE_USERNAME: root
      SPRING_DATASOURCE_PASSWORD: "123456"
      SPRING_REDIS_HOST: redis
      SPRING_RABBITMQ_HOST: rabbitmq

volumes:
  mysql-data:
  redis-data:
```

说明：

- Compose 自动创建一个网络，所有 service 默认在同一网络内，主机名直接用服务名（mysql/redis/rabbitmq）。
- `depends_on` 只等容器启动、不等 MySQL 真正能连；Spring Boot 自带连接重试，本地学习一般够用。
- Compose v2 命令为 `docker compose`（老版本是独立的 `docker-compose`，语法基本相同）。

### 2. 常用命令（在 yml 所在目录执行）

```bash
docker compose up -d        # 后台构建并启动全部服务
docker compose ps           # 查看本项目各服务状态
docker compose logs -f app  # 跟踪某个服务日志
docker compose stop         # 停止全部
docker compose start        # 再次启动
docker compose down         # 停止并删除容器和网络（卷保留，数据不丢）
docker compose down -v      # 连数据卷一起删（数据库清空，慎用）
docker compose up -d --build app   # 改了代码/Dockerfile 后重建指定服务
```

---

## 十一、镜像仓库（了解会用即可）

```bash
# 1. 登录 Docker Hub（需先在官网注册）
docker login

# 2. 给镜像打标准标签：用户名/仓库名:标签
docker tag demo-app:1.0 你的用户名/demo-app:1.0

# 3. 推送
docker push 你的用户名/demo-app:1.0

# 4. 别人拉取
docker pull 你的用户名/demo-app:1.0
```

公司环境一般用私有仓库（Harbor 等），登录地址和命名规范以公司为准。

---

## 十二、常见报错排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `docker run` 报 port is already allocated | 宿主机端口被占用（本机已装 MySQL/Redis 等） | 改宿主端口，如 `-p 3307:3306`；或停掉占用端口的程序 |
| `docker: Cannot connect to the Docker daemon` | Docker 服务没启动，或当前用户无权限 | `sudo systemctl start docker`；普通用户执行 `sudo usermod -aG docker $USER` 后重新登录 |
| `permission denied while trying to connect` | 当前用户不在 docker 组 | 同上加组重新登录；临时用 `sudo docker ...` |
| 拉镜像超时 / TLS handshake timeout | 网络访问 Docker Hub 慢 | 配置 registry-mirrors 加速；公司网络检查代理 |
| 容器一启动就 Exited | 主进程退出（配置错误、密码未传、数据目录权限等） | `docker logs 容器名` 看具体错误 |
| MySQL 改了密码环境变量不生效 | 数据卷已初始化过，环境变量只在首次生效 | 清空卷重来：`docker compose down -v` 或删卷 |
| 应用容器连不上 mysql-learning | 不在同一网络 / 用了 localhost | 加入同一自定义网络；连接地址写容器名（服务名） |
| 访问映射端口 Connection refused | 容器内该端口没有程序监听，或防火墙拦截 | `docker exec` 进容器确认服务真实端口；`sudo firewall-cmd`/云安全组放行端口 |
| `bind: source path not found` | bind mount 的宿主机目录不存在 | 先在宿主机 `mkdir -p` 创建目录和配置文件 |
| 挂载后容器内读不到文件/日志报 Permission denied | 宿主机目录权限不足，或 SELinux 拦截 | 调整目录权限属主；SELinux 场景挂载加 `:Z`，或 `sudo setenforce 0` 验证 |
| `docker build` 很慢 | 构建上下文太大 | 配好 `.dockerignore`；基础镜像已被分层缓存 |

排查通用三板斧：

```bash
docker ps -a               # 容器是否在运行，还是 Exited
docker logs 容器名          # 主进程报了什么错
docker exec -it 容器名 sh   # 进去看配置、端口、网络
```

---

## 十三、完成标准 Checklist

- [ ] 能说清镜像、容器、仓库、数据卷四者的关系
- [ ] 不看文档能用 `docker run` 配 `-d/--name/-p/-e/-v` 跑起一个中间件
- [ ] 能解释多个 `-p` 的含义，以及映射到不存在端口会发生什么
- [ ] 能说清什么时候用 `localhost:端口`、什么时候用 `容器名:端口`
- [ ] 知道容器删除后数据为什么会丢，能用命名卷解决持久化
- [ ] 能独立为本项目写 Dockerfile 并 `docker build` 出镜像、运行验证
- [ ] 能用 docker-compose.yml 一条命令起齐 MySQL + Redis + RabbitMQ + 应用
- [ ] 容器启动失败时会按 `ps -a → logs → exec` 的顺序排查

全部打勾后，Docker 在 Java 日常开发中的部分已够用。后续按需学习：多阶段构建减小镜像体积、分层构建优化、Harbor 私有仓库、CI 中构建推送镜像；容器编排和集群属于 Kubernetes 范围，不在初级要求内。
