FROM mcr.microsoft.com/devcontainers/base:ubuntu-22.04

ARG USERNAME=vscode
ARG FLUTTER_CHANNEL=stable

ENV FLUTTER_CHANNEL=${FLUTTER_CHANNEL}

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV FLUTTER_HOME=/opt/flutter
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

ENV PATH=${FLUTTER_HOME}/bin:${FLUTTER_HOME}/bin/cache/dart-sdk/bin:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${PATH}

# ------------------------------------------------------------
# System deps (Flutter CLI + Android build)
# ------------------------------------------------------------
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    git \
    unzip \
    xz-utils \
    zip \
    python3 \
    openjdk-17-jdk \
    libglu1-mesa \
    libstdc++6 \
    libgcc-s1 \
    libnss3 \
    libx11-6 \
    libxext6 \
    libxrender1 \
    libxi6 \
    libxrandr2 \
    libxcursor1 \
    libasound2 \
    libatk1.0-0 \
    libatk-bridge2.0-0 \
    libcups2 \
    libdrm2 \
    libgbm1 \
    libpango-1.0-0 \
    libpangocairo-1.0-0 \
    && rm -rf /var/lib/apt/lists/*

# ------------------------------------------------------------
# Android SDK command line tools
# ------------------------------------------------------------
RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools \
  && curl -fsSL -o /tmp/cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
  && unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools \
  && mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest \
  && rm /tmp/cmdline-tools.zip

# ------------------------------------------------------------
# Android SDK packages + licenses
# (Add whatever compileSdk your project uses; 34 is common)
# ------------------------------------------------------------
RUN yes | sdkmanager --sdk_root=${ANDROID_SDK_ROOT} --licenses \
  && sdkmanager --sdk_root=${ANDROID_SDK_ROOT} \
    "platform-tools" \
    "platforms;android-35" \
    "build-tools;35.0.0"

# ------------------------------------------------------------
# Flutter install (channel pinned via releases json)
# ------------------------------------------------------------
RUN <<'EOF'
set -eux

curl -fsSL https://storage.googleapis.com/flutter_infra_release/releases/releases_linux.json -o /tmp/flutter_releases.json

FLUTTER_ARCHIVE="$(python3 - <<'PY'
import json, os
data = json.load(open("/tmp/flutter_releases.json"))
channel = os.environ.get("FLUTTER_CHANNEL", "stable")
release_hash = data["current_release"][channel]
release = next(r for r in data["releases"] if r["hash"] == release_hash)
print(release["archive"])
PY
)"

curl -fsSL "https://storage.googleapis.com/flutter_infra_release/releases/${FLUTTER_ARCHIVE}" -o /tmp/flutter.tar.xz
tar -xJf /tmp/flutter.tar.xz -C /opt
rm /tmp/flutter.tar.xz /tmp/flutter_releases.json
EOF

# ------------------------------------------------------------
# Permissions so vscode user can write SDK + Flutter cache
# ------------------------------------------------------------
RUN chown -R ${USERNAME}:${USERNAME} ${ANDROID_SDK_ROOT} /opt/flutter
