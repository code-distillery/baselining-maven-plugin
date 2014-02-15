package net.distilledcode.maven.baselining;

import org.apache.maven.artifact.versioning.ArtifactVersion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class BaselineVersionSelector {
    private BaselineVersionSelector() {
    }

    public static ArtifactVersion selectBaselineVersion(final ArtifactVersion current, final Collection<ArtifactVersion> otherVersions) {
        final List<ArtifactVersion> candidateVersions = new ArrayList<ArtifactVersion>(otherVersions);
        removeSnapshotVersions(candidateVersions);
        removeVersionsSmallerThanCurrent(current, candidateVersions);
        Collections.sort(candidateVersions, Collections.reverseOrder());
        return candidateVersions.size() > 0 ? candidateVersions.get(0) : null;
    }

    private static void removeVersionsSmallerThanCurrent(final ArtifactVersion current, final Iterable<ArtifactVersion> versions) {
        final Iterator<ArtifactVersion> versionIterator = versions.iterator();
        while (versionIterator.hasNext()) {
            final ArtifactVersion version = versionIterator.next();
            if (version.compareTo(current) >= 0) {
                versionIterator.remove();
            }
        }
    }

    private static void removeSnapshotVersions(final Iterable<ArtifactVersion> versions) {
        final Iterator<ArtifactVersion> versionIterator = versions.iterator();
        while (versionIterator.hasNext()) {
            final ArtifactVersion version = versionIterator.next();
            if ("SNAPSHOT".equals(version.getQualifier())) {
                versionIterator.remove();
            }
        }
    }
}
