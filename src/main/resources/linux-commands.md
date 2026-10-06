# Linux 基础命令学习指南（生产实用向，Java 初级开发）

面向需要登录 Linux 服务器部署应用、排查问题的 Java 开发。不追求大而全，只收生产环境高频命令。

环境：CentOS/RHEL（yum、firewall-cmd）与 Ubuntu/Debian（apt、ufw）并列标注；服务管理统一为 systemd。

---

## 一、先建立 4 个意识

1. **确认环境再动手**：生产机器先看自己是谁、在哪台机器，避免误操作。
2. **命令区分大小写**：文件名、命令名均敏感。
3. **删除不可逆**：Linux 无回收站，`rm` 前停一秒。
4. **权限最小化**：能用普通用户就不用 root，需要时再加 sudo。

```bash
whoami          # 当前用户
hostname        # 当前机器名（多台服务器时确认没登错）
pwd             # 当前所在目录
uname -a        # 内核与系统信息
cat /etc/os-release   # 发行版版本（决定用 yum 还是 apt）
```

---

## 二、目录与文件操作

### 1. 路径与浏览

```bash
pwd                       # 当前路径
ls                        # 列出文件
ls -lht                   # 长格式、人类可读大小、按修改时间排序
ls -la                    # 含隐藏文件（以 . 开头）
cd /opt/app               # 进目录（绝对路径）
cd ..                     # 上一级
cd ~                      # 回家目录
cd -                      # 回到上一次所在目录
tree -L 2                 # 目录树（无此命令可跳过或 yum install tree）
```

`ls -l` 输出速读：

```
-rw-r--r-- 1 root root 1.2K Oct  3 10:00 app.log
└──权限──┘  └属主┘└属组┘  └大小┘   └时间┘  └文件名
```

### 2. 文件与目录增删改

```bash
mkdir -p /opt/app/logs           # -p 递归创建多级目录
cp app.jar app.jar.bak           # 复制文件
cp -r conf conf.bak              # 递归复制目录
mv app.jar /opt/app/             # 移动（也用于改名）
rm app.log                       # 删文件
rm -rf /opt/app/logs             # 递归强删目录（危险，见下方红线）
touch a.txt                      # 新建空文件 / 更新时间戳
ln -s /opt/app/app.jar current.jar   # 软链接（部署切版本常用）
```

rm 红线：

- 严禁 `rm -rf /`、`rm -rf /*`
- 路径含变量时先 `echo` 确认：先执行 `echo /opt/app/$DIR` 看展开结果
- 生产删文件优先 `mv` 到临时目录或改后缀 `.bak`，观察后再删

### 3. 查看文件内容

```bash
cat app.log                # 小文件一次看完
less app.log               # 大文件分页查看（推荐）
# less 内：/关键词 搜索，n 下一个，N 上一个，g 开头，G 末尾，q 退出
head -20 app.log           # 前 20 行
tail -20 app.log           # 后 20 行
tail -f app.log            # 实时跟踪日志（Ctrl+C 退出）
tail -F app.log            # 同上，文件被轮转/重建后自动重连（生产推荐）
wc -l app.log              # 统计行数
```

### 4. 文件传输（scp / rsync）

基于 SSH 协议在两台 Linux 机器之间传文件，前提是能用 ssh 登录目标机器（22 端口通、有账号密码或密钥）。

#### 4.1 命令语法拆解

```bash
scp 源路径 目标路径
rsync 源路径 目标路径
```

涉及远程机器时，路径写成 `用户名@机器IP:远程目录`，冒号是分隔符，冒号后是远程绝对路径。

```
user@192.168.1.10:/opt/app/
│         │              │
│         │              └─ 远程机器上的目录（冒号不能漏）
│         └─ 远程机器 IP 或主机名
└─ 登录用的用户名
```

#### 4.2 scp：上传、下载、传目录

```bash
# 上传：本机当前目录的 app.jar → 远程 /opt/app/
scp app.jar user@192.168.1.10:/opt/app/

# 上传并改名（远程路径写成完整文件名）
scp app.jar user@192.168.1.10:/opt/app/demo-app-1.0.jar

# 下载：远程文件 → 本机当前目录（. 表示当前目录）
scp user@192.168.1.10:/opt/app/app.log ./

# 下载到指定本地文件名
scp user@192.168.1.10:/opt/app/app.log ./app-20261003.log

# 传整个目录必须加 -r
scp -r conf/ user@192.168.1.10:/opt/app/

# SSH 端口不是默认 22 时用 -P（大写）指定，如 2222
scp -P 2222 app.jar user@192.168.1.10:/opt/app/

# 一次传多个文件
scp app.jar application.yml user@192.168.1.10:/opt/app/
```

首次连接会提示 `Are you sure you want to continue connecting (yes/no)`，输入 yes，之后会记录到 `~/.ssh/known_hosts` 不再询问。

#### 4.3 rsync：传大量文件、可续传、只同步差异

```bash
rsync -avz --progress app.jar user@192.168.1.10:/opt/app/
```

| 参数 | 含义 |
|---|---|
| `-a` | archive 归档模式：递归传目录并保留权限、属主、时间戳、软链接（最常用） |
| `-v` | verbose 显示传输过程 |
| `-z` | 传输时压缩，日志、文本类文件节省带宽 |
| `--progress` | 显示进度条 |
| `--delete` | 删除目标端多余的文件（让两端完全一致，危险，先用 `-n` 预演） |
| `-n` / `--dry-run` | 只显示将要传哪些文件，不实际执行 |
| `-e "ssh -p 2222"` | 指定非 22 的 SSH 端口 |

路径末尾的斜杠含义不同，容易踩坑：

```bash
rsync -avz conf/  user@host:/opt/app/conf/   # 传目录内容：把 conf 里的文件放进远程 conf
rsync -avz conf   user@host:/opt/app/        # 传目录本身：远程变成 /opt/app/conf/
```

#### 4.4 scp 与 rsync 怎么选

| 对比 | scp | rsync |
|---|---|---|
| 单个小文件/jar 包 | 简单直接 | 同样可以 |
| 整个目录、大量小文件 | 每次全量重传 | 只传变化部分，速度快 |
| 传输中断 | 从头再来 | 已传部分不重传 |
| 依赖 | 系统自带 | 部分精简系统需 `yum install rsync` / `apt install rsync` |
| 典型场景 | 发布单个 jar、下载单个日志 | 同步整个部署目录、日志目录、反复传输 |

Java 部署常用组合：

```bash
# 发布：本地打好的 jar 传到服务器（scp 够用）
scp target/untitled-1.0-SNAPSHOT.jar user@192.168.1.10:/opt/app/

# 从服务器取日志目录回本地分析（rsync 只拉变化部分）
rsync -avz --progress user@192.168.1.10:/opt/app/logs/ ./logs/
```

#### 4.5 免密配置（避免每次输密码）

```bash
# 1. 本机生成密钥对（已有 ~/.ssh/id_rsa 可跳过，一路回车）
ssh-keygen -t rsa

# 2. 把公钥拷到远程机器（之后 scp/rsync/ssh 都不再要密码）
ssh-copy-id user@192.168.1.10
```

公钥写入远程 `~/.ssh/authorized_keys`，私钥留在本机 `~/.ssh/id_rsa`，切勿把私钥发给别人。

#### 4.6 常见报错

| 现象 | 原因与处理 |
|---|---|
| `Connection refused` / 超时 | 目标机 SSH 服务未启动、22 端口未放通、IP 不通；先用 `nc -zv IP 22` 验证 |
| `Permission denied (publickey,password)` | 用户名/密码错误，或服务器只允许密钥登录 |
| `No such file or directory` | 远程目录不存在或无写权限；先 `ssh` 登录 `mkdir -p` 创建 |
| `Host key verification failed` | 远程机器重装过，本机记录的指纹失效；编辑 `~/.ssh/known_hosts` 删除旧条目 |
| Windows 本机执行提示找不到 scp | Win10+ 自带 OpenSSH（PowerShell 可直接用）；老系统用 WinSCP/Xftp 图形工具 |

---

## 三、文本处理（排查日志的核心能力）

### 1. grep：过滤查找

```bash
grep "Exception" app.log                    # 找含关键词的行
grep -n "Exception" app.log                 # 带行号
grep -i "error" app.log                     # 忽略大小写
grep -C 5 "Exception" app.log               # 匹配行前后各 5 行上下文
grep -v "DEBUG" app.log                     # 反向：排除含 DEBUG 的行
grep -rn "user:1" /opt/app/                 # 递归搜目录下所有文件并显示行号
grep -E "Exception|ERROR" app.log           # 正则：多个关键词
```

组合用法（最常用）：

```bash
grep "Exception" app.log | grep -v "BindException"   # 再排除无关异常
cat app.log | grep "userId=1001" | wc -l             # 某用户报错次数
```

### 2. find：按条件找文件

```bash
find /opt/app -name "*.log"                # 按文件名
find /opt/app -name "*.log" -mtime -1      # 1 天内修改过的
find /opt/app -type f -size +100M          # 大于 100M 的文件
find /opt/app -type d                      # 只找目录
# 找到后批量处理：删除 7 天前的日志
find /opt/app/logs -name "*.log" -mtime +7 -exec rm -f {} \;
```

时间参数：`-mtime -1`（1 天内）、`+7`（7 天前）。

### 3. awk：取列与统计

```bash
awk '{print $1}' access.log                # 打印每行第 1 列（默认空格/制表符分隔）
awk -F',' '{print $2}' data.csv            # 指定逗号分隔，取第 2 列
awk '$9 == 500 {print $7}' access.log      # 条件：第 9 列为 500 时打印第 7 列
awk '{sum += $10} END {print sum}' file    # 对第 10 列求和
```

### 4. sort / uniq / head：排行统计

```bash
# 统计日志中出现次数最多的 IP（经典三连）
awk '{print $1}' access.log | sort | uniq -c | sort -rn | head -10
```

| 命令 | 作用 |
|---|---|
| sort | 排序，`-n` 按数字，`-r` 倒序 |
| uniq -c | 去重并计数（必须先 sort） |
| head -10 | 取前 10 |

### 5. sed：替换与按行查看

```bash
sed -n '100,120p' app.log                  # 只看 100~120 行
sed 's/old/new/g' file                     # 全文替换输出（不改原文件）
sed -i 's/old/new/g' file                  # 直接改文件（生产慎用，先备份）
sed -i.bak 's/old/new/g' file              # 改的同时生成 file.bak
```

### 6. 其他高频

```bash
# 两个文件内容对比（配置核对、发布 diff）
diff app-dev.yml app-prod.yml

# 大文件查找定位
grep -n "NullPointerException" app.log | head -1   # 先拿行号
sed -n '1530,1560p' app.log                        # 再看上下文

# 实时只看错误
tail -f app.log | grep --line-buffered -E "ERROR|Exception"
```

---

## 四、用户、权限与属主

### 1. 权限模型

```
-rwxr-xr--
│└┬┘└┬┘└┬┘
│ │  │  └─ others 其他用户权限
│ │  └──── group 同组权限
│ └─────── owner 属主权限
└───────── 文件类型：- 普通文件，d 目录，l 软链接
```

r=4 读，w=2 写，x=1 执行。`rwxr-xr--` 即 754。

### 2. 常用操作

```bash
chmod 755 app.sh                 # 改权限：属主 rwx，其余 r-x
chmod +x deploy.sh               # 加可执行权限（脚本最常用）
chmod 644 application.yml        # 属主可写，其余只读（配置文件常用）
chown app:app app.jar            # 改属主属组
chown -R app:app /opt/app        # 递归改整个目录
```

| 场景 | 推荐权限 |
|---|---|
| shell 脚本 | 755 |
| 配置文件（含密码） | 600（仅属主可读写） |
| jar、普通文件 | 644 |
| 目录 | 755 |

### 3. 用户与 sudo

```bash
id                               # 查看当前用户和所属组
sudo 命令                         # 以 root 权限执行
sudo -u appuser 命令              # 以指定用户执行
su - appuser                     # 切换用户（- 连带环境变量）
exit                             # 退回原用户
```

安全红线：不要把业务应用放在 root 下跑；用专用普通用户（如 appuser）启动 Java 进程。

---

## 五、进程与服务管理

### 1. 查进程

```bash
ps -ef | grep java               # 找 Java 进程（含完整启动命令）
ps -ef | grep java | grep -v grep   # 排除 grep 自身
ps aux                           # 所有进程及 CPU/内存占用
```

输出第二列是 PID，后续 kill/查线程都用它。

### 2. 杀进程

```bash
kill 12345                       # 发送 15（SIGTERM），请求进程优雅退出（优先）
kill -9 12345                    # 发送 9（SIGKILL），强杀，资源来不及释放（最后手段）
```

规则：先 `kill`，等 10~30 秒确认未退出再 `kill -9`。Spring Boot 收到 SIGTERM 会走优雅停机。

一键定位并杀 Java 进程（确认后再用）：

```bash
ps -ef | grep 'demo-app' | grep -v grep | awk '{print $2}' | xargs kill
```

### 3. systemctl：管理系统服务

```bash
sudo systemctl status nginx          # 查看状态（最常用）
sudo systemctl start nginx
sudo systemctl stop nginx
sudo systemctl restart nginx
sudo systemctl reload nginx          # 平滑重载配置（不中断服务，Nginx 支持）
sudo systemctl enable nginx          # 设为开机自启
sudo systemctl disable nginx
systemctl list-units --type=service  # 列出所有服务及状态
```

改完服务配置文件后：能 reload 就不 restart（reload 不断连接）。

### 4. journalctl：看 systemd 管理的服务日志

```bash
sudo journalctl -u nginx -f          # 实时跟踪某服务日志
sudo journalctl -u nginx --since "1 hour ago"
sudo journalctl -u nginx --since "2026-10-03 09:00" --until "2026-10-03 10:00"
sudo journalctl -u nginx -n 200      # 最后 200 行
```

### 5. 前后台与任务

```bash
java -jar app.jar &              # & 放后台（关掉终端可能被杀）
nohup java -jar app.jar > app.log 2>&1 &   # 关终端也不退出，日志重定向
jobs                             # 查看当前终端后台任务
fg %1                            # 调回前台
Ctrl+Z                           # 前台任务挂起
bg %1                            # 挂起任务转后台运行
```

> 生产部署建议用 systemd 服务单元或容器，nohup 只用于临时验证。

---

## 六、系统资源排查（OOM、卡顿必查）

### 1. 内存

```bash
free -h                          # 总览（-h 人类可读）
```

关注 `available` 列（真正可用内存），不是 free。buff/cache 被占用属正常，需要时系统会回收。

### 2. CPU 与负载

```bash
top                              # 实时资源（进入后按 P 按 CPU 排序，M 按内存，q 退出）
top -Hp 12345                    # 查看某 Java 进程内各线程占用（排查高 CPU）
uptime                           # 三个负载值：1/5/15 分钟平均
# load average 接近或超过 CPU 核数说明过载
```

### 3. 磁盘

```bash
df -h                            # 各分区使用情况（磁盘满先看这个）
df -i                            # inode 使用情况（小文件过多会 inode 耗尽）
du -sh /opt/app/*                # 目录各子项大小汇总
du -sh /var/log/* | sort -rh | head      # 找最大的日志目录
```

磁盘 100% 的典型原因：应用日志、容器数据、core dump 文件。

### 4. Java 进程专项排查（JDK 自带，需装 JDK 不只是 JRE）

```bash
# 假设 Java PID = 12345
jps -l                                    # 列出本机 Java 进程及主类
jstack 12345 > thread-$(date +%F).dump    # 导出线程栈（死锁、卡顿时连续导 3 次）
jmap -heap 12345                          # 堆内存概况
jstat -gcutil 12345 1000 10               # 每秒一次 GC 情况，共 10 次
jmap -dump:format=b,file=heap.hprof 12345 # OOM 时导堆快照（文件大，确认磁盘空间）
```

排查顺序（面试与实战通用）：

1. 服务卡 → `top` 看 CPU/内存
2. CPU 高 → `top -Hp PID` 拿高占用线程 ID，转十六进制，在 `jstack` 输出中找 `nid=0x...`
3. 内存涨/Full GC 频繁 → `jstat -gcutil`，必要时导 hprof 用 MAT 分析

---

## 七、网络与端口排查

### 1. 端口与连接

```bash
ss -tlnp                         # 本机所有监听端口及进程（新系统推荐）
netstat -tlnp                    # 老系统等价命令
ss -tlnp | grep 8080             # 8080 是否在监听、被谁占用
ss -tn state established | wc -l # 当前已建立连接数
```

字段：`-t` TCP、`-l` 监听中、`-n` 不解析名字（快）、`-p` 显示进程。

### 2. 连通性测试

```bash
ping 192.168.1.10                       # 网络可达（ICMP，禁 ping 的环境不通不代表服务不通）
curl -I http://localhost:8080/actuator/health   # HTTP 接口状态码
curl -v http://192.168.1.10:8080        # -v 看完整握手与请求过程
telnet 192.168.1.10 3306                # TCP 端口能否连通（退出按 Ctrl+] 再 quit）
nc -zv 192.168.1.10 3306                # 同上，更轻量
nslookup redis.example.com              # DNS 解析
```

Java 应用连不上中间件的排查顺序：

1. `ss -tlnp | grep 端口`：服务端是否在监听
2. `nc -zv 服务端IP 端口`：客户端到服务端网络是否通
3. 不通：防火墙/安全组；通但应用报错：账号密码、vhost、白名单

### 3. 防火墙

```bash
# CentOS/RHEL（firewalld）
sudo firewall-cmd --list-ports                       # 已放行端口
sudo firewall-cmd --permanent --add-port=8080/tcp   # 放行
sudo firewall-cmd --reload                           # 重载生效

# Ubuntu/Debian（ufw）
sudo ufw status
sudo ufw allow 8080/tcp
```

云服务器还要在**云控制台安全组**放行，机器内放行了但安全组没放行同样不通。

### 4. 路由与本机 IP

```bash
ip addr                          # 查看网卡和 IP（老命令 ifconfig）
ip route                         # 路由表、默认网关
```

---

## 八、软件安装与 yum/apt

```bash
# CentOS/RHEL
sudo yum install -y vim wget curl          # 安装
sudo yum list installed | grep java        # 查已装
sudo yum remove 包名
sudo yum update 包名                        # 更新单个包

# Ubuntu/Debian
sudo apt-get update                        # 先刷新软件源索引（安装前必做一次）
sudo apt-get install -y vim wget curl
sudo apt-get remove 包名
```

| 注意点 | 说明 |
|---|---|
| 不要在生产随意 `yum update`（不指定包名） | 会全量升级，可能引发兼容问题 |
| apt 装包前先 `apt-get update` | 否则找不到最新包或报 404 |
| 公司内网 | 通常要换成内网 yum/apt 源，按运维提供的配置操作 |

---

## 九、压缩与解压（部署、取日志必用）

```bash
tar -zcvf logs.tar.gz logs/           # 打包并 gzip 压缩整个目录
tar -zxvf logs.tar.gz                 # 解压到当前目录
tar -zxvf app.tar.gz -C /opt/app/     # 解压到指定目录
tar -ztvf logs.tar.gz                 # 只看包内文件列表不解压

zip -r logs.zip logs/                 # zip 压缩
unzip logs.zip -d /opt/app/           # zip 解压到目录
```

参数记忆：`c` 创建、`x` 解压、`z` gzip、`v` 显示过程、`f` 指定文件名。

---

## 十、vim 必会操作（服务器上改配置）

只需先掌握这些就能完成 90% 的配置修改：

```
vim application.yml
```

| 模式/操作 | 作用 |
|---|---|
| 打开后默认普通模式 | 不能直接输入文字 |
| `i` | 进入插入模式开始编辑 |
| `Esc` | 回到普通模式 |
| `:w` | 保存 |
| `:q` | 退出 |
| `:wq` 或 `ZZ` | 保存并退出 |
| `:q!` | 不保存强制退出（改乱了用这个放弃） |
| `/关键词` 回车 | 搜索，`n` 下一个 |
| `gg` / `G` | 文件开头 / 末尾 |
| `dd` | 剪切当前行 |
| `yy` 然后 `p` | 复制当前行 / 粘贴 |
| `u` | 撤销 |
| `:set nu` | 显示行号 |

关键习惯：改配置前先备份 `cp application.yml application.yml.bak`；不确定是否改对时 `:q!` 放弃重来。

---

## 十一、环境变量与 Java 相关

```bash
env                              # 查看所有环境变量
echo $PATH                       # 查看命令搜索路径
echo $JAVA_HOME
which java                       # 可执行文件的实际路径
java -version
```

临时生效（仅当前终端）：

```bash
export JAVA_HOME=/usr/local/jdk-11
export PATH=$JAVA_HOME/bin:$PATH
```

永久生效：追加到 `~/.bashrc`（当前用户）后执行 `source ~/.bashrc`；全局生效写 `/etc/profile`（需重新登录）。

---

## 十二、定时任务与时间

```bash
date                                         # 当前时间
date '+%Y-%m-%d %H:%M:%S'
timedatectl                                  # 时区与 NTP 状态
sudo timedatectl set-timezone Asia/Shanghai  # 改时区（日志时间不对先查这个）

crontab -l                                   # 查看当前用户定时任务
crontab -e                                   # 编辑
```

cron 表达式（分 时 日 月 周）示例：

```
0 2 * * *  /opt/app/backup.sh        # 每天凌晨 2 点执行
*/5 * * * * /opt/app/health.sh        # 每 5 分钟执行
```

---

## 十三、Java 生产高频场景速查

| 场景 | 命令 |
|---|---|
| 发布新 jar 后启动 | `nohup java -jar -Xms512m -Xmx512m app.jar --spring.profiles.active=prod > nohup.out 2>&1 &` |
| 实时看应用日志 | `tail -F /opt/app/logs/app.log` |
| 只看报错并带上下文 | `grep -C 10 -E "Exception|ERROR" app.log` |
| 统计某接口调用次数 | `grep "/api/order" access.log \| wc -l` |
| 找 Java 进程 | `ps -ef \| grep java \| grep -v grep` |
| 优雅停应用 | `kill PID`（不要直接 -9） |
| 确认端口起来了 | `ss -tlnp \| grep 8080` |
| 磁盘满找元凶 | `df -h` → `du -sh /var/log/* \| sort -rh \| head` |
| 内存够不够 | `free -h` 看 available |
| 导出日志打包取回 | `tar -zcvf log.tar.gz logs/` 然后 scp 下载 |
| 机器时间不对 | `timedatectl` 检查时区与 NTP |
| 看系统层 OOM 杀进程记录 | `dmesg -T \| grep -i "out of memory"` 或 `journalctl -k \| grep -i oom` |

---

## 十四、危险命令红线

| 命令 | 风险 |
|---|---|
| `rm -rf /`、`rm -rf /*`、`rm -rf $VAR/`（VAR 为空时变成 `rm -rf /`） | 删除系统文件，不可恢复 |
| `kill -9` Java 进程 | 跳过优雅停机，请求中断、数据可能不一致 |
| `chmod -R 777 /` 或目录 | 权限全开，安全隐患，也可能破坏系统目录 |
| `dd if=... of=/dev/sda` | 直接覆写磁盘 |
| `mkfs.*` | 格式化，数据全毁 |
| `>` 重定向到重要文件 | `> app.log` 会清空文件（清日志用专用工具或 rotate） |
| 生产 `reboot`、`shutdown` | 确认影响面和集群状态后再执行 |
| 来源不明的 `curl ... \| bash` | 未审查脚本直接执行，风险极高 |

通用保命习惯：先在测试环境验证；生产删除/重启操作先 `ls`/`ps` 确认对象；批量操作前备份。

---

## 十五、完成标准 Checklist

- [ ] 能用 cd/ls/pwd/cp/mv/rm 完成基本文件操作，rm 前有意识确认路径
- [ ] 能用 tail/grep/find/awk 定位日志中的异常和统计次数
- [ ] 能用 ps + kill（先优雅后强杀）管理 Java 进程
- [ ] 能用 systemctl status/start/restart/reload 管理服务，知道 reload 与 restart 区别
- [ ] 能用 free/top/df/du 判断内存、CPU、磁盘问题
- [ ] 能用 ss/nc/curl 按「监听→连通→应用」顺序排查端口问题
- [ ] 能改 vim 配置并正确保存/放弃退出
- [ ] 能用 tar 打包、scp 传文件
- [ ] 知道 chmod 数字权限，能给脚本加执行权限、给敏感配置设 600
- [ ] 能说出 kill 与 kill -9、SIGTERM 与优雅停机的关系
- [ ] 能独立完成一次「传 jar → 启动 → 看日志 → 验证端口 → 验证接口」的部署流程
