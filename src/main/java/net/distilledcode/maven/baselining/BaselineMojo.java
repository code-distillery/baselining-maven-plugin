package net.distilledcode.maven.baselining;

import aQute.bnd.differ.Baseline;
import aQute.bnd.differ.DiffPluginImpl;
import aQute.bnd.osgi.Jar;
import aQute.bnd.service.diff.Delta;
import aQute.bnd.service.diff.Diff;
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
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.Collection;
import java.util.Iterator;
import java.util.Stack;

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

    public static final String BUNDLE_SYMBOLIC_NAME = "Bundle-SymbolicName";

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
     * Whether or not to explain why an export version needs to be raised.
     */
    @Parameter(property = "baselining.baseline.explain", defaultValue = "false")
    private boolean explain;

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

    /*
     * If set to true the plugin execution will be skipped.
     *
     * @since 1.0.4
     */
    @Parameter(property = "baselining.baseline.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(defaultValue = "${localRepository}", readonly = true)
    private ArtifactRepository localRepository;

    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true)
    private List<ArtifactRepository> remoteRepositories;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final Artifact artifact = project.getArtifact();

        if (skip) {
            getLog().debug("Execution skipped via property \"baselining.baseline.skip\"");
            return;
        }
        if (isBundle(artifact.getFile())) {
            getLog().debug("Execution skipped, artifact is not a jar file.");
            return;
        }

        try {
            final ArtifactVersion baselineVersion = computeBaselineVersion(artifact);
            if (baselineVersion == null) {
                getLog().info(MSG_NO_BASELINE);
            } else {
                getLog().info(String.format(MSG_BASELINING, baselineVersion));
                final Artifact baselineArtifact = resolveBaselineArtifact(artifact, baselineVersion);
                final Set<Baseline.Info> baselineInfos = baseline(artifact.getFile(), baselineArtifact.getFile());
                final Iterator<Baseline.Info> iterator = baselineInfos.iterator();
                while(iterator.hasNext()) {
                    final Baseline.Info info = iterator.next();
                    if (info.packageDiff.getDelta() == Delta.UNCHANGED) {
                        iterator.remove();
                    }
                }
                reportFindings(baselineInfos);
            }
        } catch(MojoFailureException e) {
            throw e; // rethrow MojoFailureException as that can be a desired outcome
        } catch (Exception e) {
            throw new MojoExecutionException("Unexpected exception during mojo execution", e);
        }
    }

    private void reportFindings(Set<Baseline.Info> baselineInfos) throws MojoFailureException {

        if (baselineInfos.size() == 0) {
            getLog().info("No API changes found.");
            return;
        }

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

            if (comparison != 0 && explain) {
                explain(getLog(), info.packageDiff, new Stack<Diff>());
            }
        }

        if (failureReport.length() > 0) {
            throw new MojoFailureException(
                    "There were API changes, please adjust the following exported package versions.\n\n" +
                            failureReport.toString()
            );
        }
    }

    private static void explain(Log log, Diff diff, Stack<Diff> ancestorDiffs) {
        ancestorDiffs.push(diff);
        switch (diff.getDelta()) {
            case ADDED:
            case REMOVED:
                switch (diff.getType()) {
                    case VERSION:
                        // ignore changes for these
                        break;
                    default:
                        final String prefix = diff.getDelta() == Delta.ADDED ? "   + " : "   - ";
                        log.info(prefix + ancestorsToString(ancestorDiffs));
                        break;
                }
                break;
            case CHANGED:
            case MAJOR:
            case MINOR:
            case MICRO:
                final Collection<? extends Diff> children = diff.getChildren();
                for (final Diff childDiff : children) {
                    explain(log, childDiff, ancestorDiffs);
                }
                break;
            case UNCHANGED:
            default:
                // do nothing
                break;
        }
        ancestorDiffs.pop();
    }

    private static String ancestorsToString(Stack<Diff> ancestorDiffs) {
        final StringBuilder sb = new StringBuilder();
        final String description = ancestorDiffs.peek().getType().toString().toLowerCase();
        final String packageName = ancestorDiffs.get(0).getName();
        sb.append(description).append(" ");
        for (final Diff diff : ancestorDiffs) {
            switch (diff.getType()) {
                case EXTENDS:
                case IMPLEMENTS:
                    sb.replace(0, description.length(), "inheritance");
                    sb.append(" ").append(description).append(" ");
                    sb.append(fqnToAbbreviatedClassName(diff.getName(), packageName));
                    break;
                case CLASS:
                case ENUM:
                case INTERFACE:
                    sb.append(fqnToAbbreviatedClassName(diff.getName(), packageName));
                    break;
                case CONSTANT:
                case FIELD:
                    sb.append("#").append(diff.getName());
                    break;
                case METHOD:
                    sb.append("#").append(abbreviateMethodArguments(diff.getName(), packageName));
                    break;
                case ANNOTATED:
                    sb.append(" with ").append(diff.getName());
                    break;
                case PROPERTY:
                    sb.insert(0, "annotation-");
                    sb.append("(").append(diff.getName()).append(")");
                    break;
                case VERSION:
                    break;
                case RETURN:
                    sb.insert(description.length(), fqnToAbbreviatedClassName(diff.getName(), packageName) + " from");
                    break;
                case ACCESS:
                    sb.insert(description.length(), diff.getName() + " of");
                    break;
                case PACKAGE:
                    if (ancestorDiffs.size() == 1) {
                        sb.append(diff.getName());
                    }
                    break;
                case ANNOTATION:
                default:
                    sb.append(" *** ");
                    sb.append(diff.getName());
                    break;
            }
        }
        return sb.toString();
    }

    private static String abbreviateMethodArguments(String methodSignature, String packageName) {
        final int open = methodSignature.indexOf("(");
        final int close = methodSignature.indexOf(")");
        final String argumentString = methodSignature.substring(open + 1, close);
        final String[] arguments = argumentString.split(",");
        final StringBuilder sb = new StringBuilder(methodSignature.substring(0, open + 1));
        for (final String arg : arguments) {
            sb.append(fqnToAbbreviatedClassName(arg, packageName)).append(",");
        }
        sb.setLength(sb.length() - 1); // strip off trailing comma
        sb.append(methodSignature.substring(close, methodSignature.length()));
        return  sb.toString();
    }

    private static String fqnToAbbreviatedClassName(final String className, String packageName) {
        if (className.startsWith(packageName)) {
            return className.substring(packageName.length() + 1);
        } else {
            return className;
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
        final Baseline baseline = new Baseline(new ReporterAdapter(), new DiffPluginImpl());
        final Jar n = new Jar(newer);
        final Jar o = new Jar(older);
        return baseline.baseline(n, o, null);
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

    private boolean isBundle(final File artifactFile) {
        if (!artifactFile.getName().endsWith(".jar") || !artifactFile.exists()) {
            return false;
        }
        final Manifest manifest = loadManifest(artifactFile);
        if (manifest == null) {
            return false;
        }
        final String symbolicName = (String) manifest.getMainAttributes().get(BUNDLE_SYMBOLIC_NAME);
        return symbolicName != null;
    }

    private Manifest loadManifest(final File artifactFile) {
        JarFile jarFile = null;
        try {
            jarFile = new JarFile(artifactFile, false, JarFile.OPEN_READ);
            return jarFile.getManifest();
        } catch (IOException ioe) {
            getLog().error("Error reading JAR manifest:", ioe);
            return null;
        } finally {
            if (jarFile != null) {
                try {
                    jarFile.close();
                } catch (IOException ignore) {
                    // ignore
                }
            }
        }
    }

    public static enum Enforcement {
        lowerAndUpperBound,
        lowerBound,
        none
    }
}
