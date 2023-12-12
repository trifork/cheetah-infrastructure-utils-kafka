FROM mcr.microsoft.com/vscode/devcontainers/java:1-17-buster

ARG MAVEN_VERSION=""
RUN su vscode -c "umask 0002 && . /usr/local/sdkman/bin/sdkman-init.sh && sdk install maven \"${MAVEN_VERSION}\"";
COPY --chown=vscode:vscode settings.xml /home/vscode/.m2/
COPY maven-settings.xml /usr/share/maven/ref/

# [Optional] Uncomment this section to install additional OS packages.
RUN apt-get update && export DEBIAN_FRONTEND=noninteractive \
    && apt-get -y install --no-install-recommends git openssh-client less iproute2 procps curl lsb-release

# Clean up
RUN export DEBIAN_FRONTEND=noninteractive \
    && apt-get autoremove -y \
    && apt-get clean -y \
    && rm -rf /var/lib/apt/lists/*

# Allow for a consistant java home location for settings - image is changing over time
RUN if [ ! -d "/docker-java-home" ]; then ln -s "${JAVA_HOME}" /docker-java-home; fi

# Install github act
RUN curl -s https://raw.githubusercontent.com/nektos/act/master/install.sh | sudo bash