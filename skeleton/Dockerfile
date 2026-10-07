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

# 全量构建（含 8 模块测试），产出 dy-app 可启动 fat-jar
COPY . .
RUN mvn -B -ntp clean install -DskipTests -pl dy-app -am

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
