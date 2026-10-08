# 调元云 · dy-app 多阶段镜像
# 构建上下文 = skeleton/ (多模块根 pom 所在目录)
# 本地离线构建需挂载 ~/.m2；CI 内使用在线 Maven。

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# 先拷 pom，利用层缓存（依赖不变则不重下）
COPY pom.xml .
COPY dy-common/pom.xml dy-common/
COPY dy-tenancy/pom.xml dy-tenancy/
COPY dy-security/pom.xml dy-security/
COPY dy-web/pom.xml dy-web/
COPY dy-audit/pom.xml dy-audit/
COPY dy-config/pom.xml dy-config/
COPY dy-crypto/pom.xml dy-crypto/
COPY dy-app/pom.xml dy-app/

# 全量构建，产出 dy-app 可启动 fat-jar
COPY . .
#
# 🛑 `-Dexec.skip=true` 是**刻意**加的，两条理由各自独立 —— 写在这里是为了让下一个人
#    看到的不是"这一行在偷懒"，而是"这一行在镜像构建里做不到别的选择"：
#
#   理由一（结构性，无法绕开）：根 pom 的 4 个 validate 门禁
#     （ADR-12 词表扫描 / s16 零派生 / s18 契约一致性 / s19 SDK 表面）
#     **都要读冻结契约** `contract/openapi-v1.0.0.yaml`，而该文件在 `skeleton/` 的
#     **上一级**（与 PRD 同级；s16 的门禁注释逐字写着"contract/ 在 build 根的上一级"）。
#     本 Dockerfile 的构建上下文就是 `skeleton/`（见 .dockerignore；docker-compose.yml
#     里也是 `context: .`）⇒ **契约根本不在构建上下文里**，门禁的 docs-root 向上探测
#     必然失败并以 exit 2 拒绝运行。在镜像里跑它们不是"麻烦"，是**不可能**。
#
#   理由二（基础镜像层面）：`maven:*` 基础镜像里**没有 `python` 这个可执行名**、
#     也没有 PyYAML；而根 pom 的 `compliance.python.executable` 默认值就是 `python`
#     ⇒ 即便契约在上下文里，也会先以 "Cannot run program \"python\"" 变红，
#       而那句错误完全指不到"缺一个 Python 包"这个真实原因（本仓第 48 条同族：
#       写下的命令与实际跑得起来的命令是两件事）。
#
#   ⇒ 门禁的归属是 **CI 的 backend job**：那里构建上下文是**整个仓库**（契约在场），
#     并且已显式准备 Python + PyYAML。本 RUN 的定位是**打包** —— 与同一行里的
#     `-DskipTests` 同性质：只产出可运行的 jar，不重复做验证。
#     要验证，请跑 CI 的 backend job，或本机的 `mvn -B clean install`。
RUN mvn -B -ntp clean install -DskipTests -pl dy-app -am -Dexec.skip=true

# ---------- Stage 2: run ----------
FROM eclipse-temurin:17-jre AS run
WORKDIR /app

# 运行期只依赖 Postgres；凭据经环境变量注入（见 docker-compose.yml / K8s Secret）
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"

COPY --from=build /workspace/dy-app/target/dy-app-*.jar /app/app.jar

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=60s \
  CMD wget -qO- http://localhost:8080/actuator/health 2>/dev/null || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dspring.profiles.active=$SPRING_PROFILES_ACTIVE -jar /app/app.jar"]
