package net.distilledcode.maven.baselining;

import aQute.bnd.differ.Baseline;
import aQute.bnd.differ.DiffPluginImpl;
import aQute.bnd.osgi.Jar;
import aQute.libg.reporter.ReporterAdapter;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.metadata.ArtifactMetadataRetrievalException;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.OverConstrainedVersionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;

import java.io.File;
import java.util.List;
import java.util.Set;

import static net.distilledcode.maven.baselining.BaselineVersionSelector.selectBaselineVersion;

/**
 * Compares exported java packages of the current artifact with the latest available released
 * version. If the semantics of exported java packages have changed, an incremented export
 * version is suggested.
 */
@Mojo(
        name = "baseline",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresProject = true,
        threadSafe = true
)
public class BaselineMojo extends AbstractMojo {

    public static final String MSG_NO_BASELINE = "No baseline version found";

    public static final String MSG_BASELINING = "Baselining against version %s";

    public static final String MSG_RAISE_VERSION = "Please raise the version of package %s to %s (old: %s -> new: %s)";

    public static final String MSG_LOWER_VERSION = "Please lower the version of package %s to %s (old: %s -> new: %s)";

    @Component
    private MavenSession session;

    @Component
    private MavenProject project;

    @Component
    private ArtifactResolver resolver;

    @Component
    private RepositorySystem repositorySystem;

    @Component
    private ArtifactMetadataSource artifactMetadataSource;

    /**
     * Deprecated: Whether or not to fail the build if exported version numbers need to be upgraded.
     *
     * If {@code enforcement} is set to anything other than {@code lowerAndUpperBound} (the default),
     * this option is ignored.
     *
     * @since 1.0.2
     * @deprecated Superseded by {@code enforcement}. {@code lowerAndUpperBound} is equivalent
     * to {@code failOnError=true}, {@code none} to failOnError=false.
     */
    @Deprecated
    @Parameter(defaultValue = "true")
    private boolean failOnError;

    /**
     * The {@code enforcement} allows controlling when the build should fail. Valid values
     * are:
     * <li>
     * lowerAndUpperBound (default): Enforce that export versions are incremented as required
     * but are not set to a higher value. Fails the build otherwise.
     * <li>
     * lowerBound: Enforce that export versions are incremented as required, but allows
     * increments that are higher than necessary. Fails the build otherwise.
     * <li>
     * none: The output is purely informational. Never fails the build.
     *
     * @since 1.0.4
     */
    @Parameter(property = "baselining.baseline.enforcement", defaultValue = "lowerAndUpperBound")
    private Enforcement enforcement;

    @Parameter(defaultValue = "${localRepository}", readonly = true)
    private ArtifactRepository localRepository;

    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true)
    private List<ArtifactRepository> remoteRepositories;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            final Artifact artifact = project.getArtifact();
            final ArtifactVersion baselineVersion = computeBaselineVersion(artifact);
            if (baselineVersion == null) {
                getLog().info(MSG_NO_BASELINE);
            } else {
                getLog().info(String.format(MSG_BASELINING, baselineVersion));
                final Artifact baselineArtifact = resolveBaselineArtifact(artifact, baselineVersion);
                final Set<Baseline.Info> baselineInfos = baseline(artifact.getFile(), baselineArtifact.getFile());
                reportFindings(baselineInfos);
            }
        } catch(MojoFailureException e) {
            throw e; // rethrow MojoFailureException as that can be a desired outcome
        } catch (Exception e) {
            throw new MojoExecutionException("Unexpected exception during mojo execution", e);
        }
    }

    private void reportFindings(Set<Baseline.Info> baselineInfos) throws MojoFailureException {

        // backwards compatibility for failOnError
        if (enforcement == Enforcement.lowerAndUpperBound && !failOnError) {
            enforcement = Enforcement.none;
        }

        final StringBuilder failureReport = new StringBuilder();
        for (final Baseline.Info info : baselineInfos) {
            final int comparison = info.newerVersion.compareTo(info.suggestedVersion);
            if (comparison < 0) { // lower bound violation: newerVersion is less than suggestedVersion
                final String msg = String.format(MSG_RAISE_VERSION, info.packageName, info.suggestedVersion, info.olderVersion, info.newerVersion);
                switch (enforcement) {
                    case lowerAndUpperBound:
                    case lowerBound:
                        failureReport.append(msg).append("\n");
                        getLog().error(msg);
                        break;
                    case none:
                        getLog().warn(msg);
                }
            } else if (comparison > 0) { // upper bound violation: newerVersion is greater than suggestedVersion
                final String msg = String.format(MSG_LOWER_VERSION, info.packageName, info.suggestedVersion, info.olderVersion, info.newerVersion);
                switch (enforcement) {
                    case lowerAndUpperBound:
                        failureReport.append(msg).append("\n");
                        getLog().error(msg);
                        break;
                    case lowerBound:
                    case none:
                        getLog().warn(msg);
                }
            }
        }

        if (failureReport.length() > 0) {
            throw new MojoFailureException(
                    "There were API changes, please adjust the following exported package versions.\n\n" +
                            failureReport.toString()
            );
        }
    }

    private Artifact resolveBaselineArtifact(Artifact artifact, ArtifactVersion baselineVersion)
            throws ArtifactNotFoundException, ArtifactResolutionException {
        final Artifact baselineArtifact = repositorySystem.createArtifact(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                baselineVersion.toString(),
                "compile",
                "jar"
        );
        resolveArtifact(baselineArtifact);
        return baselineArtifact;
    }

    private void resolveArtifact(Artifact baselineArtifact) {
        final ArtifactResolutionRequest request = new ArtifactResolutionRequest();
        request.setArtifact(baselineArtifact);
        request.setLocalRepository(localRepository);
        request.setRemoteRepositories(remoteRepositories);
        request.setOffline(session.isOffline());
        repositorySystem.resolve(request);
    }

    private ArtifactVersion computeBaselineVersion(Artifact artifact)
            throws ArtifactMetadataRetrievalException, OverConstrainedVersionException {
        final ArtifactVersion currentVersion = artifact.getSelectedVersion();
        final List<ArtifactVersion> availableVersions = getAvailableVersions(artifact);
        return selectBaselineVersion(currentVersion, availableVersions);
    }

    private static Set<Baseline.Info> baseline(File newer, File older) throws Exception {
        Baseline baseline = new Baseline(new ReporterAdapter(), new DiffPluginImpl());
        Jar n = new Jar(newer);
        Jar o = new Jar(older);

        return baseline.baseline(n, o, null);
        // for each package you have an info, detailing the findings
        // base version, new version, errors, suggested version, etc.
    }

    private List<ArtifactVersion> getAvailableVersions(final Artifact artifact)
            throws ArtifactMetadataRetrievalException {
        final Artifact nonSnapshotArtifact;
        if (artifact.isSnapshot()) {
            final String version = project.getVersion();
            final int snapshotIdx = version.indexOf("-SNAPSHOT");
            final String nonSnapshotVersion = version.substring(0, snapshotIdx);
            nonSnapshotArtifact = repositorySystem.createArtifact(
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    nonSnapshotVersion,
                    "compile",
                    "jar"
            );
        } else {
            nonSnapshotArtifact = artifact;
        }

        final List<ArtifactVersion> versions = artifactMetadataSource
                .retrieveAvailableVersions(nonSnapshotArtifact, localRepository, remoteRepositories);
        return versions;
    }

    public static enum Enforcement {
        lowerAndUpperBound,
        lowerBound,
        none
    }
}
