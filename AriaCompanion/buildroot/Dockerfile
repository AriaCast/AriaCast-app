FROM ubuntu:22.04

LABEL org.opencontainers.image.title="AriaCompanion builder" \
      org.opencontainers.image.description="Buildroot cross-compiler for Pi Zero W/2W firmware"

ENV DEBIAN_FRONTEND=noninteractive
ENV BUILDROOT_VERSION=2024.02.6

# ── Build dependencies (Buildroot host requirements) ──────────────────────────
RUN apt-get update && apt-get install -y --no-install-recommends \
        bash bc binutils bison bzip2 ca-certificates \
        cpio diffutils file findutils flex \
        gawk gcc g++ git gzip \
        libssl-dev libelf-dev libncurses-dev \
        lzop make patch perl python3 rsync \
        sed tar texinfo unzip wget \
    && rm -rf /var/lib/apt/lists/*

# ── Pre-download Buildroot source (cached as its own layer) ───────────────────
# This layer only re-runs when BUILDROOT_VERSION changes, not when our source
# changes — so `docker build` after editing AriaCompanion code stays fast.
RUN wget -q --show-progress --progress=bar:force \
        "https://buildroot.org/downloads/buildroot-${BUILDROOT_VERSION}.tar.gz" \
        -O /opt/buildroot.tar.gz \
    && tar xf /opt/buildroot.tar.gz -C /opt \
    && rm /opt/buildroot.tar.gz

# ── Copy our BR2_EXTERNAL tree ────────────────────────────────────────────────
COPY . /build/ariacomp

# Create download cache directory (mount a named volume here for fast rebuilds)
RUN mkdir -p /build/dl-cache

# Output dir that will be served
RUN mkdir -p /build/serve

WORKDIR /build/ariacomp

EXPOSE 8040

ENTRYPOINT ["/build/ariacomp/docker-entrypoint.sh"]
# Default: build both targets; override with e.g. `docker run … pizero2w`
CMD ["both"]
