#!/usr/bin/env bash
# Regenerate mock-artifactory/data/ from scratch with two test artifacts:
#  - com.test:demo-lib 1.0.0       (release)
#  - com.test:demo-lib 1.0.1-SNAPSHOT (snapshot, different SHA)
# Both jars carry META-INF/git.properties + META-INF/maven/.../pom.xml
# with <scm> pointing at the test GitHub repo.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
DATA="$DIR/data"
GROUP_PATH="com/test"
ARTIFACT="demo-lib"
RELEASE_VERSION="1.0.0"
SNAPSHOT_BASE="1.0.1-SNAPSHOT"
SNAPSHOT_TS_VERSION="1.0.1-20260422.143547-1"
SNAPSHOT_TIMESTAMP="20260422.143547"
SNAPSHOT_BUILDNUM="1"

# SCM / GitHub coords must match the test repo you point the resolver at.
# Override via env: TEST_GH_OWNER=... TEST_GH_REPO=... ./build-fixtures.sh
GH_OWNER="${TEST_GH_OWNER:-namin2}"
GH_REPO="${TEST_GH_REPO:-dep-resolver-sandbox-lib}"

# SHAs we want to see the resolver compare. These should be REAL commit SHAs
# from the library's repo so GitHub's compare API returns something meaningful.
# For a first pass, use placeholders — we'll swap them to real SHAs once the
# sandbox library repo is created.
RELEASE_SHA="${RELEASE_SHA:-1111111111111111111111111111111111111111}"
SNAPSHOT_SHA="${SNAPSHOT_SHA:-2222222222222222222222222222222222222222}"

echo "Rebuilding fixtures into $DATA"
rm -rf "$DATA"
mkdir -p "$DATA"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

build_jar() {
    local version="$1" sha="$2" outpath="$3"
    local work="$TMP/jar-$version"
    mkdir -p "$work/META-INF/maven/com.test/$ARTIFACT"

    cat > "$work/META-INF/git.properties" <<EOF
git.commit.id=$sha
git.dirty=false
EOF

    cat > "$work/META-INF/maven/com.test/$ARTIFACT/pom.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.test</groupId>
  <artifactId>$ARTIFACT</artifactId>
  <version>$version</version>
  <scm>
    <connection>scm:git:https://github.com/$GH_OWNER/$GH_REPO.git</connection>
    <url>https://github.com/$GH_OWNER/$GH_REPO</url>
  </scm>
</project>
EOF

    # Dummy class so it's a "real" jar
    mkdir -p "$work/com/test"
    echo "// placeholder" > "$work/com/test/Dummy.txt"

    mkdir -p "$(dirname "$outpath")"
    (cd "$work" && zip -qr "$outpath" .)
    echo "  built $outpath (sha=$sha)"
}

# -------- RELEASE --------
RELEASE_JAR_PATH="$DATA/releases/$GROUP_PATH/$ARTIFACT/$RELEASE_VERSION/$ARTIFACT-$RELEASE_VERSION.jar"
build_jar "$RELEASE_VERSION" "$RELEASE_SHA" "$RELEASE_JAR_PATH"

cat > "$DATA/releases/$GROUP_PATH/$ARTIFACT/maven-metadata.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>com.test</groupId>
  <artifactId>$ARTIFACT</artifactId>
  <versioning>
    <latest>$RELEASE_VERSION</latest>
    <release>$RELEASE_VERSION</release>
    <versions>
      <version>$RELEASE_VERSION</version>
    </versions>
  </versioning>
</metadata>
EOF

# -------- SNAPSHOT --------
SNAP_JAR_PATH="$DATA/snapshots/$GROUP_PATH/$ARTIFACT/$SNAPSHOT_BASE/$ARTIFACT-$SNAPSHOT_TS_VERSION.jar"
build_jar "$SNAPSHOT_BASE" "$SNAPSHOT_SHA" "$SNAP_JAR_PATH"

# Artifact-level metadata (lists all snapshot bases)
cat > "$DATA/snapshots/$GROUP_PATH/$ARTIFACT/maven-metadata.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>com.test</groupId>
  <artifactId>$ARTIFACT</artifactId>
  <versioning>
    <latest>$SNAPSHOT_BASE</latest>
    <versions>
      <version>$SNAPSHOT_BASE</version>
    </versions>
  </versioning>
</metadata>
EOF

# Snapshot-level metadata (lists timestamped versions)
cat > "$DATA/snapshots/$GROUP_PATH/$ARTIFACT/$SNAPSHOT_BASE/maven-metadata.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>com.test</groupId>
  <artifactId>$ARTIFACT</artifactId>
  <version>$SNAPSHOT_BASE</version>
  <versioning>
    <snapshot>
      <timestamp>$SNAPSHOT_TIMESTAMP</timestamp>
      <buildNumber>$SNAPSHOT_BUILDNUM</buildNumber>
    </snapshot>
    <snapshotVersions>
      <snapshotVersion>
        <extension>jar</extension>
        <value>$SNAPSHOT_TS_VERSION</value>
      </snapshotVersion>
    </snapshotVersions>
  </versioning>
</metadata>
EOF

echo "Done."
echo "Release: $RELEASE_VERSION (sha=$RELEASE_SHA)"
echo "Snapshot: $SNAPSHOT_BASE → $SNAPSHOT_TS_VERSION (sha=$SNAPSHOT_SHA)"
echo "SCM in jars: https://github.com/$GH_OWNER/$GH_REPO"
