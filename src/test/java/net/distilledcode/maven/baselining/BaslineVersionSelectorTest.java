package net.distilledcode.maven.baselining;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.junit.Test;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import static net.distilledcode.maven.baselining.BaselineVersionSelector.selectBaselineVersion;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class BaslineVersionSelectorTest {

    @Test
    public void baselineSnapshotVersion() {
        final ArtifactVersion baselineVersion = selectBaselineVersion(
                v("1.0.3-SNAPSHOT"),
                vs("1.0.0", "1.0.2", "1.0.1")
        );
        assertEquals("1.0.2", baselineVersion.toString());
    }

    @Test
    public void baselineReleaseVersion() {
        final ArtifactVersion baselineVersion = selectBaselineVersion(
                v("1.0.3"),
                vs("1.0.0", "1.0.2", "1.0.1")
        );
        assertEquals("1.0.2", baselineVersion.toString());
    }

    @Test
    public void ignoreLargerVersionsWithSnapshot() {
        final ArtifactVersion baselineVersion = selectBaselineVersion(
                v("1.0.1-SNAPSHOT"),
                vs("1.0.0", "1.0.2", "1.0.1")
        );
        assertEquals("1.0.0", baselineVersion.toString());
    }

    @Test
    public void ignoreLargerVersionsWithRelease() {
        final ArtifactVersion baselineVersion = selectBaselineVersion(
                v("1.0.2"),
                vs("1.0.0", "1.0.3", "1.0.1")
        );
        assertEquals("1.0.1", baselineVersion.toString());
    }

    @Test
    public void neverBaselineAgainstSnapshots() {
        final ArtifactVersion baselineVersion = selectBaselineVersion(
                v("1.0.2"),
                vs("1.0.0-SNAPSHOT", "1.0.0", "1.0.1-SNAPSHOT", "1.0.1", "1.0.2-SNAPSHOT")
        );
        assertEquals("1.0.1", baselineVersion.toString());
    }

    @Test
    public void noBaselineVersionAgainstSnapshots() {
        final ArtifactVersion baselineVersion = selectBaselineVersion(
                v("1.0.0"),
                vs("1.0.0-SNAPSHOT", "1.0.0", "1.0.1-SNAPSHOT", "1.0.1", "1.0.2-SNAPSHOT")
        );
        assertNull(baselineVersion);
    }

    private ArtifactVersion v(String version) {
        return new DefaultArtifactVersion(version);
    }

    private Collection<ArtifactVersion> vs(String... versions) {
        final Set<ArtifactVersion> artifactVersions = new HashSet<ArtifactVersion>();
        for (final String version : versions) {
            artifactVersions.add(v(version));
        }
        return artifactVersions;
    }
}
