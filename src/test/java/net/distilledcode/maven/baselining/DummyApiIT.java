package net.distilledcode.maven.baselining;

import org.apache.maven.it.VerificationException;
import org.apache.maven.it.Verifier;
import org.apache.maven.it.util.ResourceExtractor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static java.util.Arrays.asList;

public class DummyApiIT {

    private static final String GROUP_ID = "net.distilledcode.maven.baselining-maven-plugin.it";

    private static final String MAVEN_REPO_LOCAL = "maven.repo.local";

    private static Verifier baseVerifier;

    @BeforeClass
    public static void setupClass() {
        try {
            baseVerifier = createVerifier("dummy-1.0.0");
            baseVerifier.executeGoal("install");
            baseVerifier.verifyErrorFreeLog();
            baseVerifier.verifyTextInLog(BaselineMojo.MSG_NO_BASELINE);
        } catch (VerificationException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @AfterClass
    public static void tearDownClass() {
        try {
            baseVerifier.deleteArtifacts(GROUP_ID);
            baseVerifier = null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void noApiChanges() throws IOException, VerificationException {
        final Verifier verifier = createVerifier("dummy-1.0.1-SNAPSHOT");
        verifier.executeGoal("package");
        verifier.verifyErrorFreeLog();
        verifier.verifyTextInLog(String.format(BaselineMojo.MSG_BASELINING, "1.0.0"));
    }

    @Test
    public void methodAdded() throws IOException, VerificationException {
        final Verifier verifier0 = createVerifier("dummy-1.0.1-SNAPSHOT");
        verifier0.executeGoal("install");
        final Verifier verifier = createVerifier("dummy-1.0.2");
        try {
            verifier.executeGoal("package");
        } catch (VerificationException e) {
            // build failure expected
        }
        verifier.verifyTextInLog(String.format(BaselineMojo.MSG_BASELINING, "1.0.0"));
        verifier.verifyTextInLog(String.format(BaselineMojo.MSG_RAISE_VERSION, "dummy", "1.1.0", "1.0.0", "1.0.0"));
        verifier.verifyTextInLog("BUILD FAILURE");

        // cleanup installed artifacts
        verifier.deleteArtifacts(GROUP_ID, "dummy", "1.0.1-SNAPSHOT");
    }

    @Test
    public void requireLowerExportVersion() throws IOException, VerificationException {
        final Verifier verifier = createVerifier("dummy-1.0.2-require-lower-export");
        try {
            verifier.executeGoal("package");
        } catch (VerificationException e) {
            // build failure expected
        }
        verifier.verifyTextInLog(String.format(BaselineMojo.MSG_BASELINING, "1.0.0"));
        verifier.verifyTextInLog(String.format(BaselineMojo.MSG_LOWER_VERSION, "dummy", "1.1.0", "1.0.0", "2.0.0"));
        verifier.verifyTextInLog("BUILD FAILURE");
    }

    @Test
    public void breakingChange() throws IOException, VerificationException {
        final Verifier verifier = createVerifier("dummy-1.0.2-breaking-change");
        try {
            verifier.executeGoal("package");
        } catch (VerificationException e) {
            // build failure expected
        }
        verifier.verifyTextInLog(String.format(BaselineMojo.MSG_BASELINING, "1.0.0"));
        verifier.verifyTextInLog(String.format(BaselineMojo.MSG_RAISE_VERSION, "dummy", "2.0.0", "1.0.0", "1.0.0"));
        verifier.verifyTextInLog("BUILD FAILURE");
    }

    @Test
    public void enforceBundleVersion() throws IOException, VerificationException {
        final Verifier verifier = createVerifier("dummy-1.0.2-wrong-bundle-version");
        verifier.setSystemProperty("baselining.baseline.enforcebundleversion", "true");
        try {
            verifier.executeGoal("package");
        } catch (VerificationException e) {
            // build failure expected
        }
        verifier.verifyTextInLog(String.format(BaselineMojo.MSG_BASELINING, "1.0.0"));
        verifier.verifyTextInLog(String.format(BaselineMojo.MSG_RAISE_BUNDLE_VERSION, "2.0.0", "1.0.0", "1.0.2"));
        verifier.verifyTextInLog("BUILD FAILURE");
    }

    private static Verifier createVerifier(final String testFolderName) throws IOException, VerificationException {
        final File testDir = ResourceExtractor.simpleExtractResources(DummyApiIT.class, "/" + testFolderName);
        final File settingsXml = new File(testDir.getParent(), "settings.xml");

        // During release:prepare the system property maven.repo.local is set (probably
        // for some forked process).
        // Verifier gives precedence to the system property (correctly IMHO), rather than
        // the setting in settings.xml, which results in an incorrect local repository
        // during release:prepare and thus test failures. To work around this issue, we
        // temporarily clear the system property during the creation of the Verifier instance.
        final String originalLocalRepo = System.getProperty(MAVEN_REPO_LOCAL);
        System.clearProperty(MAVEN_REPO_LOCAL);
        final Verifier verifier= new Verifier(testDir.getAbsolutePath(), settingsXml.getAbsolutePath());
        verifier.setCliOptions(asList("-s", settingsXml.getAbsolutePath()));
        if (originalLocalRepo != null) {
            System.setProperty(MAVEN_REPO_LOCAL, originalLocalRepo);
        }
        return verifier;
    }
}