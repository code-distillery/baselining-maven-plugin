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
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

@Mojo(
        name = "baseline",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresProject = true,
        threadSafe = true
)
public class BaselineMojo extends AbstractMojo {

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

        final Artifact artifact = project.getArtifact();
        final ArtifactVersion artifactVersion;
        try {
            final ArtifactVersion activeArtifactVersion = artifact.getSelectedVersion();
            final List availableVersions = artifactMetadataSource
                    .retrieveAvailableVersions(artifact, localRepository, remoteRepositories);

            getLog().info("Artifact Metadata Source: " + artifactMetadataSource.toString() + " " + activeArtifactVersion.toString());

            Collections.sort(availableVersions, Collections.reverseOrder());
            final Iterator iterator = availableVersions.iterator();
            while(iterator.hasNext()) {
                final ArtifactVersion next = (ArtifactVersion)iterator.next();
                if ("SNAPSHOT".equals(next.getQualifier()) || next.equals(activeArtifactVersion)) {
                    iterator.remove();
                }
            }

            if (availableVersions.isEmpty()) {
                getLog().info("No baseline version found.");
                return;
            }

            for (final Object version : availableVersions) {
                getLog().info("Available artifact version: " + version.toString());
            }


            // TODO: make sure artifactVersions are not bigger than current
            artifactVersion = (ArtifactVersion)availableVersions.get(0);

        } catch (ArtifactMetadataRetrievalException e) {
            throw new MojoExecutionException("Failed to retrieve available versions.", e);
        } catch (OverConstrainedVersionException e) {
            throw new MojoExecutionException("Problem with artifact version.", e);
        }

        final Artifact oldArtifact;
        try {
             oldArtifact = artifactFactory.createArtifact(
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    artifactVersion.toString(),
                    "compile",
                    "jar"
            );
            //getLog().info("Local repository: " + localRepository + " " + remoteRepositories.toString());
            resolver.resolve(oldArtifact, remoteRepositories, localRepository);
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException("Failed to resolve baseline artifact.", e);
        } catch (ArtifactNotFoundException e) {
            throw new MojoExecutionException("Baseline artifact not found.", e);
        }

        final File newJar = artifact.getFile();
        final File oldJar = oldArtifact.getFile();
        final StringBuilder suggestions;
        try {
            getLog().info("Baselining against artifact version " + artifactVersion);
            final Set<Baseline.Info> baselineInfos = baseline(newJar, oldJar);
            suggestions = new StringBuilder();
            for (final Baseline.Info info : baselineInfos) {
                if (!info.newerVersion.equals(info.suggestedVersion)) {
                    final String msg = "Suggested export for package: " + info.packageName + ";version=\"" + info.suggestedVersion + "\"";
                    suggestions.append(msg).append("\n");
                    getLog().error(msg);
                }
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Error while baselining.", e);
        }

        if (suggestions.length() > 0) {
            throw new MojoFailureException(
                    "There were API changes, some package exports need to be adjusted.\n\n" +
                            suggestions.toString()
            );
        }
    }

    private static Set<Baseline.Info> baseline(File newer, File older) throws Exception {
        Baseline baseline = new Baseline(new ReporterAdapter(), new DiffPluginImpl());
        Jar n = new Jar(newer);
        Jar o = new Jar(older);

        return baseline.baseline(n, o, null);
        // for each package you have an info, detailing the findings
        // base version, new version, errors, suggested version, etc.
    }
}
