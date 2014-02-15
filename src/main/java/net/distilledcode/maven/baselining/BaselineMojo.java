package net.distilledcode.maven.baselining;

import aQute.bnd.differ.Baseline;
import aQute.bnd.differ.DiffPluginImpl;
import aQute.bnd.osgi.Jar;
import aQute.libg.reporter.ReporterAdapter;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataRetrievalException;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.OverConstrainedVersionException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.List;
import java.util.Set;

import static net.distilledcode.maven.baselining.BaselineVersionSelector.selectBaselineVersion;

@Mojo(
        name = "baseline",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresProject = true,
        threadSafe = true
)
public class BaselineMojo extends AbstractMojo {

    public static final String MSG_NO_BASELINE = "No baseline version found";

    public static final String MSG_BASELINING = "Baselining against version %s";

    public static final String MSG_RAISE_VERSION = "Please raise the version of package %s to %s";

    @Component
    private MavenProject project;

    @Component
    private ArtifactResolver resolver;

    @Component
    private ArtifactFactory artifactFactory;

    @Component
    private ArtifactMetadataSource artifactMetadataSource;

    @Parameter(defaultValue = "${localRepository}", readonly = true)
    private ArtifactRepository localRepository;

    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true)
    private List remoteRepositories;

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
        } catch (Exception e) {
            throw new MojoExecutionException("Unexpected exception during mojo execution", e);
        }
    }

    private void reportFindings(Set<Baseline.Info> baselineInfos) throws MojoFailureException {
        final StringBuilder report = new StringBuilder();
        for (final Baseline.Info info : baselineInfos) {
            if (!info.newerVersion.equals(info.suggestedVersion)) {
                final String msg = String.format(MSG_RAISE_VERSION, info.packageName, info.suggestedVersion);
                report.append(msg).append("\n");
                getLog().error(msg);
            }
        }

        if (report.length() > 0) {
            throw new MojoFailureException(
                    "There were API changes, please adjust the following exported package versions.\n\n" +
                            report.toString()
            );
        }
    }

    private Artifact resolveBaselineArtifact(Artifact artifact, ArtifactVersion baselineVersion)
            throws ArtifactNotFoundException, ArtifactResolutionException {
        final Artifact baselineArtifact = artifactFactory.createArtifact(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                baselineVersion.toString(),
                "compile",
                "jar"
        );
        resolver.resolve(baselineArtifact, remoteRepositories, localRepository);
        return baselineArtifact;
    }

    private ArtifactVersion computeBaselineVersion(Artifact artifact) throws ArtifactMetadataRetrievalException, OverConstrainedVersionException {
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
        @SuppressWarnings("unchecked")
        final List<ArtifactVersion> versions = (List<ArtifactVersion>) artifactMetadataSource
                .retrieveAvailableVersions(artifact, localRepository, remoteRepositories);
        return versions;
    }
}
