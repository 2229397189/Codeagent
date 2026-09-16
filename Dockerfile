# syntax=docker/dockerfile:1
# CodeAgent - minimal, observable coding agent (zero dependency, Java 17).
# Multi-stage: compile with the JDK, then ship a ready-to-run image.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY src ./src
RUN mkdir -p /out \
    && find src -name '*.java' > /out/sources.txt \
    && javac -encoding UTF-8 -d /out @/out/sources.txt

FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY --from=build /out ./out
COPY --from=build /src ./src

# The CLI reads CODEAGENT_API_KEY from the environment automatically
# (see core/AgentConfig.java). The agent is interactive, so run with -it
# and mount your project directory at /workspace.
#
# Build:   docker build -t codeagent .
# Run:     docker run -it -e CODEAGENT_API_KEY=YOUR_KEY \
#            -v "$PWD:/workspace" codeagent --model glm-4.6v
#
# Override the model: append e.g. "--model glm-4.5-air" to the command.
ENTRYPOINT ["java", "-cp", "/app/out", "com.codeagent.cli.CodeAgentCli"]
CMD ["--model", "glm-4.6v", "--base-url", "https://open.bigmodel.cn/api/paas/v4", "--workspace", "/workspace"]
