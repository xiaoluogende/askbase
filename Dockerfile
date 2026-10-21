# 多阶段构建：构建阶段用完整 JDK，运行阶段只留 JRE，镜像能小一半以上
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# 先只拷 pom 下依赖，利用 Docker 层缓存：
# 只改代码不改依赖时，这一层不会重新下载，构建从 3 分钟降到 20 秒
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

# 非 root 运行。容器逃逸时这一层能限制影响面，也是很多公司安全扫描的硬性要求
RUN useradd -r -u 1001 -m appuser
USER appuser

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
